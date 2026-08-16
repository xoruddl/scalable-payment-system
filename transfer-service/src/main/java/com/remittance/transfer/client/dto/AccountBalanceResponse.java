package com.remittance.transfer.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalanceResponse(UUID accountId, BigDecimal balance, String currency, Long version) {
}
