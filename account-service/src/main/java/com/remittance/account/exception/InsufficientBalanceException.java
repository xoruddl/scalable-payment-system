package com.remittance.account.exception;

import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

	public InsufficientBalanceException(UUID accountId) {
		super("잔액이 부족합니다: " + accountId);
	}
}
