package com.remittance.transfer.exception;

import java.util.UUID;

public class CurrencyMismatchException extends RuntimeException {

	public CurrencyMismatchException(UUID accountId) {
		super("통화가 일치하지 않습니다: " + accountId);
	}
}
