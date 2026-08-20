package com.remittance.account.web;

import com.remittance.account.service.ReconciliationQueryService;
import com.remittance.account.web.dto.AccountBalancePage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대사 서비스가 읽어가는 전용 API. Gateway로 노출되지 않는다.
 *
 * <p><b>읽기만 있다.</b> 대사는 찾아서 알리는 역할이고, 고치는 건 데이터 주인의 몫이다 —
 * 남의 서비스가 이 계좌를 마음대로 손볼 수 있는 문을 만들면 지금까지 지켜온 경계가 무너진다.
 */
@RestController
@RequestMapping("/internal/reconciliation")
@RequiredArgsConstructor
public class InternalReconciliationController {

	private static final int DEFAULT_PAGE_SIZE = 200;
	private static final int MAX_PAGE_SIZE = 1000;

	private final ReconciliationQueryService reconciliationQueryService;

	@GetMapping("/balances")
	public AccountBalancePage balances(
			@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
		return reconciliationQueryService.balances(cursor, Math.min(size, MAX_PAGE_SIZE));
	}
}
