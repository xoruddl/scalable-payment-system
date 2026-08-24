package com.remittance.account.domain;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.CurrencyMismatchException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잔액이 {@link AccountBalanceShard}로 나가면서 이 클래스에 남은 규칙은
 * <b>계좌를 쓸 수 있는가</b>뿐이다. 금액 계산은 {@link AccountBalanceTest}에 있다.
 */
class AccountTest {

	private Account newAccount() {
		return Account.builder().ownerId(UUID.randomUUID()).currency("KRW").accountType(AccountType.PERSONAL).build();
	}

	@Test
	void 새_계좌는_쪼개지_않은_상태로_시작한다() {
		// 계좌 대부분은 경합이 없다. 쪼개는 것은 붐비는 계좌에만 하는 처방이다.
		assertThat(newAccount().getShardCount()).isEqualTo((short) 1);
	}

	@Test
	void 통화가_같고_활성이면_통과한다() {
		assertThatCode(() -> newAccount().assertUsable("KRW")).doesNotThrowAnyException();
	}

	@Test
	void 통화가_다르면_예외() {
		assertThatThrownBy(() -> newAccount().assertUsable("USD"))
				.isInstanceOf(CurrencyMismatchException.class);
	}

	@Test
	void 활성상태가_아니면_예외() {
		Account account = newAccount();
		account.freeze();

		assertThatThrownBy(() -> account.assertUsable("KRW"))
				.isInstanceOf(AccountNotActiveException.class);
	}

	@Test
	void 조각을_늘릴_수는_있어도_줄일_수는_없다() {
		Account account = newAccount();

		account.widenShards((short) 4);
		assertThat(account.getShardCount()).isEqualTo((short) 4);

		// 줄이려면 없어지는 조각의 돈을 옮겨야 하는데 그 절차가 없다.
		// 조용히 받아주면 그 조각의 잔액이 조회에서 사라진다.
		assertThatThrownBy(() -> account.widenShards((short) 2))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
