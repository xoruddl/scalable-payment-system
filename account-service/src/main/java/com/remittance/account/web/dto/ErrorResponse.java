package com.remittance.account.web.dto;

public record ErrorResponse(String code, String message, String traceId) {
}
