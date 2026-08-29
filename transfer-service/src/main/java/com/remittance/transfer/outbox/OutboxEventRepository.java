package com.remittance.transfer.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

	/**
	 * 발행이 끝났는데 아직 남아 있는 건수 (Phase 4 · 보관 기간).
	 *
	 * <p>미발행 적체는 처음부터 세고 있었는데 <b>발행이 끝난 뒤 쌓이는 것은 아무도 세지 않았다.</b>
	 * 그래서 120만 건이 될 때까지 몰랐다.
	 */
	long countByPublishedAtIsNotNull();

	/**
	 * 보관 기간이 지난 행을 <b>끊어서</b> 지운다. 이유는 `account-service`의 같은 메서드에 있다.
	 *
	 * @return 실제로 지운 건수. {@code limit}보다 적으면 더 지울 게 없다는 뜻이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = "delete from outbox_events where published_at is not null "
			+ "and published_at < :before order by id limit :limit", nativeQuery = true)
	int deletePublishedBefore(@Param("before") Instant before, @Param("limit") int limit);
}
