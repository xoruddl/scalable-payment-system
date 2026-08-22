package com.remittance.transfer.outbox;

import com.remittance.transfer.support.Timestamps;
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

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 패턴의 이벤트 저장소.
 *
 * <p>해결하려는 문제: "DB에 상태를 저장하는 것"과 "Kafka로 이벤트를 발행하는 것"은 서로 다른 시스템이라
 * 하나의 트랜잭션으로 묶을 수 없다. 상태만 저장되고 발행이 실패하면 이벤트가 유실되고,
 * 발행만 되고 저장이 롤백되면 있지도 않은 일이 알려진다.
 *
 * <p>그래서 발행 대신 <b>같은 DB 트랜잭션 안에서 이 테이블에 INSERT</b>한다. 상태 변경과 이벤트 기록은
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

	/** 이벤트를 발생시킨 애그리거트 종류 (예: Transfer) */
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
}
