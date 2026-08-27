package com.remittance.account.external;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 격벽 — <b>외부 호출이 스레드를 몇 개까지 붙들 수 있나.</b>
 *
 * <p>2026-08-27 실측에서 상대가 2초 느려지자 <b>내부</b> 송금 종결 p99가
 * 3,071 → 58,790ms가 됐다. 입금 리스너 하나가 내부 입금과 외부 호출을 같이 처리하는데,
 * 외부 호출만으로 스레드를 다 써버렸기 때문이다.
 *
 * <p>여기서 거는 계약은 둘이다.
 * <ol>
 *   <li><b>정원을 넘으면 즉시 거절한다</b> — 기다리면 스레드가 묶이는 것은 똑같다</li>
 *   <li><b>거절해도 자리는 돌아온다</b> — 안 그러면 한 번 막힌 뒤 영영 못 부른다</li>
 * </ol>
 */
class BulkheadTest {

	private ExternalCallBulkhead bulkhead(int capacity) {
		return new ExternalCallBulkhead(capacity, new SimpleMeterRegistry());
	}

	@Test
	void 정원_안에서는_그냥_실행된다() {
		ExternalCallBulkhead bulkhead = bulkhead(2);

		assertThat(bulkhead.call(() -> "보냈다")).isEqualTo("보냈다");
		assertThat(bulkhead.call(() -> "또 보냈다")).isEqualTo("또 보냈다");
	}

	@Test
	void 표준_Micrometer_지표로_정원과_남은_자리를_노출한다() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		new ExternalCallBulkhead(2, meters);

		assertThat(meters.get("resilience4j.bulkhead.max.allowed.concurrent.calls")
				.tag("name", "external-bank").gauge().value()).isEqualTo(2);
		assertThat(meters.get("resilience4j.bulkhead.available.concurrent.calls")
				.tag("name", "external-bank").gauge().value()).isEqualTo(2);
	}

	@Test
	void 정원이_차면_기다리지_않고_즉시_거절한다() throws Exception {
		ExternalCallBulkhead bulkhead = bulkhead(1);
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
			pool.submit(() -> bulkhead.call(() -> {
				occupied.countDown();
				await(release);
				return null;
			}));
			assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

			long startedAt = System.nanoTime();
			assertThatThrownBy(() -> bulkhead.call(() -> "들어가면 안 된다"))
					.isInstanceOf(BulkheadFullException.class);
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			// 기다렸다면 격벽의 의미가 없다 — 스레드가 묶이는 것은 똑같기 때문이다.
			assertThat(elapsedMs)
					.as("자리가 없으면 곧바로 돌아와야 그 스레드가 내부 송금을 처리한다")
					.isLessThan(100);
			release.countDown();
		}
	}

	@Test
	void 실행이_예외로_끝나도_자리는_돌아온다() {
		ExternalCallBulkhead bulkhead = bulkhead(1);

		assertThatThrownBy(() -> bulkhead.call(() -> {
			throw new IllegalStateException("상대가 터졌다");
		})).isInstanceOf(IllegalStateException.class);

		// 안 돌려주면 한 번 실패한 뒤 영영 외부로 못 나간다.
		assertThat(bulkhead.call(() -> "다시 된다")).isEqualTo("다시 된다");
	}

	@Test
	void 동시에_몰려도_정원만큼만_들어간다() throws Exception {
		int capacity = 3;
		int threads = 20;
		ExternalCallBulkhead bulkhead = bulkhead(capacity);
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					await(start);
					try {
						bulkhead.call(() -> {
							peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
							sleepQuietly();
							inside.decrementAndGet();
							return null;
						});
					} catch (BulkheadFullException full) {
						rejected.incrementAndGet();
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(peak.get())
				.as("이 숫자가 곧 '외부에 묶일 수 있는 스레드 수'다")
				.isLessThanOrEqualTo(capacity);
		assertThat(rejected.get())
				.as("나머지는 거절돼야 한다 — 거절이 곧 내부 송금을 지키는 것이다")
				.isPositive();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleepQuietly() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
