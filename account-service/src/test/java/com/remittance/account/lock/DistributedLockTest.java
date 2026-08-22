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

	/**
	 * 락 대기 시간을 재는 이유 (Phase 5 Step 2).
	 *
	 * <p>핫 계좌 부하에서 <b>접수는 계속 202를 주고 HTTP 에러율도 안 오른다</b> — 경합은
	 * 비동기 파이프라인 뒤에서 벌어지기 때문이다. 그 뒤에서 무슨 일이 나는지 말해주는 게 이 값이라,
	 * Phase 6에서 락을 바꿀 때 <b>무엇이 나아졌는지 말할 근거</b>가 된다.
	 */
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
