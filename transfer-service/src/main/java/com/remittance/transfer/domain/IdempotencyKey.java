package com.remittance.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * POST /transfers 의 Idempotency-Key 헤더 값을 저장한다.
 * 동일 key로 재요청 시 requestHash를 비교해 동일 payload면 기존 처리 결과를,
 * 다른 payload면 409/422 충돌 응답을 내리는 데 사용한다.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

	@Id
	@Column(length = 36)
	private String key;

	@Column(nullable = false, length = 64, updatable = false)
	private String requestHash;

	@Column
	private UUID transferId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private IdempotencyStatus status;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant expiresAt;

	@Builder
	public IdempotencyKey(String key, String requestHash, Instant expiresAt) {
		this.key = key;
		this.requestHash = requestHash;
		this.status = IdempotencyStatus.IN_PROGRESS;
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	public void complete(UUID transferId) {
		this.transferId = transferId;
		this.status = IdempotencyStatus.COMPLETED;
	}

	public void fail() {
		this.status = IdempotencyStatus.FAILED;
	}
}
