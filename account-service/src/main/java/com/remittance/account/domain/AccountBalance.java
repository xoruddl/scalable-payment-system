package com.remittance.account.domain;

import com.remittance.account.exception.InsufficientBalanceException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 계좌 하나와 <b>그 계좌 잔액의 조각들</b>을 함께 다루는 단위. 잔액을 바꾸는 모든 일이 여기를 거친다.
 *
 * <p>엔티티가 아니라 <b>한 트랜잭션 동안만 사는 묶음</b>이다. 계좌와 조각이 서로 다른 테이블에
 * 있는데 업무 규칙(잔액이 모자라면 못 뺀다, 정지된 계좌는 못 쓴다)은 둘을 함께 봐야 성립한다.
 * 그 규칙이 서비스 코드로 흩어지지 않게 여기 모은다.
 *
 * <p>전에는 이 자리가 {@code Account}였다. 잔액이 계좌 행에 있었기 때문이다.
 * 옮기고 나서 {@code Account}에 남은 것은 <b>계좌가 쓸 수 있는 상태인가</b>뿐이다.
 */
public final class AccountBalance {

	private final Account account;
	/** 이 연산이 실제로 읽어온 조각들. 항상 {@code shardNo} 오름차순이다. */
	private final List<AccountBalanceShard> shards;
	/** 읽지 <b>않은</b> 조각들의 합. 입금은 조각 하나만 읽으므로 나머지가 여기 담긴다. */
	private final BigDecimal unreadTotal;

	private AccountBalance(Account account, List<AccountBalanceShard> shards, BigDecimal unreadTotal) {
		if (shards.isEmpty()) {
			// 계좌를 만들 때 0번 조각을 함께 만든다. 조각이 없다는 건 그 절차가 깨졌다는 뜻이라
			// 잔액 0으로 넘어가면 안 된다 — 없는 돈을 0원이라고 답하게 된다.
			throw new IllegalStateException("계좌에 잔액 조각이 하나도 없다: " + account.getAccountId());
		}
		this.account = account;
		this.shards = shards;
		this.unreadTotal = unreadTotal;
	}

	/**
	 * 조각을 <b>전부</b> 읽어온 경우. 출금과 조회가 이걸 쓴다 — 합을 알아야 하기 때문이다.
	 */
	public static AccountBalance whole(Account account, List<AccountBalanceShard> shards) {
		return new AccountBalance(account, shards, BigDecimal.ZERO);
	}

	/**
	 * 조각 <b>하나만</b> 읽어온 경우. 입금이 이걸 쓴다.
	 *
	 * <p>여기가 쪼갠 이득이 나오는 지점이다. 조각 하나만 읽고 그 행만 바꾸므로,
	 * 같은 계좌의 다른 입금이 다른 조각을 만지는 한 서로 기다리지 않는다.
	 *
	 * @param unreadTotal 읽지 않은 조각들의 합. 분개장에 남길 <b>변경 후 잔액</b>을 만드는 데 쓴다.
	 *                    조각이 하나뿐인 계좌면 0이다.
	 */
	public static AccountBalance onlyShard(Account account, AccountBalanceShard shard, BigDecimal unreadTotal) {
		return new AccountBalance(account, List.of(shard), unreadTotal);
	}

	public UUID getAccountId() {
		return account.getAccountId();
	}

	public String getCurrency() {
		return account.getCurrency();
	}

	public Account account() {
		return account;
	}

	/**
	 * 조각들의 합. 이게 이 계좌의 잔액이다.
	 *
	 * <p>⚠️ 조각 하나만 읽어온 경우({@link #onlyShard}) 나머지 조각의 합은 <b>읽은 시점의 값</b>이다.
	 * 그 사이 다른 입금이 커밋됐으면 여기 반영되지 않는다. <b>쪼갠 계좌의 "변경 후 잔액"은
	 * 근사치</b>라는 뜻이고, 이것도 쪼개는 대가다. 정합성 대사는 이 값을 쓰지 않는다 —
	 * 원장은 금액을 direction대로 합산하므로 영향이 없다.
	 */
	public BigDecimal total() {
		return shards.stream().map(AccountBalanceShard::getBalance)
				.reduce(unreadTotal, BigDecimal::add);
	}

	/**
	 * 넣는다. <b>읽어온 첫 조각에만</b> 더한다 — 어느 조각에 넣을지는 읽어올 때 이미 정해졌다.
	 */
	public void credit(BigDecimal amount, String currency) {
		account.assertUsable(currency);
		shards.get(0).add(amount);
	}

	/**
	 * 뺀다. <b>합을 보고 조각들에서 훑어 뺀다.</b>
	 *
	 * <p>입금과 달리 조각 하나만 볼 수 없다. 그 조각에 없어도 옆 조각에 있으면 계좌에는 있는
	 * 돈이라, 조각 하나만 보고 거절하면 <b>잔액이 있는데 실패하는</b> 일이 생긴다.
	 * 그래서 출금은 계좌 전체를 본다 — <b>쪼갠 이득이 출금에는 없다</b>는 뜻이고, 이게 대가다.
	 *
	 * <p>앞 조각부터 채워 뺀다. 순서를 고정하는 이유는 같은 계좌에 출금이 둘 동시에 오면
	 * 서로 반대 순서로 조각을 잡아 교착에 빠질 수 있기 때문이다.
	 */
	public void debit(BigDecimal amount, String currency) {
		account.assertUsable(currency);
		if (unreadTotal.signum() != 0) {
			throw new IllegalStateException("조각을 전부 읽지 않고 출금할 수 없다: " + getAccountId());
		}
		if (total().compareTo(amount) < 0) {
			throw new InsufficientBalanceException(getAccountId());
		}
		BigDecimal remaining = amount;
		for (AccountBalanceShard shard : shards) {
			if (remaining.signum() == 0) {
				break;
			}
			BigDecimal take = shard.getBalance().min(remaining);
			if (take.signum() <= 0) {
				continue;
			}
			shard.subtract(take);
			remaining = remaining.subtract(take);
		}
	}

	/** 이 연산이 읽어온 조각들. 저장할 때 쓴다. */
	public List<AccountBalanceShard> shards() {
		return shards;
	}
}
