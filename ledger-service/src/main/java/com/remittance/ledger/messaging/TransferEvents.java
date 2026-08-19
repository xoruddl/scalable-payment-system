package com.remittance.ledger.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 송금 Saga 중 이 서비스가 관여하는 이벤트.
 *
 * <p>서비스 간 공유 모듈을 두지 않기로 했으므로 account/transfer 서비스도 같은 계약을 각자 정의한다.
 * 필드 이름이 곧 계약이니 바꿀 때는 세 곳을 함께 확인해야 한다.
 */
public final class TransferEvents {

	/** Account가 발행: 입금까지 끝남 → 원장에 남길 차례 */
	public static final String CREDITED = "transfer.credited";
	/** 이 서비스가 발행: 원장 기록 완료 → Transfer가 송금을 COMPLETED로 종결한다 */
	public static final String LEDGER_RECORDED = "transfer.ledger-recorded";

	private TransferEvents() {
	}

	/** 원장을 쓰는 데 필요한 값이 모두 담겨 있다 — 다른 서비스에 되묻지 않는다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Credited(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			BigDecimal fromBalanceAfter,
			BigDecimal toBalanceAfter,
			Instant occurredAt
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LedgerRecorded(
			UUID transferId,
			Instant occurredAt
	) {
	}
}
