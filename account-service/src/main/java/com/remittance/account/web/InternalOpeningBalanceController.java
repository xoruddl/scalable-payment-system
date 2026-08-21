package com.remittance.account.web;

import com.remittance.account.service.OpeningBalanceService;
import com.remittance.account.web.dto.CarryOpeningBalanceRequest;
import com.remittance.account.web.dto.OpeningBalanceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 원장 도입 이전 잔액을 이월하는 <b>일회성 운영 작업</b>용 API. Gateway로 노출되지 않는다.
 *
 * <p>{@link InternalAccountController}(Transfer가 Saga에서 부르는 문)와 일부러 갈라놓았다 —
 * 이건 흐름의 일부가 아니라 <b>사람이 한 번 돌리는 이행 작업</b>이라, 누가 왜 부르는지가 다르다.
 *
 * <p><b>대사 서비스가 이걸 자동으로 부르지 않는다.</b> 그러면 결국 대사가 남의 데이터를 고치는 셈이
 * 되어, 원인을 모르는 채 증상만 지우게 된다. 대사는 차이를 보고할 뿐이고, 그 보고를 보고
 * 이월할지 판단해 부르는 건 운영자다.
 */
@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class InternalOpeningBalanceController {

	private final OpeningBalanceService openingBalanceService;

	@PostMapping("/{accountId}/opening-balance")
	public OpeningBalanceResponse carryForward(@PathVariable UUID accountId,
			@Valid @RequestBody CarryOpeningBalanceRequest request) {
		return OpeningBalanceResponse.from(openingBalanceService.carryForward(
				accountId, request.observedBalance(), request.ledgerBalance()));
	}
}
