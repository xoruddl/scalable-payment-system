package com.remittance.account.domain;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

	private Account newAccount() {
		return Account.builder().ownerId(UUID.randomUUID()).currency("KRW").accountType(AccountType.PERSONAL).build();
	}

	@Test
	void credit_증가한다() {
		Account account = newAccount();

		account.credit(BigDecimal.valueOf(1000), "KRW");

		assertThat(account.getBalance()).isEqualByComparingTo("1000");
	}

	@Test
	void debit_잔액을_초과하면_예외() {
		Account account = newAccount();
		account.credit(BigDecimal.valueOf(500), "KRW");

		assertThatThrownBy(() -> account.debit(BigDecimal.valueOf(1000), "KRW"))
				.isInstanceOf(InsufficientBalanceException.class);
	}

	@Test
	void debit_통화가_다르면_예외() {
		Account account = newAccount();
		account.credit(BigDecimal.valueOf(1000), "KRW");

		assertThatThrownBy(() -> account.debit(BigDecimal.valueOf(100), "USD"))
				.isInstanceOf(CurrencyMismatchException.class);
	}

	@Test
	void 활성상태가_아니면_예외() {
		Account account = newAccount();
		account.freeze();

		assertThatThrownBy(() -> account.credit(BigDecimal.valueOf(100), "KRW"))
				.isInstanceOf(AccountNotActiveException.class);
	}

	@Test
	void debit_성공시_잔액이_차감된다() {
		Account account = newAccount();
		account.credit(BigDecimal.valueOf(1000), "KRW");

		account.debit(BigDecimal.valueOf(300), "KRW");

		assertThat(account.getBalance()).isEqualByComparingTo("700");
	}
}
