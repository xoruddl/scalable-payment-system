package com.remittance.reconciliation.service;

import com.remittance.reconciliation.domain.ReconciliationRun;
import com.remittance.reconciliation.repository.ReconciliationFindingRepository;
import com.remittance.reconciliation.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 회차를 DB에 남긴다. 트랜잭션은 여기에만 있고, 셋 다 짧다.
 *
 * 회차 하나가 트랜잭션 둘로 갈린다
 * {@link #open}이 먼저 커밋되고, HTTP 검사가 그 밖에서 돌고, {@link #complete}나
 * {@link #fail}이 뒤를 닫는다. 검사가 도는 동안 트랜잭션이 열려 있지 않게 하려는 것이고,
 * 그게 이 분리의 전부다 (이유는 {@link ReconciliationChecks} 참고).
 *
 * 덤으로 얻은 것 — 도는 중인 회차가 보인다
 * 전에는 한 트랜잭션이라 끝나기 전까지 행 자체가 없었다. 지금은 {@code finishedAt}이
 * 빈 행으로 남아 "지금 돌고 있다"가 보인다. 프로세스가 검사 도중 죽으면 그 행이
 * 안 닫힌 채 남는데, 그것도 알아야 할 사실이라 지우지 않는다.
 *
 * 대신 지표가 그 행을 마지막 회차로 세면 안 된다 — 끝나지 않은 회차를 "방금 끝났다"로
 * 읽으면 {@code last.run.age.seconds}가 막으려는 바로 그 거짓말이 된다.
 * 그래서 {@code ReconciliationMetrics}는 끝난 회차만 골라 읽는다.
 *
 * 별도 빈인 이유는 {@code @Transactional} 프록시 때문이다 —
 * 같은 클래스 안에서 부르면(self-invocation) 걸리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationRunRecorder {

	private final ReconciliationRunRepository runRepository;
	private final ReconciliationFindingRepository findingRepository;

	/** 회차를 연다. 커밋하고 나와야 검사가 트랜잭션 밖에서 돌 수 있다. */
	@Transactional
	public ReconciliationRun open(Instant startedAt) {
		return runRepository.save(new ReconciliationRun(startedAt));
	}

	/** 찾은 것을 저장하고 회차를 닫는다. 둘은 함께 커밋되어야 한다 — 세어둔 수와 실제 행이 맞아야 하므로. */
	@Transactional
	public ReconciliationRun complete(Long runId, ReconciliationChecks.Result result, Instant finishedAt) {
		findingRepository.saveAll(result.findings());
		ReconciliationRun run = load(runId);
		run.complete(result.accountsChecked(), result.findingCount(), finishedAt);
		return run;
	}

	/**
	 * 결과를 지우지 않고 실패로 남긴다. "깨끗했다"와 "못 읽었다"는 전혀 다른 얘기다.
	 *
	 * 찾다 만 것은 저장하지 않는다. 끝까지 못 돈 회차의 부분 목록을 남기면
	 * 보는 사람이 그걸 전부로 읽는다.
	 */
	@Transactional
	public ReconciliationRun fail(Long runId, String failureReason, Instant finishedAt) {
		ReconciliationRun run = load(runId);
		run.fail(failureReason, finishedAt);
		return run;
	}

	/**
	 * {@code getReferenceById}를 쓰지 않는다. 그건 프록시를 돌려주는데, 이 회차는 트랜잭션이
	 * 끝난 뒤 지표가 값을 읽어간다 — 초기화가 안 된 채로 나가면 거기서 터진다.
	 * 회차당 SELECT 한 번이고 60초에 한 번 도는 배치라 아낄 자리가 아니다.
	 */
	private ReconciliationRun load(Long runId) {
		return runRepository.findById(runId)
				.orElseThrow(() -> new IllegalStateException("방금 연 대사 회차가 없다 (runId=%d)".formatted(runId)));
	}
}
