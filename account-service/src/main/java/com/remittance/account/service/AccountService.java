package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class AccountService {

	private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

	private final AccountRepository accountRepository;

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
	 * 동시 잔액 갱신 충돌(@Version) 발생 시 재조회 후 재시도한다.
	 * 계좌별 동시성 직렬화(분산 락)는 Phase 2에서 도입한다.
	 */
	public Account debit(UUID accountId, BigDecimal amount, String currency) {
		return withOptimisticRetry(accountId, account -> account.debit(amount, currency));
	}

	public Account credit(UUID accountId, BigDecimal amount, String currency) {
		return withOptimisticRetry(accountId, account -> account.credit(amount, currency));
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
