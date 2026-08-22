package com.remittance.transfer.exception;

/**
 * 같은 Idempotency-Key로 서로 다른 payload를 보낸 경우.
 * 클라이언트가 키를 재사용하고 있다는 뜻이므로 처리하지 않고 거절한다.
 */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException(String key) {
		super("동일한 Idempotency-Key로 다른 요청이 이미 처리되었습니다: " + key);
	}
}
