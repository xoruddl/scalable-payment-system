package com.remittance.ledger.web.dto;

import java.util.List;

public record TransactionPageResponse(List<TransactionResponse> items, String nextCursor, boolean hasNext) {
}
