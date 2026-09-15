package com.remittance.account.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.Length;
import org.springframework.data.domain.DomainEvents;

import com.remittance.account.support.Timestamps;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Outbox 패턴의 이벤트 저장소. (transfer-service의 같은 이름 클래스와 구조가 같다 —
 * 서비스 경계를 유지하기 위해 공유 모듈을 두지 않고 각자 갖는다.)
 *
 * 해결하려는 문제: "DB에 상태를 저장하는 것"과 "Kafka로 이벤트를 발행하는 것"은 서로 다른 시스템이라
 * 하나의 트랜잭션으로 묶을 수 없다. 상태만 저장되고 발행이 실패하면 이벤트가 유실되고,
 * 발행만 되고 저장이 롤백되면 있지도 않은 일이 알려진다.
 *
 * 그래서 발행 대신 같은 DB 트랜잭션 안에서 이 테이블에 INSERT한다. 상태 변경과 이벤트 기록은
 * 원자적으로 함께 커밋되고, 별도 릴레이가 이 테이블을 읽어 Kafka로 보낸다.
 * 발행이 실패하면 {@code publishedAt}이 비어 있으므로 다음 폴링에서 다시 시도된다
 * (= at-least-once. 중복 수신은 소비하는 쪽이 감당해야 한다).
 */
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_unpublished", columnList = "publishedAt, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 이벤트를 발생시킨 애그리거트 종류 (예: Account) */
	@Column(nullable = false, length = 50, updatable = false)
	private String aggregateType;

	/** 애그리거트 식별자. Kafka 파티션 키로 써서 같은 송금의 이벤트 순서를 보장한다. */
	@Column(nullable = false, updatable = false)
	private UUID aggregateId;

	/** Kafka 토픽명으로도 쓰인다 (예: transfer.completed) */
	@Column(nullable = false, length = 100, updatable = false)
	private String eventType;

	/**
	 * {@code @Lob}만 붙이면 Hibernate가 기본 길이(255)를 보고 MySQL에서 TINYTEXT로 만들어
	 * 이벤트 본문이 잘린다("Data too long"). 길이를 명시해 LONGTEXT로 잡는다.
	 * (H2는 관대해서 테스트는 통과했고, MySQL e2e에서야 드러난 문제)
	 */
	@Lob
	@Column(nullable = false, updatable = false, length = Length.LONG32)
	private String payload;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** null이면 아직 발행되지 않았다는 뜻. 릴레이가 이 조건으로 폴링한다. */
	@Column
	private Instant publishedAt;

	@Builder
	public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payload) {
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.createdAt = Timestamps.now();
	}

	public void markPublished() {
		this.publishedAt = Timestamps.now();
	}

	/**
	 * 저장되면 릴레이를 깨운다 — 다음 폴링을 기다리지 않게 (2026-09-15, D-007).
	 *
	 * Spring Data가 {@code save}가 끝날 때 이 값을 이벤트로 내고, {@link OutboxRelayLoop}가 행을 적은
	 * 트랜잭션이 커밋된 뒤에 받는다. 그래서 행을 적는 곳(잔액 분개 · Saga 단계 · 모르는 입금)마다 깨우라고
	 * 부르지 않는다 — 모두 {@code save}를 거친다. {@code save}를 거치지 않고 적은 행은 깨우지 못하고
	 * 주기가 줍는다. 늦을 뿐 빠지지 않는다.
	 * (Spring Data는 {@code delete}에서도 내지만, 보관 기간 정리는 쿼리로 지워서 이 길을 타지 않는다.)
	 */
	@DomainEvents
	Collection<Recorded> recorded() {
		return List.of(Recorded.INSTANCE);
	}

	/** 행이 적혔다는 신호. 내용은 없다 — 릴레이는 어느 행인지가 아니라 "적힌 게 있다"만 알면 된다. */
	public enum Recorded {
		INSTANCE
	}
}
