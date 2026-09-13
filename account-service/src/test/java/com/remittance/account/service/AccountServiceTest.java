package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalance;
import com.remittance.account.domain.AccountBalanceShard;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private BalanceGuard balanceGuard;

	@Mock
	private BalanceMutationExecutor mutationExecutor;

	@Mock
	private BalanceShards balanceShards;

	@InjectMocks
	private AccountService accountService;

	private AccountBalance 잔액() {
		Account account = Account.builder().ownerId(UUID.randomUUID()).currency("KRW")
				.accountType(AccountType.PERSONAL).build();
		return AccountBalance.whole(account, List.of(
				new AccountBalanceShard(account.getAccountId(), (short) 0, BigDecimal.valueOf(1000))));
	}

	@Test
	void 계좌가_없으면_예외() {
		UUID accountId = UUID.randomUUID();
		given(accountRepository.findByAccountId(accountId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccount(accountId))
				.isInstanceOf(AccountNotFoundException.class);
	}

	/**
	 * 방어를 건너뛰고 {@code mutationExecutor}를 직접 부르면 이 스텁이 걸리지 않아 깨진다.
	 * 잔액을 바꾸는 경로가 하나라도 문 밖으로 새면 방어는 없는 것과 같으므로,
	 * "락 안에서 무슨 일이 일어나나"가 아니라 "문을 지났나"를 잰다.
	 */
	@Test
	void 출금은_방향까지_맞춰_동시성_방어를_거친다() {
		UUID accountId = UUID.randomUUID();
		AccountBalance balance = 잔액();
		given(balanceGuard.<AccountBalance>guarded(eq(accountId),
				eq(AccountEvents.TransactionDirection.DEBIT), any())).willReturn(balance);

		assertThat(accountService.debit(accountId, BigDecimal.valueOf(100), "KRW")).isSameAs(balance);
		verifyNoInteractions(mutationExecutor);
	}

	/**
	 * 방향이 틀리면 조용히 망가진다 — 입금인데 DEBIT으로 들어가면 조각을 고르지 않고
	 * 전부 잠가서, 쪼갠 계좌가 다시 한 줄로 선다. 그래서 방향까지 못 박는다.
	 */
	@Test
	void 입금은_방향까지_맞춰_동시성_방어를_거친다() {
		UUID accountId = UUID.randomUUID();
		AccountBalance balance = 잔액();
		given(balanceGuard.<AccountBalance>guarded(eq(accountId),
				eq(AccountEvents.TransactionDirection.CREDIT), any())).willReturn(balance);

		assertThat(accountService.credit(accountId, BigDecimal.valueOf(100), "KRW")).isSameAs(balance);
		verifyNoInteractions(mutationExecutor);
	}
}
