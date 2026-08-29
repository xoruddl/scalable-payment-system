package com.remittance.account.outbox;

import com.remittance.account.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발행이 끝난 Outbox 행을 <b>보관 기간이 지나면 지운다</b> (Phase 4).
 *
 * <h2>왜 이 테스트가 있나 — 실제로 당했다</h2>
 * 릴레이는 미발행 건만 읽고 나머지는 그냥 둔다. 그래서 사흘 만에
 * <b>account 240만 건(1,105MB) · transfer 120만 건(551MB)</b>이 쌓였고,
 * 같은 100 TPS에서 종결 p99가 <b>4,673 → 5,044ms</b>로 SLO를 넘었다.
 * 지우고 다시 재니 <b>4,639ms</b>로 돌아왔다.
 *
 * <h2>여기서 거는 계약 — 셋 중 셋째가 제일 중요하다</h2>
 * <ol>
 *   <li>보관 기간이 지난 <b>발행된</b> 행은 지운다</li>
 *   <li>기간이 안 지났으면 남긴다</li>
 *   <li>★ <b>미발행 행은 아무리 오래돼도 안 지운다</b> — 지우면 그 이벤트가 영영 발행되지 않는다.
 *       돈이 한쪽만 움직인 채 끝난다</li>
 * </ol>
 */
@SpringBootTest
class OutboxRetentionTest extends AbstractIntegrationTest {

	@Autowired
	private OutboxEventRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@Transactional
	void 오래된_발행_행만_지운다() {
		Long 오래전에_발행 = 이벤트를_남긴다();
		Long 방금_발행 = 이벤트를_남긴다();
		Long 아직_미발행 = 이벤트를_남긴다();

		발행됨으로_바꾼다(오래전에_발행, Instant.now().minus(Duration.ofDays(7)));
		발행됨으로_바꾼다(방금_발행, Instant.now());
		// 아직_미발행은 그대로 둔다 — published_at이 null이다.

		int 지운_수 = repository.deletePublishedBefore(
				Instant.now().minus(Duration.ofDays(3)), 100);

		assertThat(지운_수).isEqualTo(1);
		assertThat(남아있는(오래전에_발행)).as("보관 기간이 지났다").isFalse();
		assertThat(남아있는(방금_발행)).as("아직 조사에 쓸 수 있다").isTrue();
		assertThat(남아있는(아직_미발행))
				.as("미발행을 지우면 그 이벤트는 영영 발행되지 않는다 — 돈이 한쪽만 움직인다")
				.isTrue();
	}

	/**
	 * 한 번에 다 지우지 않는다. 삭제 흔적이 한꺼번에 쏟아지면 InnoDB가 뒤에서 치우느라
	 * <b>오히려 느려진다</b> — 2026-08-29에 360만 건을 한 번에 지우고 곧바로 재봤다가
	 * 종결 p99가 5,044 → 11,768ms가 됐다.
	 */
	@Test
	@Transactional
	void 청크보다_많으면_잘라서_지운다() {
		for (int i = 0; i < 5; i++) {
			발행됨으로_바꾼다(이벤트를_남긴다(), Instant.now().minus(Duration.ofDays(7)));
		}

		int 첫_청크 = repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(3)), 2);
		int 둘째_청크 = repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(3)), 2);

		assertThat(첫_청크).isEqualTo(2);
		assertThat(둘째_청크).isEqualTo(2);
	}

	/**
	 * 발행이 끝났는데 아직 남아 있는 건수 — <b>이 지표가 없어서 못 봤다.</b>
	 * 미발행 적체는 처음부터 세고 있었는데, 발행 뒤에 쌓이는 것은 아무도 세지 않았다.
	 */
	@Test
	@Transactional
	void 남아있는_건수를_셀_수_있다() {
		long 처음 = repository.countByPublishedAtIsNotNull();
		발행됨으로_바꾼다(이벤트를_남긴다(), Instant.now());
		이벤트를_남긴다();

		assertThat(repository.countByPublishedAtIsNotNull())
				.as("미발행은 세지 않는다 — 그건 backlog 지표가 따로 본다")
				.isEqualTo(처음 + 1);
	}

	private Long 이벤트를_남긴다() {
		return repository.save(OutboxEvent.builder()
				.aggregateType("Account")
				.aggregateId(UUID.randomUUID())
				.eventType("test.event")
				.payload("{}")
				.build()).getId();
	}

	/**
	 * {@code publishedAt}은 엔티티가 지금 시각으로만 찍으므로, 과거로 만들려면 직접 쓴다.
	 *
	 * <p>⚠️ 기본키로 찾는다. {@code aggregate_id}는 MySQL에 <b>binary(16)</b>으로 들어가서
	 * 문자열로 비교하면 아무것도 안 맞는다(처음에 그렇게 짰다가 0건이 나왔다).
	 */
	private void 발행됨으로_바꾼다(Long id, Instant publishedAt) {
		entityManager.flush();
		entityManager.createNativeQuery(
						"update outbox_events set published_at = :at where id = :id")
				.setParameter("at", publishedAt)
				.setParameter("id", id)
				.executeUpdate();
	}

	private boolean 남아있는(Long id) {
		entityManager.clear();
		return repository.findById(id).isPresent();
	}
}
