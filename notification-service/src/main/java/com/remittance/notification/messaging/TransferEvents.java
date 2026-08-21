package com.remittance.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 이 서비스가 <b>소비하는</b> 송금 종결 이벤트. Transfer Service가 발행한다.
 *
 * <p>중간 단계 이벤트(debited/credited/ledger-recorded)는 듣지 않는다. 알림은 <b>끝났을 때</b>
 * 한 번 가는 것이고, 진행 중인 단계마다 알리면 사용자에게는 소음일 뿐이다.
 *
 * <p>서비스 간 공유 모듈을 두지 않기로 했으므로 계약을 각자 정의한다.
 * 필드 이름이 곧 계약이니 바꿀 때는 발행하는 쪽과 함께 확인해야 한다.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}를 붙여 발행하는 쪽이 필드를 추가해도
 * 이 서비스가 깨지지 않게 한다. 그래야 서비스를 각자 배포할 수 있다.
 */
public final class TransferEvents {

	/** Transfer가 발행: 원장 기록까지 끝나 송금이 완료됐다 */
	public static final String COMPLETED = "transfer.completed";
	/** Transfer가 발행: 송금이 실패로 종결됐다 (보상까지 끝난 경우를 포함) */
	public static final String FAILED = "transfer.failed";

	private TransferEvents() {
	}

	/**
	 * 종결된 송금 한 건. <b>알림에 필요한 값이 모두 담겨 있어</b> 다른 서비스에 되묻지 않는다 —
	 * 알림을 보내려고 Transfer에 동기 호출을 걸면 그 서비스가 죽었을 때 알림도 함께 멈춘다.
	 *
	 * @param failureReason 실패한 경우에만 채워진다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TransferSettled(
			UUID transferId,
			String status,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			String failureReason,
			Instant occurredAt
	) {
	}
}
