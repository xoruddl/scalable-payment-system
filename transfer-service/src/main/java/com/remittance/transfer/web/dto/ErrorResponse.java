package com.remittance.transfer.web.dto;

public record ErrorResponse(String code, String message, String traceId) {
}
