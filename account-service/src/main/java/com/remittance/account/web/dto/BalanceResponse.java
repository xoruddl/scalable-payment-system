package com.remittance.account.web.dto;

import com.remittance.account.domain.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
		UUID accountId,
		BigDecimal balance,
		String currency,
		Long version,
		Instant asOf
) {
	public static BalanceResponse from(Account account) {
		return new BalanceResponse(
				account.getAccountId(),
				account.getBalance(),
				account.getCurrency(),
				account.getVersion(),
				account.getUpdatedAt()
		);
	}
}
