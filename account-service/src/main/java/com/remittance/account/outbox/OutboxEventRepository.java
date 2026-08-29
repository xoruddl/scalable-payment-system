package com.remittance.account.outbox;

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
	 * 이 애그리거트 앞으로 아직 발행되지 않은 이벤트가 있는가.
	 *
	 * <p>개시 잔액 이월이 쓴다 — 미발행 분개가 남아 있으면 원장이 잔액보다 뒤처져 있다는 뜻이라,
	 * 그 상태에서 차이를 이월하면 같은 변경을 두 번 세게 된다.
	 */
	boolean existsByAggregateIdAndPublishedAtIsNull(UUID aggregateId);

	/**
	 * 발행이 끝났는데 아직 남아 있는 건수 (Phase 4 · 보관 기간).
	 *
	 * <p>미발행 적체는 처음부터 세고 있었는데 <b>발행이 끝난 뒤 쌓이는 것은 아무도 세지 않았다.</b>
	 * 그래서 240만 건이 될 때까지 몰랐다.
	 */
	long countByPublishedAtIsNotNull();

	/**
	 * 보관 기간이 지난 행을 <b>끊어서</b> 지운다.
	 *
	 * <p>파생 쿼리(`deleteBy...`)를 쓰지 않는 이유는 <b>건수를 못 자르기 때문</b>이다.
	 * 한 번에 다 지우면 트랜잭션이 길어지고 삭제 흔적이 한꺼번에 쏟아진다 —
	 * 2026-08-29에 360만 건을 한 번에 지우고 곧바로 재봤다가 종결 p99가 두 배 나빠졌다.
	 *
	 * @return 실제로 지운 건수. {@code limit}보다 적으면 더 지울 게 없다는 뜻이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = "delete from outbox_events where published_at is not null "
			+ "and published_at < :before order by id limit :limit", nativeQuery = true)
	int deletePublishedBefore(@Param("before") Instant before, @Param("limit") int limit);
}
