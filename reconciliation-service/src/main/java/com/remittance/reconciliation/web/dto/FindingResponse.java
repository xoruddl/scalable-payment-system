package com.remittance.reconciliation.web.dto;

import com.remittance.reconciliation.domain.FindingType;
import com.remittance.reconciliation.domain.ReconciliationFinding;

import java.time.Instant;

public record FindingResponse(Long runId, FindingType type, String subject, String detail, Instant detectedAt) {

	public static FindingResponse from(ReconciliationFinding finding) {
		return new FindingResponse(finding.getRunId(), finding.getType(),
				finding.getSubject(), finding.getDetail(), finding.getDetectedAt());
	}
}
