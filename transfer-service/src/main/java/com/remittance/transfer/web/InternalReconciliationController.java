package com.remittance.transfer.web;

import com.remittance.transfer.service.ReconciliationQueryService;
import com.remittance.transfer.web.dto.StrandedKeyView;
import com.remittance.transfer.web.dto.UnsettledTransferView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 대사 서비스가 읽어가는 전용 API. Gateway로 노출되지 않는다.
 * <b>읽기만 있다</b> — 고치는 건 데이터 주인의 몫이고, 여기서는 사실만 알려준다.
 */
@RestController
@RequestMapping("/internal/reconciliation")
@RequiredArgsConstructor
public class InternalReconciliationController {

	private static final int MAX_LIMIT = 500;

	private final ReconciliationQueryService reconciliationQueryService;

	@GetMapping("/unsettled-transfers")
	public List<UnsettledTransferView> unsettledTransfers(
			@RequestParam long olderThanSeconds,
			@RequestParam(defaultValue = "" + MAX_LIMIT) int limit) {
		return reconciliationQueryService.unsettledTransfers(
				Duration.ofSeconds(olderThanSeconds), Math.min(limit, MAX_LIMIT));
	}

	@GetMapping("/stranded-keys")
	public List<StrandedKeyView> strandedKeys(
			@RequestParam long olderThanSeconds,
			@RequestParam(defaultValue = "" + MAX_LIMIT) int limit) {
		return reconciliationQueryService.strandedKeys(
				Duration.ofSeconds(olderThanSeconds), Math.min(limit, MAX_LIMIT));
	}
}
