package com.remittance.ledger.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

	public TransactionNotFoundException(UUID transactionId) {
		super("거래 내역을 찾을 수 없습니다: " + transactionId);
	}
}
