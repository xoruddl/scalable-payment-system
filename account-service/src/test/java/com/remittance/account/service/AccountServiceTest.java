package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

	@Mock
	private AccountRepository accountRepository;

	@InjectMocks
	private AccountService accountService;

	@Test
	void 계좌가_없으면_예외() {
		UUID accountId = UUID.randomUUID();
		given(accountRepository.findByAccountId(accountId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccount(accountId))
				.isInstanceOf(AccountNotFoundException.class);
	}

	@Test
	void 낙관적_락_충돌시_재조회_후_재시도한다() {
		UUID accountId = UUID.randomUUID();
		Account account = Account.builder().ownerId(UUID.randomUUID()).currency("KRW")
				.accountType(AccountType.PERSONAL).build();
		account.credit(BigDecimal.valueOf(1000), "KRW");

		given(accountRepository.findByAccountId(accountId)).willReturn(Optional.of(account));
		given(accountRepository.saveAndFlush(any(Account.class)))
				.willThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId))
				.willThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId))
				.willReturn(account);

		Account result = accountService.credit(accountId, BigDecimal.valueOf(100), "KRW");

		assertThat(result).isSameAs(account);
		verify(accountRepository, times(3)).saveAndFlush(any(Account.class));
	}

	@Test
	void 재시도를_모두_소진하면_예외() {
		UUID accountId = UUID.randomUUID();
		Account account = Account.builder().ownerId(UUID.randomUUID()).currency("KRW")
				.accountType(AccountType.PERSONAL).build();

		given(accountRepository.findByAccountId(accountId)).willReturn(Optional.of(account));
		given(accountRepository.saveAndFlush(any(Account.class)))
				.willThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId));

		assertThatThrownBy(() -> accountService.credit(accountId, BigDecimal.valueOf(100), "KRW"))
				.isInstanceOf(ConcurrentUpdateException.class);
	}
}
