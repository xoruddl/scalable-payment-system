package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalance;
import com.remittance.account.domain.AccountBalanceShard;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.repository.AccountBalanceShardRepository;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 잔액 조각을 <b>읽어오고 저장하는</b> 한 곳. 조각을 몇 개 읽을지가 여기서 갈린다.
 *
 * <table>
 *   <tr><th>연산</th><th>읽는 조각</th><th>왜</th></tr>
 *   <tr><td>입금</td><td><b>하나</b> (무작위)</td><td>더하기만 하므로 다른 조각을 볼 이유가 없다</td></tr>
 *   <tr><td>출금</td><td>전부</td><td>합을 알아야 모자란지 판단할 수 있다</td></tr>
 *   <tr><td>조회</td><td>전부</td><td>합이 곧 답이다</td></tr>
 * </table>
 *
 * <p>입금만 쪼개지는 것이 이 설계의 전부다. <b>출금은 오히려 느려진다</b> — 전에는 행 하나였는데
 * 이제 N행을 읽는다. 핫 계좌는 <b>받는 쪽</b>이라 그 대가를 치를 만하다고 봤다.
 */
@Component
@RequiredArgsConstructor
public class BalanceShards {

	private final AccountRepository accountRepository;
	private final AccountBalanceShardRepository shardRepository;

	/** 계좌를 만들 때 0번 조각을 함께 만든다. 조각 없는 계좌는 존재할 수 없다. */
	public void createFirstShard(Account account) {
		shardRepository.save(new AccountBalanceShard(account.getAccountId(), (short) 0, BigDecimal.ZERO));
	}

	/**
	 * 이 변경에 필요한 만큼만 읽는다.
	 *
	 * <p>방향이 곧 읽을 범위다 — 넣는 것은 조각 하나면 되고, 빼는 것은 합을 알아야 한다.
	 * 호출부가 매번 고르게 하면 <b>한 곳만 잘못 골라도 조용히 틀린다</b>(안 읽은 조각의 돈이
	 * 없는 것처럼 보인다). 그래서 여기서 정한다.
	 */
	public AccountBalance load(UUID accountId, AccountEvents.TransactionDirection direction) {
		return direction == AccountEvents.TransactionDirection.CREDIT
				? forCredit(accountId) : whole(accountId);
	}

	/** 조각을 전부 읽는다. 출금과 조회가 쓴다. */
	public AccountBalance whole(UUID accountId) {
		Account account = account(accountId);
		return AccountBalance.whole(account, shardRepository.findByAccountIdOrderByShardNoAsc(accountId));
	}

	/**
	 * 넣을 조각 하나만 읽는다. 입금이 쓴다.
	 *
	 * <p>조각이 하나뿐인 계좌(대부분)는 <b>전부 읽는 것과 같다.</b> 그래서 나머지 합을 구하는
	 * 쿼리를 아예 내보내지 않는다 — 안 쪼갠 계좌가 쪼개기 때문에 느려지면 안 된다.
	 */
	public AccountBalance forCredit(UUID accountId) {
		Account account = account(accountId);
		if (account.getShardCount() <= 1) {
			return AccountBalance.whole(account, shardRepository.findByAccountIdOrderByShardNoAsc(accountId));
		}
		short shardNo = pick(account.getShardCount());
		AccountBalanceShard shard = shardRepository.findByAccountIdAndShardNo(accountId, shardNo)
				.orElseThrow(() -> new IllegalStateException(
						"있어야 할 조각이 없다 (accountId=%s, shardNo=%d)".formatted(accountId, shardNo)));
		return AccountBalance.onlyShard(account, shard, shardRepository.totalExcluding(accountId, shardNo));
	}

	/**
	 * 어느 조각에 넣을지 고른다. <b>무작위</b>다.
	 *
	 * <p>순번을 돌리면(round-robin) 인스턴스마다 자기 순번을 갖게 되어, 인스턴스가 여럿이면
	 * 같은 조각을 동시에 고르는 일이 오히려 잦아진다. 무작위는 그런 결이 없다.
	 * 송금 ID로 해시하는 방법도 있지만, 그러면 <b>같은 송금의 재시도가 같은 조각으로 몰려</b>
	 * 경합이 난 조각을 계속 다시 두드리게 된다.
	 */
	private short pick(short shardCount) {
		return (short) ThreadLocalRandom.current().nextInt(shardCount);
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
}
