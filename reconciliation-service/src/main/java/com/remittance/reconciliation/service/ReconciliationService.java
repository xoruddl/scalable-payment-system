package com.remittance.reconciliation.service;

import com.remittance.reconciliation.domain.ReconciliationRun;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 대사 한 회차를 돈다 — 회차를 열고, 검사를 돌리고, 결과를 닫는다.
 *
 * 고치지 않는다. 여기서 잔액을 맞추거나 송금을 종결시키면, 원인을 모르는 채 증상만
 * 지우는 꼴이 된다. 게다가 남의 서비스 데이터를 바꾸는 순간 서비스 경계가 무너진다.
 * 찾아서 남기는 데까지가 이 서비스의 일이다.
 *
 * 이 클래스에는 {@code @Transactional}이 없다 (2026-09-11)
 * 전에는 여기 붙어 있었고, 그래서 HTTP 검사가 트랜잭션 안에서 돌았다.
 * 지금은 세 역할이 갈려 있다.
 *
 *   ReconciliationChecks        HTTP로 찾는다      트랜잭션 없음 (오래 걸린다)
 *   ReconciliationRunRecorder   DB에 남긴다        트랜잭션 (짧다)
 *   ReconciliationService       순서를 정한다      트랜잭션 없음
 *
 * 나눈 이유와 대가는 {@link ReconciliationChecks} 주석에 있다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private final ReconciliationChecks checks;
	private final ReconciliationRunRecorder recorder;
	private final ReconciliationMetrics metrics;

	public ReconciliationRun runOnce() {
		ReconciliationRun opened = recorder.open(Instant.now());
		Long runId = opened.getId();
		try {
			ReconciliationChecks.Result result = checks.run(runId);
			ReconciliationRun run = recorder.complete(runId, result, Instant.now());
			if (result.hasFindings()) {
				log.warn("대사에서 어긋남을 찾았다 (runId={}, 계좌 {}건 확인, 발견 {}건)",
						runId, result.accountsChecked(), result.findingCount());
			}
			metrics.record(run, result.findings());
			return run;
		} catch (RuntimeException e) {
			log.error("대사가 끝까지 돌지 못했다 (runId={})", runId, e);
			ReconciliationRun run = recorder.fail(runId, shortReason(e), Instant.now());
			// 실패한 회차도 알린다. 이걸 빼면 배치가 계속 실패하는 동안 지표는 마지막 성공
			// 회차의 값에 멈춰 있어, 화면상으로는 아무 일도 없는 것처럼 보인다.
			metrics.record(run, List.of());
			return run;
		}
	}

	private String shortReason(RuntimeException e) {
		String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
		return reason.length() > 500 ? reason.substring(0, 500) : reason;
	}
}
