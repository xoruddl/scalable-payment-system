package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.lock.DistributedLock;
import com.remittance.account.repository.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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

	@Mock
	private DistributedLock distributedLock;

	@Mock
	private BalanceMutationExecutor mutationExecutor;

	/**
	 * 메트릭은 목이 아니라 진짜 레지스트리를 쓴다. 목으로 두면 "increment()가 불렸다"까지만
	 * 확인하게 되는데, 정작 알고 싶은 건 <b>어떤 태그로 몇이 찍혔나</b>이다.
	 */
	@Spy
	private MeterRegistry meterRegistry = new SimpleMeterRegistry();

	@InjectMocks
	private AccountService accountService;

	private double conflictCount(String outcome) {
		return meterRegistry.find("remittance.optimistic.lock.conflict")
				.tag("entity", "account").tag("outcome", outcome)
				.counters().stream().mapToDouble(counter -> counter.count()).sum();
	}

	/** 락 자체는 여기서 검증 대상이 아니므로, 그냥 통과시켜 원래 동작을 실행하게 한다. */
	@SuppressWarnings("unchecked")
	private void passThroughLock() {
		given(distributedLock.executeWithLock(any(), any(), any(), any()))
				.willAnswer(invocation -> ((Supplier<Account>) invocation.getArgument(3)).get());
	}

	@Test
	void 계좌가_없으면_예외() {
		UUID accountId = UUID.randomUUID();
		given(accountRepository.findByAccountId(accountId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccount(accountId))
				.isInstanceOf(AccountNotFoundException.class);
	}

	@Test
	void 낙관적_락_충돌시_재조회_후_재시도한다() {
		passThroughLock();
		UUID accountId = UUID.randomUUID();
		Account account = Account.builder().ownerId(UUID.randomUUID()).currency("KRW")
				.accountType(AccountType.PERSONAL).build();
		account.credit(BigDecimal.valueOf(1000), "KRW");

		given(mutationExecutor.execute(any(), any(), any(), any(), any()))
				.willThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId))
				.willThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId))
				.willReturn(account);

		Account result = accountService.credit(accountId, BigDecimal.valueOf(100), "KRW");

		assertThat(result).isSameAs(account);
		verify(mutationExecutor, times(3)).execute(any(), any(), any(), any(), any());
		// 충돌이 두 번 났고 둘 다 재시도로 넘겼다. 이 값이 0에서 뜨기 시작하면
		// 분산 락이 막지 못한 경합이 실제로 있다는 뜻이다 (Phase 5 Step 2).
		assertThat(conflictCount("retried")).isEqualTo(2);
		assertThat(conflictCount("exhausted")).isZero();
	}

	@Test
	void 재시도를_모두_소진하면_예외() {
		passThroughLock();
		UUID accountId = UUID.randomUUID();
		given(mutationExecutor.execute(any(), any(), any(), any(), any()))
				.willThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId));

		assertThatThrownBy(() -> accountService.credit(accountId, BigDecimal.valueOf(100), "KRW"))
				.isInstanceOf(ConcurrentUpdateException.class);
		// 마지막 한 번은 성격이 다르다 — 재시도로 넘긴 게 아니라 요청이 실패한 것이다.
		assertThat(conflictCount("exhausted")).isEqualTo(1);
		assertThat(conflictCount("retried")).isEqualTo(4);
	}
}
