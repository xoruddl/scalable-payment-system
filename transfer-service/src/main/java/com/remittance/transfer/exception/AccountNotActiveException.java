package com.remittance.transfer.exception;

import java.util.UUID;

public class AccountNotActiveException extends RuntimeException {

	public AccountNotActiveException(UUID accountId) {
		super("계좌가 활성 상태가 아닙니다: " + accountId);
	}
}
