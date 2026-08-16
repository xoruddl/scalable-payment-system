package com.remittance.ledger.web.dto;

import com.remittance.ledger.domain.TransactionDirection;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RecordTransactionRequest(
		@NotNull UUID transferId,
		@NotNull UUID accountId,
		@NotNull TransactionDirection direction,
		@NotNull BigDecimal amount,
		@NotNull BigDecimal balanceAfter
) {
}
