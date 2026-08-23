package com.remittance.account.lock;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.exception.LockAcquisitionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DistributedLockTest extends AbstractIntegrationTest {

	@Autowired
	private DistributedLock distributedLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private MeterRegistry meterRegistry;

	private String newKey() {
		return "lock:test:" + UUID.randomUUID();
	}

	private Timer waitTimer(String outcome) {
		return meterRegistry.get("remittance.lock.wait").tag("outcome", outcome).timer();
	}

	private Timer holdTimer() {
		return meterRegistry.get("remittance.lock.hold").timer();
	}

	/**
	 * 이번 호출이 <b>얼마를 보탰는지</b> 잰다.
	 *
	 * <p>{@code Timer.max()}를 쓰면 안 된다 — 그건 <b>그 타이머의 전체 이력 최댓값</b>이라
	 * 같은 클래스의 다른 테스트가 기록한 값까지 섞인다(실제로 여기서 한 번 걸렸다).
	 * 누적 시간의 차이를 봐야 이번 호출만 잰다.
	 */
	private double holdDelta(double before) {
		return holdTimer().totalTime(TimeUnit.MILLISECONDS) - before;
	}

	/**
	 * 락 대기 시간을 재는 이유 (Phase 5 Step 2).
	 *
	 * <p>핫 계좌 부하에서 <b>접수는 계속 202를 주고 HTTP 에러율도 안 오른다</b> — 경합은
	 * 비동기 파이프라인 뒤에서 벌어지기 때문이다. 그 뒤에서 무슨 일이 나는지 말해주는 게 이 값이라,
	 * Phase 6에서 락을 바꿀 때 <b>무엇이 나아졌는지 말할 근거</b>가 된다.
	 */
	/**
	 * 대기와 <b>보유</b>를 나눠 재는 이유 (Phase 6 Step 1).
	 *
	 * <p>2026-08-23 핫 계좌 측정에서 대기 p99가 95~100ms였는데, <b>그게 왜 100ms인지는
	 * 대기만 봐서는 알 수 없었다.</b> 앞사람이 오래 붙들고 있어서인지, 놓은 걸 뒷사람이
	 * 늦게 알아채서인지(이 구현은 50ms마다 다시 물어본다) 구분이 안 된다.
	 * <b>처방이 서로 달라서</b> — 전자는 임계 구역을 줄여야 하고 후자는 폴링을 그만둬야 한다 —
	 * 가르지 않으면 무엇을 고친 건지 말할 수 없다.
	 *
	 * <p>보유 시간은 그 자체로 <b>한 계좌의 처리량 상한</b>이기도 하다. 10ms면 그 계좌는
	 * 서버를 아무리 늘려도 초당 100건을 못 넘는다.
	 */
	@Test
	void 락을_쥐고_있던_시간을_잰다() {
		long beforeCount = holdTimer().count();
		double beforeTotal = holdTimer().totalTime(TimeUnit.MILLISECONDS);

		distributedLock.executeWithLock(newKey(), Duration.ofSeconds(5), Duration.ofSeconds(1), () -> {
			sleep(60);
			return null;
		});

		assertThat(holdTimer().count()).isEqualTo(beforeCount + 1);
		assertThat(holdDelta(beforeTotal))
				.as("임계 구역에서 60ms를 썼으니 그만큼은 잡혀야 한다")
				.isGreaterThanOrEqualTo(50);
	}

	/**
	 * 보유 시간에 <b>해제(Redis 왕복)까지 넣으면 안 된다.</b> 그건 임계 구역이 아니라 뒷정리다.
	 * 넣으면 "임계 구역을 줄였는데 보유 시간이 안 줄어드는" 상황이 생겨 판단이 흐려진다.
	 */
	@Test
	void 보유_시간에_해제_시간은_넣지_않는다() {
		long beforeCount = holdTimer().count();
		double beforeTotal = holdTimer().totalTime(TimeUnit.MILLISECONDS);

		distributedLock.executeWithLock(newKey(), Duration.ofSeconds(5), Duration.ofSeconds(1), () -> null);

		assertThat(holdTimer().count()).isEqualTo(beforeCount + 1);
		assertThat(holdDelta(beforeTotal))
				.as("아무것도 안 하는 임계 구역이라 Redis 왕복 시간이 섞이면 안 된다")
				.isLessThan(50);
	}

	@Test
	void 락을_기다린_시간을_잰다() {
		long before = waitTimer("acquired").count();

		distributedLock.executeWithLock(newKey(), Duration.ofSeconds(3), Duration.ofSeconds(3), () -> "ok");

		assertThat(waitTimer("acquired").count()).isEqualTo(before + 1);
	}

	@Test
	void 못_잡고_포기한_것도_센다() {
		String key = newKey();
		long before = waitTimer("timeout").count();
		// 남이 잡고 있는 상태를 만든다. TTL을 길게 줘서 기다리는 동안 풀리지 않게 한다.
		redisTemplate.opsForValue().set(key, "남의-토큰", Duration.ofSeconds(30));

		assertThatThrownBy(() -> distributedLock.executeWithLock(
				key, Duration.ofSeconds(3), Duration.ofMillis(200), () -> "ok"))
				.isInstanceOf(LockAcquisitionException.class);

		// 실패 횟수를 별도 카운터로 두지 않는다 — outcome=timeout인 타이머의 count가 곧 그것이다.
		// 지표를 둘로 나누면 둘이 어긋났을 때 어느 쪽이 맞는지 알 수 없다.
		assertThat(waitTimer("timeout").count()).isEqualTo(before + 1);
	}

	@Test
	void 임계구역에_동시에_두_스레드가_들어오지_못한다() throws Exception {
		String key = newKey();
		int threadCount = 10;
		AtomicBoolean inside = new AtomicBoolean(false);
		AtomicInteger overlaps = new AtomicInteger();
		AtomicInteger completed = new AtomicInteger();

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch done = new CountDownLatch(threadCount);
		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					distributedLock.executeWithLock(key, Duration.ofSeconds(5), Duration.ofSeconds(10), () -> {
						if (!inside.compareAndSet(false, true)) {
							overlaps.incrementAndGet();
						}
						try {
							Thread.sleep(20);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
						inside.set(false);
						completed.incrementAndGet();
						return null;
					});
				} finally {
					done.countDown();
				}
			});
		}
		done.await(30, TimeUnit.SECONDS);
		executor.shutdownNow();

		assertThat(overlaps).hasValue(0);
		assertThat(completed).hasValue(threadCount);
	}

	@Test
	void 작업이_끝나면_락이_해제된다() {
		String key = newKey();

		distributedLock.executeWithLock(key, Duration.ofSeconds(5), Duration.ofSeconds(1), () -> null);

		assertThat(redisTemplate.hasKey(key)).isFalse();
	}

	@Test
	void 작업이_예외로_끝나도_락이_해제된다() {
		String key = newKey();

		assertThatThrownBy(() -> distributedLock.executeWithLock(key, Duration.ofSeconds(5), Duration.ofSeconds(1),
				() -> {
					throw new IllegalStateException("작업 실패");
				}))
				.isInstanceOf(IllegalStateException.class);

		assertThat(redisTemplate.hasKey(key)).isFalse();
	}

	@Test
	void 대기시간을_넘기면_예외가_난다() {
		String key = newKey();
		// 다른 프로세스가 이미 잡고 있는 상황
		redisTemplate.opsForValue().set(key, "someone-else", Duration.ofSeconds(10));

		assertThatThrownBy(() -> distributedLock.executeWithLock(key, Duration.ofSeconds(5),
				Duration.ofMillis(200), () -> null))
				.isInstanceOf(LockAcquisitionException.class);
	}

	/**
	 * TTL로 락이 만료된 뒤 다른 소유자가 같은 키를 잡았다면, 늦게 끝난 쪽이 그 락을 지우면 안 된다.
	 * 단순 DEL이 아니라 토큰을 비교해 지우는 이유.
	 */
	@Test
	void 남의_락은_지우지_않는다() {
		String key = newKey();

		distributedLock.executeWithLock(key, Duration.ofMillis(100), Duration.ofSeconds(1), () -> {
			// 내 락이 만료된 뒤 다른 소유자가 같은 키를 선점한 상황을 만든다
			sleep(300);
			redisTemplate.opsForValue().set(key, "another-owner", Duration.ofSeconds(10));
			return null;
		});

		assertThat(redisTemplate.opsForValue().get(key))
				.as("내 락이 아니므로 지우지 않고 그대로 두어야 한다")
				.isEqualTo("another-owner");
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
