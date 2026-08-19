package com.remittance.account.web;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import com.remittance.account.exception.LockAcquisitionException;
import com.remittance.account.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(InsufficientBalanceException.class)
	public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException e) {
		return error(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", e.getMessage());
	}

	@ExceptionHandler(AccountNotActiveException.class)
	public ResponseEntity<ErrorResponse> handleNotActive(AccountNotActiveException e) {
		return error(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", e.getMessage());
	}

	@ExceptionHandler(CurrencyMismatchException.class)
	public ResponseEntity<ErrorResponse> handleCurrencyMismatch(CurrencyMismatchException e) {
		return error(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH", e.getMessage());
	}

	@ExceptionHandler(ConcurrentUpdateException.class)
	public ResponseEntity<ErrorResponse> handleConcurrentUpdate(ConcurrentUpdateException e) {
		return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", e.getMessage());
	}

	@ExceptionHandler(LockAcquisitionException.class)
	public ResponseEntity<ErrorResponse> handleLockAcquisition(LockAcquisitionException e) {
		return error(HttpStatus.CONFLICT, "LOCK_TIMEOUT", e.getMessage());
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
