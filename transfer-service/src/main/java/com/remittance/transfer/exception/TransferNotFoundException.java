package com.remittance.transfer.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

	public TransferNotFoundException(UUID transferId) {
		super("송금 요청을 찾을 수 없습니다: " + transferId);
	}
}
