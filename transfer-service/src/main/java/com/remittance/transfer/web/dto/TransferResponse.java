package com.remittance.transfer.web.dto;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
		UUID transferId,
		TransferStatus status,
		UUID fromAccountId,
		UUID toAccountId,
		BigDecimal amount,
		String currency,
		String failureReason,
		Instant requestedAt,
		Instant completedAt
) {
	public static TransferResponse from(Transfer transfer) {
		return new TransferResponse(
				transfer.getTransferId(),
				transfer.getStatus(),
				transfer.getFromAccountId(),
				transfer.getToAccountId(),
				transfer.getAmount(),
				transfer.getCurrency(),
				transfer.getFailureReason(),
				transfer.getRequestedAt(),
				transfer.getCompletedAt()
		);
	}
}
