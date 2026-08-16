package com.remittance.ledger.web;

import com.remittance.ledger.service.TransactionService;
import com.remittance.ledger.web.dto.TransactionPageResponse;
import com.remittance.ledger.web.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TransactionController {

	private static final int MAX_PAGE_SIZE = 100;
	private static final int DEFAULT_PAGE_SIZE = 20;

	private final TransactionService transactionService;

	@GetMapping("/accounts/{accountId}/transactions")
	public Mono<TransactionPageResponse> getTransactions(
			@PathVariable UUID accountId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		return transactionService.getTransactionsByAccount(accountId, cursor, pageSize, from, to);
	}

	@GetMapping("/transactions/{transactionId}")
	public Mono<TransactionResponse> getTransaction(@PathVariable UUID transactionId) {
		return transactionService.getTransaction(transactionId);
	}
}
