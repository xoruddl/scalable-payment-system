package com.remittance.account.lock;

import com.remittance.account.AbstractRedisIntegrationTest;
import com.remittance.account.exception.LockAcquisitionException;
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
class DistributedLockTest extends AbstractRedisIntegrationTest {

	@Autowired
	private DistributedLock distributedLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private String newKey() {
		return "lock:test:" + UUID.randomUUID();
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
