package com.remittance.account.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AdjustBalanceRequest(
		@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
		@NotNull String currency,
		UUID transferId
) {
}
