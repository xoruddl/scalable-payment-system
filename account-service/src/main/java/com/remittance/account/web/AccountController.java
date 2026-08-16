package com.remittance.account.web;

import com.remittance.account.domain.Account;
import com.remittance.account.service.AccountService;
import com.remittance.account.web.dto.AccountResponse;
import com.remittance.account.web.dto.BalanceResponse;
import com.remittance.account.web.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

	private final AccountService accountService;

	@PostMapping
	public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
		Account account = accountService.createAccount(request.ownerId(), request.currency(), request.accountType());
		AccountResponse response = AccountResponse.from(account);
		return ResponseEntity.created(URI.create("/accounts/" + response.accountId())).body(response);
	}

	@GetMapping("/{accountId}")
	public AccountResponse getAccount(@PathVariable UUID accountId) {
		return AccountResponse.from(accountService.getAccount(accountId));
	}

	@GetMapping("/{accountId}/balance")
	public BalanceResponse getBalance(@PathVariable UUID accountId) {
		return BalanceResponse.from(accountService.getBalance(accountId));
	}
}
