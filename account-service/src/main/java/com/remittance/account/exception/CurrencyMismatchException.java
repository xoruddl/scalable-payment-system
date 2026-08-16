package com.remittance.account.exception;

import java.util.UUID;

public class CurrencyMismatchException extends RuntimeException {

	public CurrencyMismatchException(UUID accountId, String accountCurrency, String requestCurrency) {
		super("통화가 일치하지 않습니다 (accountId=" + accountId + ", accountCurrency=" + accountCurrency
				+ ", requestCurrency=" + requestCurrency + ")");
	}
}
