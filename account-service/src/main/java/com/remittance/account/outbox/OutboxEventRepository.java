package com.remittance.account.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	/** 아직 발행되지 않은 이벤트를 생성 순서대로 가져온다. */
	List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);

	List<OutboxEvent> findByAggregateIdOrderByIdAsc(UUID aggregateId);

	/**
	 * 이 애그리거트 앞으로 아직 발행되지 않은 이벤트가 있는가.
	 *
	 * <p>개시 잔액 이월이 쓴다 — 미발행 분개가 남아 있으면 원장이 잔액보다 뒤처져 있다는 뜻이라,
	 * 그 상태에서 차이를 이월하면 같은 변경을 두 번 세게 된다.
	 */
	boolean existsByAggregateIdAndPublishedAtIsNull(UUID aggregateId);
}
