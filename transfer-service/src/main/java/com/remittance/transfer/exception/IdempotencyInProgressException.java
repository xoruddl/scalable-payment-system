package com.remittance.transfer.exception;

/**
 * 같은 Idempotency-Key의 최초 요청이 아직 처리 중인 경우.
 * 클라이언트가 잠시 후 재시도하면 저장된 결과를 받게 된다.
 */
public class IdempotencyInProgressException extends RuntimeException {

	public IdempotencyInProgressException(String key) {
		super("동일한 Idempotency-Key의 요청이 아직 처리 중입니다: " + key);
	}
}
