package com.remittance.ledger.service;

import com.remittance.ledger.exception.InvalidCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * "recordedAt(epochMilli)|transactionId" 형태를 base64로 인코딩한 커서.
 */
public record TransactionCursor(Instant recordedAt, UUID transactionId) {

	public String encode() {
		String raw = recordedAt.toEpochMilli() + "|" + transactionId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static TransactionCursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split("\\|", 2);
			return new TransactionCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
		} catch (Exception e) {
			throw new InvalidCursorException(cursor);
		}
	}
}
