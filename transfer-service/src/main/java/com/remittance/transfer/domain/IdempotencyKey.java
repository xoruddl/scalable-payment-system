package com.remittance.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * POST /transfers 의 Idempotency-Key 헤더 값을 저장한다.
 * 동일 key로 재요청 시 requestHash를 비교해 동일 payload면 최초 처리 결과를,
 * 다른 payload면 충돌 응답을 내리는 데 사용한다.
 *
 * <p>PK를 애플리케이션이 직접 부여하기 때문에 Spring Data JPA는 이 엔티티를 "이미 존재하는 것"으로
 * 보고 {@code merge()}를 호출한다. 그러면 중복 key 저장이 예외 대신 조용한 UPDATE가 되어
 * 멱등성 판정이 무너진다. {@link Persistable}로 신규 여부를 직접 알려줘 {@code persist()},
 * 즉 INSERT가 실행되도록 하고, 중복은 DB unique 제약 위반으로 드러나게 한다.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey implements Persistable<String> {

	@Id
	@Column(name = "idempotency_key", length = 36)
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

	@Transient
	private boolean newEntity = true;

	@Builder
	public IdempotencyKey(String key, String requestHash, Instant expiresAt) {
		this.key = key;
		this.requestHash = requestHash;
		this.status = IdempotencyStatus.IN_PROGRESS;
		this.createdAt = Instant.now();
		this.expiresAt = expiresAt;
	}

	@Override
	public String getId() {
		return key;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.newEntity = false;
	}

	public void complete(UUID transferId) {
		this.transferId = transferId;
		this.status = IdempotencyStatus.COMPLETED;
	}

	public void fail(UUID transferId) {
		this.transferId = transferId;
		this.status = IdempotencyStatus.FAILED;
	}

	public boolean matches(String requestHash) {
		return this.requestHash.equals(requestHash);
	}

	/** 처리가 끝난(성공이든 실패든) 상태인지. 재요청 시 저장된 결과를 그대로 돌려줄 수 있다. */
	public boolean isTerminal() {
		return status == IdempotencyStatus.COMPLETED || status == IdempotencyStatus.FAILED;
	}
}
