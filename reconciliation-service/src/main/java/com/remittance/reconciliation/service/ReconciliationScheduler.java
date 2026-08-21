package com.remittance.reconciliation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대사를 주기적으로 돌린다.
 *
 * <p>수동 실행 엔드포인트를 따로 둔 이유는 <b>테스트와 사고 대응</b> 때문이다.
 * 다음 주기를 기다리지 않고 지금 상태를 확인할 수 있어야 한다.
 */
@Component
@ConditionalOnProperty(name = "reconciliation.scheduler.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationScheduler {

	private final ReconciliationService reconciliationService;

	@Scheduled(fixedDelayString = "${reconciliation.scheduler.interval-ms:60000}")
	public void reconcile() {
		reconciliationService.runOnce();
	}
}
