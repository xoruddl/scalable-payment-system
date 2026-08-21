package com.remittance.ledger.web;

import com.remittance.ledger.service.TransactionService;
import com.remittance.ledger.web.dto.LedgerBalanceView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * 대사 서비스가 읽어가는 전용 API. Gateway로 노출되지 않는다.
 *
 * <p>계좌 목록을 <b>받아서</b> 그 계좌들의 합만 돌려준다. 원장 전체를 페이지로 넘기지 않는 이유는,
 * 대사의 기준이 <b>계좌 쪽</b>이기 때문이다 — 계좌가 있는데 원장이 비어 있는 경우를 잡아야 하므로
 * 계좌 목록을 훑으며 원장에 물어보는 방향이 맞다.
 */
@RestController
@RequestMapping("/internal/reconciliation")
@RequiredArgsConstructor
public class InternalReconciliationController {

	private final TransactionService transactionService;

	@PostMapping("/balances")
	public Mono<List<LedgerBalanceView>> balances(@RequestBody List<UUID> accountIds) {
		return transactionService.balancesOf(accountIds);
	}
}
