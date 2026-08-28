package com.remittance.reconciliation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param pageSize          계좌를 몇 개씩 끊어 대조할지
 * @param unsettledAfter    송금이 이 시간을 넘겨 종결되지 않으면 흐름이 끊긴 것으로 본다.
 *                          정상 송금은 몇 초면 끝나지만, 컨슈머 재시도(최대 7초 백오프)와
 *                          DB 지연을 감안해 넉넉히 잡는다 — 짧으면 정상 건이 계속 잡혀 신호가 묻힌다.
 * @param keyStrandedAfter  멱등성 키가 이 시간을 넘겨 IN_PROGRESS면 접수가 죽은 것으로 본다.
 * @param externalCreditUnknownAfter 상대 은행에 보낸 뒤 이 시간을 넘겨도 결과를 모르면 사람이 봐야 한다.
 *                          확인 루프의 백오프가 최대 1분까지 늘어나므로 그보다 넉넉히 잡는다 —
 *                          짧으면 <b>아직 스스로 풀릴 건</b>까지 사람을 부르게 된다.
 * @param tolerance         이보다 작은 차이는 무시한다. 지금은 0 — 돈은 한 푼도 어긋나면 안 된다.
 */
@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(
		int pageSize,
		Duration unsettledAfter,
		Duration keyStrandedAfter,
		Duration externalCreditUnknownAfter,
		BigDecimal tolerance
) {
	public ReconciliationProperties {
		pageSize = pageSize > 0 ? pageSize : 200;
		unsettledAfter = unsettledAfter != null ? unsettledAfter : Duration.ofMinutes(2);
		keyStrandedAfter = keyStrandedAfter != null ? keyStrandedAfter : Duration.ofMinutes(10);
		externalCreditUnknownAfter = externalCreditUnknownAfter != null
				? externalCreditUnknownAfter : Duration.ofMinutes(5);
		tolerance = tolerance != null ? tolerance : BigDecimal.ZERO;
	}
}
