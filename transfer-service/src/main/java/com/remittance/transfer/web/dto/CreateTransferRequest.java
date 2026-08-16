package com.remittance.transfer.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(
		@NotNull UUID fromAccountId,
		@NotNull UUID toAccountId,
		@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
		@NotNull String currency,
		@Size(max = 100) String memo
) {
}
