package com.remittance.transfer.exception;

/**
 * Account Service 호출 자체가 실패(네트워크 오류, 5xx, 알 수 없는 응답)했을 때 사용한다.
 */
public class AccountServiceException extends RuntimeException {

	public AccountServiceException(String message, Throwable cause) {
		super(message, cause);
	}
}
