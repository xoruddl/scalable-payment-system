package com.remittance.transfer.exception;

public class InvalidTransferRequestException extends RuntimeException {

	public InvalidTransferRequestException(String message) {
		super(message);
	}
}
