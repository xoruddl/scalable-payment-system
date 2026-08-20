package com.remittance.transfer.web.dto;

import com.remittance.transfer.domain.IdempotencyKey;

import java.time.Instant;

/**
 * 접수 도중 서버가 죽어 {@code IN_PROGRESS}로 남은 멱등성 키.
 * 이 키로 다시 요청하면 영원히 409를 받는다 — 사용자 입장에선 송금이 막힌 것이다.
 */
public record StrandedKeyView(String idempotencyKey, Instant createdAt) {

	public static StrandedKeyView from(IdempotencyKey key) {
		return new StrandedKeyView(key.getKey(), key.getCreatedAt());
	}
}
