package com.remittance.transfer.client.dto;

public record ErrorResponse(String code, String message, String traceId) {
}
