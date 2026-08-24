package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalance;
import com.remittance.account.exception.StaleBalanceSnapshotException;
import com.remittance.account.exception.UnpublishedJournalException;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.outbox.BalanceJournal;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 개시 잔액 이월 한 건을 <b>하나의 트랜잭션</b>으로 처리한다 — 이월 표시와 분개 기록이 함께 커밋되어야 한다.
 *
 * <p>표시만 남고 분개가 없으면 그 계좌는 영원히 어긋난 채 다시 이월할 수도 없게 되고,
 * 분개만 남고 표시가 없으면 다음 실행이 또 심어 원장이 잔액보다 커진다.
 *
 * <p>{@link BalanceMutationExecutor}와 짝이지만 <b>잔액을 바꾸지 않는다</b>는 점이 다르다.
 */
@Component
@RequiredArgsConstructor
public class OpeningBalanceExecutor {

	private final AccountRepository accountRepository;
	private final BalanceShards balanceShards;
	private final OutboxEventRepository outboxEventRepository;
	private final BalanceJournal balanceJournal;

	@Transactional
	public OpeningBalanceResult execute(UUID accountId, BigDecimal observedBalance, BigDecimal ledgerBalance) {
		// 이월은 잔액을 바꾸지 않지만 <b>합을 봐야</b> 한다 — 원장과의 차이를 재는 게 일이다.
		AccountBalance balance = balanceShards.whole(accountId);
		Account account = balance.account();

		if (account.isOpeningBalanceCarried()) {
			// 두 번 심으면 그 액수만큼 원장이 잔액보다 커진다. 조용히 넘어가는 게 맞다 —
			// 이월은 여러 번 불려도 결과가 같아야 재시도할 수 있다.
			return OpeningBalanceResult.alreadyCarried(accountId);
		}
		if (outboxEventRepository.existsByAggregateIdAndPublishedAtIsNull(accountId)) {
			throw new UnpublishedJournalException(accountId);
		}
		if (balance.total().compareTo(observedBalance) != 0) {
			throw new StaleBalanceSnapshotException(accountId, observedBalance, balance.total());
		}

		BigDecimal gap = balance.total().subtract(ledgerBalance);
		account.markOpeningBalanceCarried();
		accountRepository.saveAndFlush(account);

		if (gap.signum() == 0) {
			// 이미 원장과 맞는 계좌다. 심을 게 없지만 표시는 남겨 다시 묻지 않게 한다.
			return OpeningBalanceResult.alreadyConsistent(accountId);
		}

		balanceJournal.record(balance,
				AccountEvents.BalanceChangeReason.OPENING_BALANCE,
				gap.signum() > 0
						? AccountEvents.TransactionDirection.CREDIT
						: AccountEvents.TransactionDirection.DEBIT,
				gap.abs(),
				// 어느 송금 때문도 아니다. 원장이 없던 시절 전체를 뭉뚱그린 한 줄이다.
				null);
		return OpeningBalanceResult.seeded(accountId, gap);
	}
}
