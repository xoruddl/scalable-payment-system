package com.remittance.transfer.outbox;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.support.Timestamps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbox에 저장되고 Kafka로 발행되는 이벤트 본문. */
public record TransferEventPayload(
		UUID transferId,
		TransferStatus status,
		UUID fromAccountId,
		UUID toAccountId,
		/** 상대 은행으로 나가면 여기 값이 있다. account-service의 입금 단계가 이걸 보고 갈린다. */
		String toBankCode,
		String toAccountNumber,
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
				transfer.getToBankCode(),
				transfer.getToAccountNumber(),
				transfer.getAmount(),
				transfer.getCurrency(),
				transfer.getFailureReason(),
				Timestamps.now()
		);
	}
}
