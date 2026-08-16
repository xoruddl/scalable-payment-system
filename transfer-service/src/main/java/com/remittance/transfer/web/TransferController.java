package com.remittance.transfer.web;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.service.TransferService;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import com.remittance.transfer.web.dto.TransferResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {

	private final TransferService transferService;

	@PostMapping
	public ResponseEntity<TransferResponse> requestTransfer(
			// TODO(Phase 2): 멱등성 키 저장/중복요청 검증. Phase 1에서는 계약만 맞추고 로직은 없음.
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateTransferRequest request) {
		Transfer transfer = transferService.requestTransfer(
				request.fromAccountId(), request.toAccountId(), request.amount(), request.currency(),
				request.memo());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(TransferResponse.from(transfer));
	}

	@GetMapping("/{transferId}")
	public TransferResponse getTransfer(@PathVariable UUID transferId) {
		return TransferResponse.from(transferService.getTransfer(transferId));
	}
}
