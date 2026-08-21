package com.remittance.account.web.dto;

import com.remittance.account.service.OpeningBalanceResult;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param outcome SEEDED / ALREADY_CARRIED / ALREADY_CONSISTENT
 * @param amount  이월한 금액 (안 심었으면 {@code null})
 */
public record OpeningBalanceResponse(UUID accountId, String outcome, BigDecimal amount) {

	public static OpeningBalanceResponse from(OpeningBalanceResult result) {
		return new OpeningBalanceResponse(result.accountId(), result.outcome().name(), result.amount());
	}
}
