package com.remittance.transfer.outbox;

import com.remittance.transfer.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 릴레이가 <b>두 대에서 동시에 돌아도</b> 같은 이벤트를 두 번 발행하지 않는다.
 *
 * <p>account-service의 같은 이름 테스트와 짝이다 — Outbox 인프라가 두 벌이라 결함도 두 벌이었다.
 * (두 벌을 하나로 합치는 것은 별도 작업으로 `ROADMAP.md`에 있다.)
 *
 * <h2>왜 지금 이 테스트를 쓰나 — Phase 8의 전제다</h2>
 * 지금까지 모든 측정이 <b>단일 인스턴스</b>였다. 그래서 이 결함은 한 번도 나타난 적이 없다.
 * 그런데 Phase 7~8에서 replica를 2로 올리는 순간, 릴레이는 <b>고치는 작업이 아니라 버그</b>가 된다.
 *
 * <h2>무엇이 깨지나</h2>
 * {@code publishBatch}는 미발행 행을 {@code SELECT}해서 Kafka로 보내고 {@code published_at}을 찍는다.
 * 이 {@code SELECT}에는 <b>잠금이 없었다.</b> 두 인스턴스가 같은 순간에 읽으면 <b>같은 행</b>을
 * 둘 다 집어, 같은 이벤트를 <b>두 번</b> 보낸다.
 *
 * <h2>왜 ShedLock이 아닌가 ★</h2>
 * 다른 예약 작업(보관 기간 정리·대사·외부 조회)은 <b>한 대만 돌면 되므로</b> ShedLock으로 잠그면 된다.
 * <b>릴레이는 반대다.</b> 여러 대가 <b>동시에</b> 돌아야 처리량이 나온다 —
 * 여기에 ShedLock을 걸면 수평 확장을 하려다 릴레이를 <b>한 대로 묶어버린다.</b>
 */
@SpringBootTest
class OutboxRelayConcurrencyTest extends AbstractIntegrationTest {

	/** 두 릴레이가 같은 순간에 읽을 때 각자 집어가려는 건수. 운영의 {@code BATCH_SIZE}와 같은 역할. */
	private static final int BATCH = 10;

	/**
	 * 두 번째 릴레이가 <b>기다리지 않는다</b>를 확인하는 상한.
	 *
	 * <p>넉넉해 보이지만 의미가 있다 — {@code SKIP LOCKED} 없이 {@code FOR UPDATE}만 걸면
	 * 두 번째는 {@code innodb_lock_wait_timeout}(기본 50초)까지 <b>블로킹된다.</b>
	 * 그 경우 이 테스트는 통과가 아니라 <b>시간을 넘겨 실패</b>해야 한다.
	 */
	private static final long 안_기다린다_초 = 10;

	@Autowired
	private OutboxEventRepository repository;

	@Autowired
	private OutboxBatchPublisher batchPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * ⚠️ 테스트 메서드에 {@code @Transactional}을 붙이지 않는다. 붙이면 <b>테스트가 트랜잭션을
	 * 대신 만들어주고</b>, 운영 코드가 자기 트랜잭션을 여는지를 못 본다 —
	 * account-service의 보관 기간 테스트에서 실제로 그렇게 거짓 green이 나왔다.
	 * 준비 데이터는 자동 커밋되는 JdbcTemplate으로 넣는다.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void 앞_테스트가_남긴_행을_치운다() {
		jdbcTemplate.update("delete from outbox_events");
	}

