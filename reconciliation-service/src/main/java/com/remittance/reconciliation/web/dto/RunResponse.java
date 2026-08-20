package com.remittance.reconciliation.web.dto;

import com.remittance.reconciliation.domain.ReconciliationRun;

import java.time.Instant;
import java.util.List;

/**
 * @param failureReason 값이 있으면 이 회차는 <b>끝까지 돌지 못했다</b>. 발견 0건을 "깨끗하다"로
 *                      읽으면 안 된다는 표시다.
 */
public record RunResponse(
		Long runId,
		Instant startedAt,
		Instant finishedAt,
		int accountsChecked,
		int findingCount,
		String failureReason,
		List<FindingResponse> findings
) {
	public static RunResponse of(ReconciliationRun run, List<FindingResponse> findings) {
		return new RunResponse(run.getId(), run.getStartedAt(), run.getFinishedAt(),
				run.getAccountsChecked(), run.getFindingCount(), run.getFailureReason(), findings);
	}
}
