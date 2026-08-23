package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.lock.AccountLockPolicy;
import com.remittance.account.lock.DistributedLock;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.repository.AccountRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class AccountService {

	private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

	/** 잔액 변경 한 건이 걸리는 시간보다 넉넉해야 한다 (자동 갱신이 없으므로). */
	private static final Duration LOCK_TTL = Duration.ofSeconds(3);
	/** 같은 계좌에 요청이 몰렸을 때 기다려보는 시간. */
	private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(3);

	private final AccountRepository accountRepository;
	private final DistributedLock distributedLock;
	private final AccountLockPolicy lockPolicy;
	private final BalanceMutationExecutor mutationExecutor;
	private final MeterRegistry meterRegistry;

	@Transactional
	public Account createAccount(UUID ownerId, String currency, AccountType accountType) {
		Account account = Account.builder()
				.ownerId(ownerId)
				.currency(currency)
				.accountType(accountType != null ? accountType : AccountType.PERSONAL)
				.build();
		return accountRepository.save(account);
	}

	@Transactional(readOnly = true)
	public Account getAccount(UUID accountId) {
		return findByAccountId(accountId);
	}

	@Transactional(readOnly = true)
	public Account getBalance(UUID accountId) {
		return findByAccountId(accountId);
	}

	/**
	 * 잔액 변경은 두 겹으로 보호한다.
	 * <ol>
	 *   <li><b>분산 락</b>(Redis): 계좌 단위로 정상 경로를 직렬화해 애초에 충돌이 생기지 않게 한다.</li>
	 *   <li><b>낙관적 락</b>(@Version): 락이 TTL로 풀렸거나 Redis 장애로 우회되는 등
	 *       분산 락이 뚫린 경우를 잡는 최후 안전망. 그래서 재시도 로직을 그대로 남겨둔다.</li>
	 * </ol>
	 * 락은 변경하는 계좌 하나에만 건다. 범위를 넓히면 데드락과 처리량 저하로 이어진다.
	 *
	 * <p>첫 겹은 {@link AccountLockPolicy}로 끌 수 있다 — 분산 락이 <b>정말로 도움이 되는지</b>를
	 * 숫자로 확인하기 위한 스위치다(Phase 6 Step 1). <b>둘째 겹은 못 끈다.</b>
	 * 낙관적 락은 선택이 아니라 마지막 방어선이다.
	 */
	public Account debit(UUID accountId, BigDecimal amount, String currency) {
		return guarded(accountId, () -> mutationExecutor.execute(accountId,
				account -> account.debit(amount, currency),
				AccountEvents.BalanceChangeReason.WITHDRAWAL,
				AccountEvents.TransactionDirection.DEBIT, amount));
	}

	public Account credit(UUID accountId, BigDecimal amount, String currency) {
		return guarded(accountId, () -> mutationExecutor.execute(accountId,
				account -> account.credit(amount, currency),
				AccountEvents.BalanceChangeReason.DEPOSIT,
				AccountEvents.TransactionDirection.CREDIT, amount));
	}

	/**
	 * 잔액을 바꾸는 <b>모든</b> 경로가 거쳐야 하는 동시성 방어. REST 진입점(debit/credit)뿐 아니라
	 * Kafka 컨슈머로 들어오는 Saga 단계도 이 메서드를 통해 실행한다 — 두 경로가 같은 계좌를
	 * 동시에 건드릴 수 있으므로, 방어가 한쪽에만 있으면 없는 것과 같다.
	 */
	public <T> T guarded(UUID accountId, Supplier<T> action) {
		if (!lockPolicy.usesDistributedLock()) {
			// 낙관적 락만으로 간다 (Phase 6 Step 1의 비교 실험). 경합 구간이 UPDATE~커밋으로
			// 짧아지는 대신, 충돌하면 트랜잭션을 처음부터 다시 한다.
			return withOptimisticRetry(accountId, action);
		}
		return withAccountLock(accountId, () -> withOptimisticRetry(accountId, action));
	}

	private <T> T withAccountLock(UUID accountId, Supplier<T> action) {
		return distributedLock.executeWithLock("lock:account:" + accountId, LOCK_TTL, LOCK_WAIT_TIMEOUT, action);
	}

	/**
	 * 충돌을 <b>센다</b> (Phase 5 Step 2). 이 값이 0에서 뜨기 시작하면 분산 락이
	 * 막지 못한 경합이 실제로 있다는 뜻이다 — 락은 이 서비스 안에서만 유효하고,
	 * 낙관적 락은 그 바깥까지 막는 최후 안전망이라 <b>둘의 차이가 여기 드러난다.</b>
	 *
	 * <p>{@code outcome=retried}는 다시 읽어 넘긴 것이고, {@code exhausted}는 끝내 포기한 것이다.
	 * retried가 늘어나는 건 견딜 만하지만 exhausted는 요청이 실패했다는 뜻이라 성격이 다르다.
	 */
	private <T> T withOptimisticRetry(UUID accountId, Supplier<T> action) {
		for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
			try {
				return action.get();
			} catch (ObjectOptimisticLockingFailureException e) {
				if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
					conflicts("exhausted").increment();
					throw new ConcurrentUpdateException(accountId);
				}
				conflicts("retried").increment();
			}
		}
		throw new ConcurrentUpdateException(accountId);
	}


	/**
	 * 충돌이 한 번도 없어도 <b>0으로 보이게</b> 미리 만들어 둔다 (Phase 5 Step 2).
	 *
	 * <p>카운터는 처음 증가할 때 생긴다. 그대로 두면 충돌이 없는 동안 시계열 자체가 없어서
	 * 화면에서 <b>"충돌 0건"과 "수집이 안 되고 있다"가 똑같이 빈 칸</b>으로 보인다.
	 * 정작 이 지표는 평소에 0인 게 정상이라, 0을 그릴 수 있어야 값어치가 있다.
	 */
	@PostConstruct
	void 충돌_카운터를_미리_만든다() {
		conflicts("retried");
		conflicts("exhausted");
	}
	private Counter conflicts(String outcome) {
		return Counter.builder("remittance.optimistic.lock.conflict")
				.description("낙관적 락 충돌 횟수")
				.tag("entity", "account")
				.tag("outcome", outcome)
				.register(meterRegistry);
	}

	private Account findByAccountId(UUID accountId) {
		return accountRepository.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
	}
}