	/**
	 * ★ 이 테스트가 이 작업의 전부다.
	 *
	 * <p>릴레이 A가 트랜잭션을 연 채 미발행 행을 집는다. <b>커밋하기 전에</b> 릴레이 B가 같은 것을 집는다.
	 * 실제 두 인스턴스에서 벌어지는 일을 그대로 만든 것이다 — A가 Kafka로 보내는 동안(수 ms)
	 * B의 폴링 주기(200ms)가 겹치면 이 상황이 된다.
	 */
	@Test
	void 두_릴레이가_같은_행을_집지_않는다() throws Exception {
		이벤트를_남긴다(BATCH);

		CountDownLatch A가_집었다 = new CountDownLatch(1);
		CountDownLatch A는_아직_커밋하지_않는다 = new CountDownLatch(1);
		AtomicReference<List<Long>> A가_집은_것 = new AtomicReference<>(List.of());
		AtomicReference<RuntimeException> A의_실패 = new AtomicReference<>();

		Thread 릴레이_A = new Thread(() -> {
			try {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					A가_집은_것.set(집는다());
					A가_집었다.countDown();
					기다린다(A는_아직_커밋하지_않는다);
				});
			} catch (RuntimeException e) {
				A의_실패.set(e);
				A가_집었다.countDown();
			}
		}, "릴레이-A");
		릴레이_A.start();

		try {
			assertThat(A가_집었다.await(안_기다린다_초, TimeUnit.SECONDS))
					.as("릴레이 A가 행을 집지 못했다 — 테스트 준비가 잘못됐다")
					.isTrue();
			assertThat(A의_실패.get()).isNull();
			assertThat(A가_집은_것.get()).as("A는 준비한 만큼 집어야 한다").hasSize(BATCH);

			List<Long> B가_집은_것 = 기다리지_않고_집는다();

			assertThat(B가_집은_것)
					.as("""
							A가 아직 커밋하지 않은 행을 B도 집었다 — 두 인스턴스가 같은 이벤트를 \
							두 번 발행한다. replica를 늘릴수록 헛일이 늘어난다""")
					.doesNotContainAnyElementsOf(A가_집은_것.get());
		} finally {
			A는_아직_커밋하지_않는다.countDown();
			릴레이_A.join(TimeUnit.SECONDS.toMillis(안_기다린다_초));
		}
	}

	/**
	 * 운영 경로 그대로 — 두 스레드가 {@code publishBatch}를 동시에 부른다.
	 *
	 * <p>위 테스트가 계약이고 이건 <b>그 계약이 실제 호출 경로에서도 지켜지는지</b>를 본다.
	 * 반환값은 "발행하고 마킹한 건수"이므로, 합이 준비한 행 수를 넘으면 <b>같은 행을 두 번 센 것</b>이다.
	 */
	@Test
	void 동시에_돌려도_마킹은_행마다_한_번이다() throws Exception {
		이벤트를_남긴다(BATCH);

		CountDownLatch 동시에_출발 = new CountDownLatch(1);
		AtomicInteger 발행한_합 = new AtomicInteger();
		AtomicReference<RuntimeException> 실패 = new AtomicReference<>();

		Runnable 릴레이 = () -> {
			try {
				기다린다(동시에_출발);
				발행한_합.addAndGet(batchPublisher.publishBatch(BATCH));
			} catch (RuntimeException e) {
				실패.set(e);
			}
		};
		Thread 하나 = new Thread(릴레이, "릴레이-1");
		Thread 둘 = new Thread(릴레이, "릴레이-2");
		하나.start();
		둘.start();

		동시에_출발.countDown();
		하나.join(TimeUnit.SECONDS.toMillis(안_기다린다_초));
		둘.join(TimeUnit.SECONDS.toMillis(안_기다린다_초));

		assertThat(실패.get()).isNull();
		assertThat(발행한_합.get())
				.as("합이 행 수를 넘으면 같은 행을 두 릴레이가 각자 발행한 것이다")
				.isEqualTo(BATCH);
		assertThat(repository.countByPublishedAtIsNull())
				.as("한 번씩만 발행했더라도 남는 게 있으면 안 된다")
				.isZero();
	}

	/** 릴레이가 실제로 쓰는 조회. 이 메서드가 바뀌는 것이 이 작업의 전부다. */
	private List<Long> 집는다() {
		return repository.findUnpublishedForRelay(BATCH).stream().map(OutboxEvent::getId).toList();
	}

	/**
	 * 두 번째 릴레이는 <b>먼저 잡은 쪽을 기다리면 안 된다.</b> 기다리면 두 대가 한 대만도 못해진다.
	 * 그래서 별도 스레드에서 시간 제한을 두고 부른다 — 블로킹되면 여기서 실패한다.
	 */
	private List<Long> 기다리지_않고_집는다() throws Exception {
		AtomicReference<List<Long>> 집은_것 = new AtomicReference<>();
		Thread 릴레이_B = new Thread(
				() -> new TransactionTemplate(transactionManager)
						.executeWithoutResult(status -> 집은_것.set(집는다())),
				"릴레이-B");
		릴레이_B.start();
		릴레이_B.join(TimeUnit.SECONDS.toMillis(안_기다린다_초));

		assertThat(릴레이_B.isAlive())
				.as("""
						B가 A를 기다리고 있다 — SKIP LOCKED 없이 FOR UPDATE만 걸면 이렇게 된다. \
						두 대를 띄워도 한 대씩 줄 서서 도는 것이라 확장이 아니다""")
				.isFalse();
		return 집은_것.get();
	}

	private void 이벤트를_남긴다(int 건수) {
		for (int i = 0; i < 건수; i++) {
			repository.save(OutboxEvent.builder()
					.aggregateType("Transfer")
					.aggregateId(UUID.randomUUID())
					.eventType("test.event")
					.payload("{}")
					.build());
		}
	}

	private void 기다린다(CountDownLatch 신호) {
		try {
			if (!신호.await(안_기다린다_초, TimeUnit.SECONDS)) {
				throw new IllegalStateException("신호를 못 받았다");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
