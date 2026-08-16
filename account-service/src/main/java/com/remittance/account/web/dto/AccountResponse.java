package com.remittance.account.web.dto;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountStatus;
import com.remittance.account.domain.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
		UUID accountId,
		UUID ownerId,
		String currency,
		AccountType accountType,
		AccountStatus status,
		Instant createdAt
) {
	public static AccountResponse from(Account account) {
		return new AccountResponse(
				account.getAccountId(),
				account.getOwnerId(),
				account.getCurrency(),
				account.getAccountType(),
				account.getStatus(),
				account.getCreatedAt()
		);
	}
}
