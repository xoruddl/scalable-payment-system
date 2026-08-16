package com.remittance.ledger.web.dto;

public record ErrorResponse(String code, String message, String traceId) {
}
