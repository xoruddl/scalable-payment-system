package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalance;
import com.remittance.account.domain.AccountBalanceShard;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.lock.AccountLockPolicy;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.repository.AccountBalanceShardRepository;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 잔액 조각을 읽어오고 저장하는 한 곳. 조각을 몇 개 읽을지가 여기서 갈린다.
 *
 *   연산  읽는 조각      왜
 *   입금  하나 (무작위)  더하기만 하므로 다른 조각을 볼 이유가 없다
 *   출금  전부           합을 알아야 모자란지 판단할 수 있다
 *   조회  전부           합이 곧 답이다
 *
 * 입금만 쪼개지는 것이 이 설계의 전부다. 출금은 오히려 느려진다 — 전에는 행 하나였는데
 * 이제 N행을 읽는다. 핫 계좌는 받는 쪽이라 그 대가를 치를 만하다고 봤다.
 *
 * 잠그며 읽느냐는 여기서 갈린다 (Phase 6.7)
 * 행 락을 쓰는 전략({@code LAYERED} · {@code PESSIMISTIC})이면 잔액을 바꾸려고 읽는 자리에서
 * 행 락을 함께 잡는다. {@code LAYERED}는 그 앞에 Redis 락이 한 겹 더 있다({@link BalanceGuard}).
 * 읽기만 하는 자리({@link #whole})는 어느 전략에서도 안 잠근다 — 조회 API와 대사가
 * 그 길로 들어오는데, 읽기 전용 트랜잭션에서 {@code FOR UPDATE}는 실행되지 않는다.
 *
 * 그래서 "바꾸려고 읽는 것"과 "보려고 읽는 것"을 메서드로 갈라두었다.
 * 호출부가 고르는 것이 아니라 여기서 정한다 — 한 곳만 잘못 골라도 조용히 틀린다.
 */
@Component
@RequiredArgsConstructor
public class BalanceShards {

	private final AccountRepository accountRepository;
	private final AccountBalanceShardRepository shardRepository;
	private final AccountLockPolicy lockPolicy;

	/** 계좌를 만들 때 0번 조각을 함께 만든다. 조각 없는 계좌는 존재할 수 없다. */
	public void createFirstShard(Account account) {
		shardRepository.save(new AccountBalanceShard(account.getAccountId(), (short) 0, BigDecimal.ZERO));
	}

	/**
	 * 이 변경에 필요한 만큼만 읽는다.
	 *
	 * 방향이 곧 읽을 범위다 — 넣는 것은 조각 하나면 되고, 빼는 것은 합을 알아야 한다.
	 * 호출부가 매번 고르게 하면 한 곳만 잘못 골라도 조용히 틀린다(안 읽은 조각의 돈이
	 * 없는 것처럼 보인다). 그래서 여기서 정한다.
	 */
	public AccountBalance load(UUID accountId, AccountEvents.TransactionDirection direction, short shardNo) {
		return direction == AccountEvents.TransactionDirection.CREDIT
				? forCredit(accountId, shardNo) : wholeForUpdate(accountId);
	}

	/**
	 * 조각을 전부 읽는다. 조회 API와 대사가 쓴다 — 보기만 하므로 잠그지 않는다.
	 * 바꾸려고 읽는 자리는 {@link #wholeForUpdate}다.
	 */
	public AccountBalance whole(UUID accountId) {
		return AccountBalance.whole(account(accountId), shardRepository.findByAccountIdOrderByShardNoAsc(accountId));
	}

	/**
	 * 조각을 전부 읽되, 그 사이에 아무도 못 바꾸게 한다. 출금과 개시 잔액 이월이 쓴다.
	 *
	 * 둘 다 합을 보고 판단한다 — 모자란지, 원장과 얼마나 벌어졌는지. 판단과 반영 사이에
	 * 조각이 움직이면 그 판단이 헛것이 된다. {@code DISTRIBUTED}는 그 구간을 Redis 락으로
	 * 막고, {@code PESSIMISTIC}은 여기서 행 락으로 막는다. {@code LAYERED}는 둘 다다 —
	 * Redis 락이 먼저 사라져도(연장 실패 · 장애 전환 · 폴백) 여기서 한 번 더 막힌다.
	 */
	public AccountBalance wholeForUpdate(UUID accountId) {
		return AccountBalance.whole(account(accountId), shardsForUpdate(accountId));
	}

	/**
	 * 넣을 조각 하나만 읽는다. 입금이 쓴다.
	 *
	 * 조각이 하나뿐인 계좌(대부분)는 전부 읽는 것과 같다. 그래서 나머지 합을 구하는
	 * 쿼리를 아예 내보내지 않는다 — 안 쪼갠 계좌가 쪼개기 때문에 느려지면 안 된다.
	 *
	 * 나머지 합({@code totalExcluding})은 잠그지 않는다. 분개장에 적을 "변경 후 잔액"을
	 * 만드는 값이라 근사치여도 되고, 잠그면 조각을 가른 이유가 사라진다 —
	 * 입금끼리 다시 한 줄로 서게 된다.
	 */
	private AccountBalance forCredit(UUID accountId, short shardNo) {
		Account account = account(accountId);
		if (account.getShardCount() <= 1) {
			return AccountBalance.whole(account, shardsForUpdate(accountId));
		}
		AccountBalanceShard shard = shardForUpdate(accountId, shardNo);
		return AccountBalance.onlyShard(account, shard, shardRepository.totalExcluding(accountId, shardNo));
	}

	/** 바뀐 조각을 즉시 반영한다. 낙관적 락 충돌을 이 트랜잭션 안에서 만나야 재시도가 걸린다. */
	public void flush(AccountBalance balance) {
		List<AccountBalanceShard> shards = balance.shards();
		shardRepository.saveAll(shards);
		shardRepository.flush();
	}

	private Account account(UUID accountId) {
		return accountRepository.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
	}

	/** 전략이 고르는 자리는 여기 둘뿐이다. 나머지 코드는 락을 의식하지 않는다. */
	private List<AccountBalanceShard> shardsForUpdate(UUID accountId) {
		return lockPolicy.usesPessimisticLock()
				? shardRepository.findForUpdateByAccountIdOrderByShardNoAsc(accountId)
				: shardRepository.findByAccountIdOrderByShardNoAsc(accountId);
	}

	private AccountBalanceShard shardForUpdate(UUID accountId, short shardNo) {
		return (lockPolicy.usesPessimisticLock()
				? shardRepository.findForUpdateByAccountIdAndShardNo(accountId, shardNo)
				: shardRepository.findByAccountIdAndShardNo(accountId, shardNo))
				.orElseThrow(() -> new IllegalStateException(
						"있어야 할 조각이 없다 (accountId=%s, shardNo=%d)".formatted(accountId, shardNo)));
	}
}
