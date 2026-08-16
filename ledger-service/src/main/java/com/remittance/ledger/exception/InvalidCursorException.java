package com.remittance.ledger.exception;

public class InvalidCursorException extends RuntimeException {

	public InvalidCursorException(String cursor) {
		super("유효하지 않은 cursor 값입니다: " + cursor);
	}
}
