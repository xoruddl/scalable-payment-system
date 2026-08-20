package com.remittance.ledger.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 송금 Saga 중 이 서비스가 <b>발행하는</b> 이벤트. 소비하는 쪽은 {@link AccountEvents}에 있다 —
 * Step 5a에서 원장의 입력이 {@code transfer.credited}에서 잔액 변경 이벤트로 바뀌었다.
 *
 * <p>서비스 간 공유 모듈을 두지 않기로 했으므로 account/transfer 서비스도 같은 계약을 각자 정의한다.
 * 필드 이름이 곧 계약이니 바꿀 때는 세 곳을 함께 확인해야 한다.
 */
public final class TransferEvents {

	/** 이 서비스가 발행: 원장 기록 완료 → Transfer가 송금을 COMPLETED로 종결한다 */
	public static final String LEDGER_RECORDED = "transfer.ledger-recorded";

	private TransferEvents() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LedgerRecorded(
			UUID transferId,
			Instant occurredAt
	) {
	}
}
