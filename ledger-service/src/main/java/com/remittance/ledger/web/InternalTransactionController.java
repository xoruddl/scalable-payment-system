package com.remittance.ledger.web;

import com.remittance.ledger.service.TransactionService;
import com.remittance.ledger.web.dto.RecordTransactionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Gateway로 노출되지 않는 서비스간 전용 API. Transfer Service가 송금 완료 후 원장 기록에 사용한다.
 */
@RestController
@RequestMapping("/internal/transactions")
@RequiredArgsConstructor
public class InternalTransactionController {

	private final TransactionService transactionService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mono<Void> recordTransactions(@Valid @RequestBody List<RecordTransactionRequest> requests) {
		return transactionService.recordTransactions(requests);
	}
}
