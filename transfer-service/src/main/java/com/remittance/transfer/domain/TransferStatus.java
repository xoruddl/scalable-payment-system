package com.remittance.transfer.domain;

public enum TransferStatus {
	PENDING,
	DEBIT_COMPLETED,
	CREDIT_COMPLETED,
	COMPLETED,
	COMPENSATING,
	FAILED
}
