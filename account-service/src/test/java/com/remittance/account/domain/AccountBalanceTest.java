package com.remittance.account.domain;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잔액 계산 규칙. 전에는 {@code Account}에 있었는데, 잔액이 조각으로 나가면서 여기로 옮겨왔다.
 */
class AccountBalanceTest {

	private final Account account = Account.builder()
			.ownerId(UUID.randomUUID()).currency("KRW").accountType(AccountType.PERSONAL).build();

	private AccountBalanceShard shard(int shardNo, String balance) {
		return new AccountBalanceShard(account.getAccountId(), (short) shardNo, new BigDecimal(balance));
	}

	private AccountBalance whole(String... balances) {
		List<AccountBalanceShard> shards = new java.util.ArrayList<>();
		for (int i = 0; i < balances.length; i++) {
			shards.add(shard(i, balances[i]));
		}
		return AccountBalance.whole(account, shards);
	}

	@Test
	void 잔액은_조각들의_합이다() {
		assertThat(whole("100", "250", "0").total()).isEqualByComparingTo("350");
	}

	@Test
	void 조각이_하나도_없으면_예외() {
		// 0원으로 넘어가면 "없는 계좌"와 "빈 계좌"를 구분하지 못한다.
		assertThatThrownBy(() -> AccountBalance.whole(account, List.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 입금은_읽어온_첫_조각에만_들어간다() {
		AccountBalanceShard target = shard(2, "0");
		// 쪼갠 계좌의 입금은 조각 하나만 읽어온다. 나머지 900은 읽은 시점의 합이다.
		AccountBalance balance = AccountBalance.onlyShard(account, target, new BigDecimal("900"));

		balance.credit(new BigDecimal("100"), "KRW");

		assertThat(target.getBalance()).isEqualByComparingTo("100");
		assertThat(balance.total()).isEqualByComparingTo("1000");
	}

	@Test
	void 출금은_앞_조각부터_훑어_뺀다() {
		AccountBalance balance = whole("30", "50", "20");

		balance.debit(new BigDecimal("60"), "KRW");

		// 30을 다 쓰고 다음 조각에서 30을 더 뺀다.
		assertThat(balance.shards()).extracting(AccountBalanceShard::getBalance)
				.usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
				.containsExactly(new BigDecimal("0"), new BigDecimal("20"), new BigDecimal("20"));
		assertThat(balance.total()).isEqualByComparingTo("40");
	}

	@Test
	void 한_조각에는_모자라도_합이_되면_출금된다() {
		// 이게 조각 하나만 보고 거절하면 안 되는 이유다 — 계좌에는 있는 돈이다.
		AccountBalance balance = whole("40", "40");

		balance.debit(new BigDecimal("70"), "KRW");

		assertThat(balance.total()).isEqualByComparingTo("10");
	}

	@Test
	void 합이_모자라면_예외() {
		assertThatThrownBy(() -> whole("40", "40").debit(new BigDecimal("100"), "KRW"))
				.isInstanceOf(InsufficientBalanceException.class);
	}

	@Test
	void 조각을_전부_읽지_않고는_출금할_수_없다() {
		// 안 읽은 조각의 돈을 뺄 수는 없다. 조용히 넘어가면 "합은 되는데 못 뺀" 상태가 된다.
		AccountBalance partial = AccountBalance.onlyShard(account, shard(1, "50"), new BigDecimal("500"));

		assertThatThrownBy(() -> partial.debit(new BigDecimal("10"), "KRW"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 통화가_다르면_예외() {
		assertThatThrownBy(() -> whole("1000").debit(new BigDecimal("100"), "USD"))
				.isInstanceOf(CurrencyMismatchException.class);
	}

	@Test
	void 활성상태가_아니면_예외() {
		account.freeze();

		assertThatThrownBy(() -> whole("1000").credit(new BigDecimal("100"), "KRW"))
				.isInstanceOf(AccountNotActiveException.class);
	}
}
