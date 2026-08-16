package com.remittance.transfer.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RecordTransactionRequest(
		UUID transferId,
		UUID accountId,
		TransactionDirection direction,
		BigDecimal amount,
		BigDecimal balanceAfter
) {
}
