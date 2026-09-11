package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalance;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 계좌를 만들고, 조회하고, 입출금 API로 들어온 잔액 변경을 처리한다.
 *
 * 동시성 방어는 여기 없다 — {@link BalanceGuard}가 한다. 입출금도 Saga 단계도
 * 개시 잔액 이월도 그 문을 거치므로, 방어의 주인은 계좌 서비스가 아니라 잔액 변경 쪽이다.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

	private final AccountRepository accountRepository;
	private final BalanceGuard balanceGuard;
	private final BalanceMutationExecutor mutationExecutor;
	private final BalanceShards balanceShards;

	@Transactional
	public Account createAccount(UUID ownerId, String currency, AccountType accountType) {
		Account account = accountRepository.save(Account.builder()
				.ownerId(ownerId)
				.currency(currency)
				.accountType(accountType != null ? accountType : AccountType.PERSONAL)
				.build());
		// 조각 없는 계좌는 존재할 수 없다 — 잔액을 물으면 "없는 돈"과 "0원"을 구분하지 못한다.
		balanceShards.createFirstShard(account);
		return account;
	}

	@Transactional(readOnly = true)
	public Account getAccount(UUID accountId) {
		return findByAccountId(accountId);
	}

	@Transactional(readOnly = true)
	public AccountBalance getBalance(UUID accountId) {
		return balanceShards.whole(accountId);
	}

	public AccountBalance debit(UUID accountId, BigDecimal amount, String currency) {
		return balanceGuard.guarded(accountId, AccountEvents.TransactionDirection.DEBIT,
				shardNo -> mutationExecutor.execute(accountId, shardNo,
						balance -> balance.debit(amount, currency),
						AccountEvents.BalanceChangeReason.WITHDRAWAL,
						AccountEvents.TransactionDirection.DEBIT, amount));
	}

	public AccountBalance credit(UUID accountId, BigDecimal amount, String currency) {
		return balanceGuard.guarded(accountId, AccountEvents.TransactionDirection.CREDIT,
				shardNo -> mutationExecutor.execute(accountId, shardNo,
						balance -> balance.credit(amount, currency),
						AccountEvents.BalanceChangeReason.DEPOSIT,
						AccountEvents.TransactionDirection.CREDIT, amount));
	}

	private Account findByAccountId(UUID accountId) {
		return accountRepository.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
	}
}
