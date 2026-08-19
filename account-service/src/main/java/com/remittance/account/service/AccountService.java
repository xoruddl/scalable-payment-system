package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.lock.DistributedLock;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
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
	 */
	public Account debit(UUID accountId, BigDecimal amount, String currency) {
		return withAccountLock(accountId,
				() -> withOptimisticRetry(accountId, account -> account.debit(amount, currency)));
	}

	public Account credit(UUID accountId, BigDecimal amount, String currency) {
		return withAccountLock(accountId,
				() -> withOptimisticRetry(accountId, account -> account.credit(amount, currency)));
	}

	private Account withAccountLock(UUID accountId, Supplier<Account> action) {
		return distributedLock.executeWithLock("lock:account:" + accountId, LOCK_TTL, LOCK_WAIT_TIMEOUT, action);
	}

	private Account withOptimisticRetry(UUID accountId, Consumer<Account> mutation) {
		for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
			try {
				return applyMutation(accountId, mutation);
			} catch (ObjectOptimisticLockingFailureException e) {
				if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
					throw new ConcurrentUpdateException(accountId);
				}
			}
		}
		throw new ConcurrentUpdateException(accountId);
	}

	private Account applyMutation(UUID accountId, Consumer<Account> mutation) {
		Account account = findByAccountId(accountId);
		mutation.accept(account);
		return accountRepository.saveAndFlush(account);
	}

	private Account findByAccountId(UUID accountId) {
		return accountRepository.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
	}
}
