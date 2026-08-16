package com.remittance.transfer.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AdjustBalanceRequest(BigDecimal amount, String currency, UUID transferId) {
}
