package com.remittance.account.web.dto;

import com.remittance.account.domain.AccountBalance;

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
	public static BalanceResponse from(AccountBalance balance) {
		return new BalanceResponse(
				balance.getAccountId(),
				balance.total(),
				balance.getCurrency(),
				// 잔액이 조각으로 나가면서 계좌 행의 버전은 잔액과 무관해졌다.
				// 조각마다 버전이 따로 있어 하나로 줄일 수 없으므로, 이 자리는 계좌 자체의 버전이다.
				balance.account().getVersion(),
				balance.account().getUpdatedAt()
		);
	}
}
