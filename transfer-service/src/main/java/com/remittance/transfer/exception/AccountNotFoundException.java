package com.remittance.transfer.exception;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(UUID accountId) {
		super("계좌를 찾을 수 없습니다: " + accountId);
	}
}
