package com.remittance.account.web.dto;

import com.remittance.account.domain.AccountType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateAccountRequest(
		@NotNull UUID ownerId,
		@NotNull String currency,
		AccountType accountType
) {
}
