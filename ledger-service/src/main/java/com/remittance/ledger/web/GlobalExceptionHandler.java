package com.remittance.ledger.web;

import com.remittance.ledger.exception.InvalidCursorException;
import com.remittance.ledger.exception.TransactionNotFoundException;
import com.remittance.ledger.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(TransactionNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(TransactionNotFoundException e) {
		return error(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(InvalidCursorException.class)
	public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException e) {
		return error(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", e.getMessage());
	}

	@ExceptionHandler(WebExchangeBindException.class)
	public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException e) {
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
