package com.remittance.transfer.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 이 서비스가 <b>소비하는</b> Saga 이벤트의 토픽명과 본문.
 * 발행하는 쪽은 {@link com.remittance.transfer.outbox.TransferEventType}에 있다.
 *
 * <p>서비스 간 공유 모듈을 두지 않기로 했으므로 account/ledger 서비스도 같은 계약을 각자 정의한다.
 * 필드 이름이 곧 계약이니 바꿀 때는 세 곳을 함께 확인해야 한다.
 *
 * <p>본문에 {@code @JsonIgnoreProperties(ignoreUnknown = true)}를 붙여, 발행하는 쪽이 필드를
 * 추가해도 이 서비스가 깨지지 않게 한다. 그래야 서비스를 각자 배포할 수 있다.
 */
public final class TransferEvents {

	/** Account가 발행: 출금 완료 */
	public static final String DEBITED = "transfer.debited";
	/** Account가 발행: 입금 완료 */
	public static final String CREDITED = "transfer.credited";
	/** Ledger가 발행: 원장 기록 완료 → 이 시점에야 송금이 COMPLETED가 된다 */
	public static final String LEDGER_RECORDED = "transfer.ledger-recorded";

	private TransferEvents() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Debited(
			UUID transferId,
			BigDecimal fromBalanceAfter,
			Instant occurredAt
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Credited(
			UUID transferId,
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
