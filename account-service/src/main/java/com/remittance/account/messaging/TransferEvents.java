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
 */
public final class TransferEvents {

	/** Transfer가 발행: 송금이 접수됨 → Account가 출금한다 */
	public static final String REQUESTED = "transfer.requested";
	/** Account가 발행: 출금 완료 → Account가 이어서 입금한다 */
	public static final String DEBITED = "transfer.debited";
	/** Account가 발행: 입금 완료 → Ledger가 원장에 기록한다 */
	public static final String CREDITED = "transfer.credited";
	/** 실패 종결 (보상 흐름은 Step 4b에서 추가) */
	public static final String FAILED = "transfer.failed";

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

	/** {@link #FAILED} 본문. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Failed(
			UUID transferId,
			String failureReason
	) {
	}
}
