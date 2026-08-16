package com.remittance.account.exception;

import com.remittance.account.domain.AccountStatus;

import java.util.UUID;

public class AccountNotActiveException extends RuntimeException {

	public AccountNotActiveException(UUID accountId, AccountStatus status) {
		super("계좌가 활성 상태가 아닙니다 (accountId=" + accountId + ", status=" + status + ")");
	}
}
