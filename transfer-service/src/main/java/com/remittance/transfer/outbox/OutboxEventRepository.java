package com.remittance.transfer.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	/** 아직 발행되지 않은 이벤트를 생성 순서대로 가져온다. */
	List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);

	List<OutboxEvent> findByAggregateIdOrderByIdAsc(UUID aggregateId);

	/**
	 * 아직 발행되지 않은 이벤트가 몇 건 쌓여 있는가 (Phase 5 Step 2).
	 *
	 * <p>릴레이가 부하를 못 따라가면 <b>접수는 계속 202를 주는데 돈은 안 움직이는</b> 상태가 된다.
	 * 접수 지연·에러율만 보면 시스템이 멀쩡해 보이므로, 이 숫자가 그 사이를 메운다.
	 */
	long countByPublishedAtIsNull();
}
