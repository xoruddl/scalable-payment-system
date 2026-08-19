package com.remittance.transfer.web;

import com.remittance.transfer.exception.AccountNotActiveException;
import com.remittance.transfer.exception.AccountNotFoundException;
import com.remittance.transfer.exception.AccountServiceException;
import com.remittance.transfer.exception.CurrencyMismatchException;
import com.remittance.transfer.exception.IdempotencyConflictException;
import com.remittance.transfer.exception.IdempotencyInProgressException;
import com.remittance.transfer.exception.InsufficientBalanceException;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.exception.TransferNotFoundException;
import com.remittance.transfer.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(TransferNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(TransferNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(InsufficientBalanceException.class)
	public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException e) {
		return error(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", e.getMessage());
	}

	@ExceptionHandler(AccountNotActiveException.class)
	public ResponseEntity<ErrorResponse> handleAccountNotActive(AccountNotActiveException e) {
		return error(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", e.getMessage());
	}

	@ExceptionHandler(CurrencyMismatchException.class)
	public ResponseEntity<ErrorResponse> handleCurrencyMismatch(CurrencyMismatchException e) {
		return error(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH", e.getMessage());
	}

	@ExceptionHandler(InvalidTransferRequestException.class)
	public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidTransferRequestException e) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
	}

	@ExceptionHandler(AccountServiceException.class)
	public ResponseEntity<ErrorResponse> handleAccountServiceError(AccountServiceException e) {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_SERVICE_UNAVAILABLE", e.getMessage());
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
		// 422. RFC 9110에서 "Unprocessable Content"로 개명되어 Spring 7은 이 상수를 쓴다.
		return error(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
	}

	@ExceptionHandler(IdempotencyInProgressException.class)
	public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(IdempotencyInProgressException e) {
		return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS", e.getMessage());
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
		return error(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
				"필수 헤더가 누락되었습니다: " + e.getHeaderName());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
				.orElse("요청 값이 유효하지 않습니다.");
		return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ErrorResponse(code, message, UUID.randomUUID().toString()));
	}
}
