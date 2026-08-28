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

	/** Account가 발행: 출금 자체가 실패 → 움직인 돈이 없으므로 곧바로 FAILED */
	public static final String DEBIT_FAILED = "transfer.debit-failed";
	/** Account가 발행: 입금 실패 → 환불이 진행 중이라는 뜻. 아직 종결이 아니다(COMPENSATING) */
	public static final String CREDIT_FAILED = "transfer.credit-failed";

	/**
	 * 상대 은행에 보냈는데 <b>답이 없다</b> — 들어갔는지 모른다 (Phase 6.5).
	 * 실패가 아니라 <b>제3의 상태</b>다. 실패로 처리하면 이미 나간 돈을 환불해 이중 지급이 된다.
	 */
	public static final String CREDIT_UNKNOWN = "transfer.credit-unknown";

	/** Account가 발행: 출금을 되돌렸음 → 이제 FAILED로 종결한다 */
	public static final String DEBIT_REVERSED = "transfer.debit-reversed";

	private TransferEvents() {
	}

	/** {@link #CREDIT_UNKNOWN} 본문. 상태만 옮기면 되므로 최소한만 받는다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreditUnknown(
			UUID transferId,
			String toBankCode,
			Instant occurredAt
	) {
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

	/**
	 * 실패 계열 이벤트는 이 서비스 입장에서 필요한 게 같다 — 어느 송금이, 왜 실패했는가.
	 * Account가 함께 실어 보내는 계좌·금액은 Account가 보상할 때 쓰는 값이라 여기서는 읽지 않는다.
	 * ({@code @JsonIgnoreProperties} 덕분에 안 읽어도 깨지지 않는다.)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StepFailed(
			UUID transferId,
			String failureReason,
			Instant occurredAt
	) {
	}
}
