package com.remittance.account.exception;

import java.util.UUID;

public class ConcurrentUpdateException extends RuntimeException {

	public ConcurrentUpdateException(UUID accountId) {
		super("동시 갱신 충돌로 처리에 실패했습니다: " + accountId);
	}
}
