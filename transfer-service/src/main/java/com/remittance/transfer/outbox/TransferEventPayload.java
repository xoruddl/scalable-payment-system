package com.remittance.transfer.outbox;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbox에 저장되고 Kafka로 발행되는 이벤트 본문. */
public record TransferEventPayload(
		UUID transferId,
		TransferStatus status,
		UUID fromAccountId,
		UUID toAccountId,
		BigDecimal amount,
		String currency,
		String failureReason,
		Instant occurredAt
) {
	public static TransferEventPayload from(Transfer transfer) {
		return new TransferEventPayload(
				transfer.getTransferId(),
				transfer.getStatus(),
				transfer.getFromAccountId(),
				transfer.getToAccountId(),
				transfer.getAmount(),
				transfer.getCurrency(),
				transfer.getFailureReason(),
				Instant.now()
		);
	}
}
