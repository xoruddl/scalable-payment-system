package com.remittance.account.web;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.service.AccountService;
import com.remittance.account.web.dto.AdjustBalanceRequest;
import com.remittance.account.web.dto.BalanceResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Gateway로 노출되지 않는 서비스간 전용 API. Transfer Service가 출금/입금 시 호출한다.
 */
@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class InternalAccountController {

	private final AccountService accountService;

	@PostMapping("/{accountId}/debit")
	public BalanceResponse debit(@PathVariable UUID accountId, @Valid @RequestBody AdjustBalanceRequest request) {
		AccountBalance balance = accountService.debit(accountId, request.amount(), request.currency());
		return BalanceResponse.from(balance);
	}

	@PostMapping("/{accountId}/credit")
	public BalanceResponse credit(@PathVariable UUID accountId, @Valid @RequestBody AdjustBalanceRequest request) {
		AccountBalance balance = accountService.credit(accountId, request.amount(), request.currency());
		return BalanceResponse.from(balance);
	}
}
