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

	/**
	 * 릴레이가 발행하려고 집어가는 조회. <b>집은 행은 커밋할 때까지 다른 릴레이가 못 집는다.</b>
	 *
	 * <p><b>왜 잠그나</b>: 잠금이 없으면 두 인스턴스가 같은 순간에 읽어 <b>같은 행을 둘 다</b> 집는다.
	 * 돈이 틀리지는 않지만(소비 쪽이 멱등하다) Kafka·컨슈머·DB가 하는 일이 통째로 두 배가 된다 —
	 * <b>replica를 늘릴수록 헛일이 늘어난다.</b> 단일 인스턴스에서는 나타날 수 없는 결함이라
	 * Phase 8에서 replica를 2로 올리는 순간 버그가 된다.
	 *
	 * <p><b>왜 {@code SKIP LOCKED}인가</b>: {@code FOR UPDATE}만 걸면 두 번째 릴레이가 첫 번째를
	 * <b>기다린다.</b> 그러면 두 대를 띄워도 한 대씩 줄 서서 도는 것이라 확장이 아니다.
	 * {@code SKIP LOCKED}는 잠긴 행을 <b>건너뛰고 그다음 것을 집게</b> 해서, 두 릴레이가
	 * 서로 다른 행을 나눠 갖고 <b>동시에</b> 일하게 만든다.
	 *
	 * <p><b>대가</b>: 이 조회를 부르는 {@code publishBatch}는 트랜잭션 안에서 Kafka 전송까지
	 * 끝내므로, <b>행 락을 네트워크 I/O 동안 쥐고 있다.</b> 단일 인스턴스에서는 보이지 않던
	 * 성질이라 replica를 늘린 뒤 재측정 대상이다.
	 *
	 * <p><b>왜 파생 쿼리가 아니라 네이티브인가</b>: JPA로 {@code SKIP LOCKED}를 걸려면
	 * {@code @QueryHints}에 {@code -2}라는 매직 넘버를 써야 한다. 무슨 뜻인지 코드만 봐서는
	 * 알 수 없으므로, <b>SQL을 그대로 적는 편</b>을 골랐다. (같은 이유로 아래 삭제 쿼리도 네이티브다)
	 */
	@Query(value = "select * from outbox_events where published_at is null "
			+ "order by id limit :limit for update skip locked", nativeQuery = true)
	List<OutboxEvent> findUnpublishedForRelay(@Param("limit") int limit);

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
