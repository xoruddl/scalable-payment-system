package com.remittance.reconciliation.web;

import com.remittance.reconciliation.domain.ReconciliationRun;
import com.remittance.reconciliation.repository.ReconciliationFindingRepository;
import com.remittance.reconciliation.repository.ReconciliationRunRepository;
import com.remittance.reconciliation.service.ReconciliationService;
import com.remittance.reconciliation.web.dto.FindingResponse;
import com.remittance.reconciliation.web.dto.RunResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 대사 결과를 사람이 들여다보는 창구. */
@RestController
@RequestMapping("/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

	private static final int DEFAULT_FINDING_LIMIT = 100;

	private final ReconciliationService reconciliationService;
	private final ReconciliationRunRepository runRepository;
	private final ReconciliationFindingRepository findingRepository;

	/** 다음 주기를 기다리지 않고 지금 돌린다. 사고가 났을 때 현재 상태를 바로 봐야 한다. */
	@PostMapping("/runs")
	public RunResponse runNow() {
		return toResponse(reconciliationService.runOnce());
	}

	@GetMapping("/runs/latest")
	public ResponseEntity<RunResponse> latest() {
		return runRepository.findFirstByOrderByIdDesc()
				.map(run -> ResponseEntity.ok(toResponse(run)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@GetMapping("/findings")
	public List<FindingResponse> findings(
			@RequestParam(defaultValue = "" + DEFAULT_FINDING_LIMIT) int limit) {
		return findingRepository.findByOrderByIdDesc(Limit.of(limit)).stream()
				.map(FindingResponse::from).toList();
	}

	private RunResponse toResponse(ReconciliationRun run) {
		return RunResponse.of(run, findingRepository.findByRunIdOrderByIdAsc(run.getId()).stream()
				.map(FindingResponse::from).toList());
	}
}
