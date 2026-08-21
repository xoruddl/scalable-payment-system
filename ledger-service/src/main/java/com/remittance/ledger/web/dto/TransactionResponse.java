package com.remittance.ledger.web.dto;

import com.remittance.ledger.domain.Transaction;
import com.remittance.ledger.domain.BalanceChangeReason;
import com.remittance.ledger.domain.TransactionDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
		UUID transactionId,
		UUID transferId,
		UUID accountId,
		BalanceChangeReason reason,
		TransactionDirection direction,
		BigDecimal amount,
		BigDecimal balanceAfter,
		Instant recordedAt
) {
	public static TransactionResponse from(Transaction transaction) {
		return new TransactionResponse(
				transaction.getTransactionId(),
				transaction.getTransferId(),
				transaction.getAccountId(),
				transaction.getReason(),
				transaction.getDirection(),
				transaction.getAmount(),
				transaction.getBalanceAfter(),
				transaction.getRecordedAt()
		);
	}
}
