package com.remittance.account.web;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import com.remittance.account.exception.LockAcquisitionException;
import com.remittance.account.exception.LockUnavailableException;
import com.remittance.account.exception.StaleBalanceSnapshotException;
import com.remittance.account.exception.UnpublishedJournalException;
import com.remittance.account.web.dto.ErrorResponse;
import org.springframework.dao.PessimisticLockingFailureException;
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

	/**
	 * Redis에 닿지 못해 분산 락을 못 잡았다 (Phase 6.7, D-006). 기본 전략(LAYERED)은 행 락만으로
	 * 진행하므로 여기까지 오지 않는다 — 폴백이 없는 전략(DISTRIBUTED)에서만 온다.
	 * 붐빈 것(409)이 아니라 잠시 쓸 수 없는 것이라 503이다.
	 */
	@ExceptionHandler(LockUnavailableException.class)
	public ResponseEntity<ErrorResponse> handleLockUnavailable(LockUnavailableException e) {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "LOCK_UNAVAILABLE", "잠시 후 다시 시도해 주세요.");
	}

	/**
	 * 행 락을 기다리다 시간을 넘긴 것 ({@code LAYERED} · {@code PESSIMISTIC} 전략). 위와 같은 일이라
	 * 같은 코드로 답한다 — 락을 Redis에서 잡느냐 DB에서 잡느냐는 호출자가 알 바 아니다.
	 * 교착으로 InnoDB가 이쪽을 죽인 경우도 여기로 온다.
	 */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handlePessimisticLock(PessimisticLockingFailureException e) {
		return error(HttpStatus.CONFLICT, "LOCK_TIMEOUT", "잔액이 다른 요청에 잠겨 있습니다. 잠시 후 다시 시도해 주세요.");
	}

	@ExceptionHandler(StaleBalanceSnapshotException.class)
	public ResponseEntity<ErrorResponse> handleStaleSnapshot(StaleBalanceSnapshotException e) {
		return error(HttpStatus.CONFLICT, "STALE_BALANCE_SNAPSHOT", e.getMessage());
	}

	@ExceptionHandler(UnpublishedJournalException.class)
	public ResponseEntity<ErrorResponse> handleUnpublishedJournal(UnpublishedJournalException e) {
		return error(HttpStatus.CONFLICT, "UNPUBLISHED_JOURNAL", e.getMessage());
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
