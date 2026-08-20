package com.remittance.account.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 송금 Saga에서 오가는 이벤트의 토픽명과 본문 스키마.
 *
 * <p>서비스 간 공유 모듈을 두지 않기로 했으므로, 같은 계약을 transfer/ledger 서비스도
 * 각자 정의한다. 필드 이름이 곧 계약이니 바꿀 때는 세 곳을 함께 확인해야 한다.
 *
 * <p>이벤트는 <b>다음 단계가 필요로 하는 값을 모두 실어 보낸다</b>(잔액 등).
 * 그래야 소비하는 쪽이 되묻기 위해 동기 호출을 하지 않는다 — 그러면 이벤트로 바꾼 의미가 없다.
 *
 * <p>모든 본문에 {@code @JsonIgnoreProperties(ignoreUnknown = true)}를 붙였다.
 * 발행하는 쪽이 필드를 추가해도 소비하는 쪽이 깨지지 않아야, 서비스를 따로 배포할 수 있다.
 * (실제로 {@code transfer.requested}에는 이 서비스가 쓰지 않는 필드가 여럿 들어 있다.)
 *
 * <p>정상 흐름과 보상 흐름은 이렇게 갈린다 (Step 4b).
 * <pre>
 *   requested ─▶ 출금 성공 ─▶ debited ─▶ 입금 성공 ─▶ credited          (정상)
 *   requested ─▶ 출금 실패 ─▶ debit-failed                              (움직인 돈 없음, 종결)
 *   debited   ─▶ 입금 실패 ─▶ credit-failed ─▶ 환불 ─▶ debit-reversed   (보상 후 종결)
 * </pre>
 * 송금을 최종적으로 FAILED로 찍고 {@code transfer.failed}를 발행하는 건 Transfer Service다.
 * 이 서비스는 <b>계좌에 무슨 일이 있었는지</b>만 알린다 — 송금의 상태는 송금의 주인이 정한다.
 */
public final class TransferEvents {

	/** Transfer가 발행: 송금이 접수됨 → Account가 출금한다 */
	public static final String REQUESTED = "transfer.requested";
	/** Account가 발행: 출금 완료 → Account가 이어서 입금한다 */
	public static final String DEBITED = "transfer.debited";
	/** Account가 발행: 입금 완료 → Ledger가 원장에 기록한다 */
	public static final String CREDITED = "transfer.credited";

	/** Account가 발행: 출금 자체가 실패 → 되돌릴 게 없으므로 송금만 실패로 종결하면 된다 */
	public static final String DEBIT_FAILED = "transfer.debit-failed";
	/**
	 * Account가 발행하고 <b>Account가 다시 소비</b>: 입금이 실패 → 이미 나간 출금을 되돌려야 한다.
	 *
	 * <p>같은 서비스 안에서 처리할 수 있는데도 굳이 이벤트로 한 바퀴 도는 이유는 <b>재시도</b> 때문이다.
	 * 요청 스레드에서 곧바로 환불하면 그 환불이 실패했을 때 아무도 다시 해주지 않는다
	 * (Step 0 재현 테스트 #4가 바로 그 문제였다). 브로커에 남겨두면 실패해도 다시 배달된다.
	 */
	public static final String CREDIT_FAILED = "transfer.credit-failed";
	/** Account가 발행: 출금을 되돌렸음 → Transfer가 송금을 FAILED로 종결한다 */
	public static final String DEBIT_REVERSED = "transfer.debit-reversed";

	private TransferEvents() {
	}

	/** {@link #REQUESTED} 본문. Transfer Service가 만든 payload와 필드가 맞아야 한다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Requested(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency
	) {
	}

	/** {@link #DEBITED} 본문. 출금 후 잔액을 함께 실어 다음 단계가 그대로 쓸 수 있게 한다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Debited(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			BigDecimal fromBalanceAfter,
			Instant occurredAt
	) {
	}

	/** {@link #CREDITED} 본문. 원장 기록에 필요한 양쪽 잔액이 모두 담긴다. */
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

	/** {@link #DEBIT_FAILED} 본문. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DebitFailed(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			String failureReason,
			Instant occurredAt
	) {
	}

	/**
	 * {@link #CREDIT_FAILED} 본문.
	 *
	 * <p>되돌릴 금액과 계좌를 본문에 담는다. 보상하는 쪽이 "얼마를 누구에게 돌려줘야 하는지"를
	 * DB에 되물으면, 그 사이 상태가 바뀌었을 때 엉뚱한 금액을 되돌릴 수 있다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreditFailed(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			String failureReason,
			Instant occurredAt
	) {
	}

	/** {@link #DEBIT_REVERSED} 본문. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DebitReversed(
			UUID transferId,
			UUID fromAccountId,
			BigDecimal amount,
			String currency,
			BigDecimal fromBalanceAfter,
			String failureReason,
			Instant occurredAt
	) {
	}
}
