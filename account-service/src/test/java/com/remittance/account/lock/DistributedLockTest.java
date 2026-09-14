package com.remittance.account.lock;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.exception.LockAcquisitionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

/**
 * Redisson으로 갈아탄 분산 락 (Phase 6.7, D-006).
 *
 * 자체 구현 때의 시험을 그대로 옮겼다 — 지키던 약속이 같기 때문이다. 다른 점은 둘이다.
 *   - "남이 쥐고 있다"를 만들 때 Redis 키를 직접 쓰지 않고 다른 스레드가 진짜로 잡는다.
 *     Redisson 락은 해시 구조라 키를 손으로 흉내 내면 Redisson이 락으로 보지 않는다
 *   - watchdog이 생겼으므로 "TTL보다 오래 쥐어도 안 풀린다"를 새로 건다
 */
@SpringBootTest
class DistributedLockTest extends AbstractIntegrationTest {

	private static final Duration WAIT = Duration.ofSeconds(1);

	@Autowired
	private DistributedLock distributedLock;

	@Autowired
	private RedissonClient redisson;

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

	private double releaseCount(String outcome) {
		return meterRegistry.get("remittance.lock.release").tag("outcome", outcome).counter().count();
	}

	/**
	 * 이번 호출이 얼마를 보탰는지 잰다.
	 *
	 * {@code Timer.max()}를 쓰면 안 된다 — 그건 그 타이머의 전체 이력 최댓값이라
	 * 같은 클래스의 다른 테스트가 기록한 값까지 섞인다(실제로 여기서 한 번 걸렸다).
	 */
	private double holdDelta(double before) {
		return holdTimer().totalTime(TimeUnit.MILLISECONDS) - before;
	}

	/**
	 * 대기와 보유를 나눠 재는 이유 (Phase 6 Step 1).
	 *
	 * 대기만 봐서는 앞사람이 오래 붙들고 있어서인지, 놓은 걸 뒷사람이 늦게 알아채서인지
	 * 구분이 안 된다. 처방이 서로 달라서 가르지 않으면 무엇을 고친 건지 말할 수 없다.
	 * 보유 시간은 그 자체로 한 계좌 조각의 처리량 상한이기도 하다.
	 */
	@Test
	void 락을_쥐고_있던_시간을_잰다() {
		long beforeCount = holdTimer().count();
		double beforeTotal = holdTimer().totalTime(TimeUnit.MILLISECONDS);

		distributedLock.executeWithLock(newKey(), WAIT, () -> {
			sleep(60);
			return null;
		});

		assertThat(holdTimer().count()).isEqualTo(beforeCount + 1);
		assertThat(holdDelta(beforeTotal))
				.as("임계 구역에서 60ms를 썼으니 그만큼은 잡혀야 한다")
				.isGreaterThanOrEqualTo(50);
	}

	/** 보유 시간에 해제(Redis 왕복)까지 넣으면 안 된다. 그건 임계 구역이 아니라 뒷정리다. */
	@Test
	void 보유_시간에_해제_시간은_넣지_않는다() {
		long beforeCount = holdTimer().count();
		double beforeTotal = holdTimer().totalTime(TimeUnit.MILLISECONDS);

		distributedLock.executeWithLock(newKey(), WAIT, () -> null);

		assertThat(holdTimer().count()).isEqualTo(beforeCount + 1);
		assertThat(holdDelta(beforeTotal))
				.as("아무것도 안 하는 임계 구역이라 Redis 왕복 시간이 섞이면 안 된다")
				.isLessThan(50);
	}

	@Test
	void 락을_기다린_시간을_잰다() {
		long before = waitTimer("acquired").count();

		distributedLock.executeWithLock(newKey(), WAIT, () -> "ok");

		assertThat(waitTimer("acquired").count()).isEqualTo(before + 1);
	}

	@Test
	void 못_잡고_포기한_것도_센다() throws Exception {
		String key = newKey();
		long before = waitTimer("timeout").count();

		try (Holder ignored = heldByAnotherThread(key)) {
			assertThatThrownBy(() -> distributedLock.executeWithLock(key, Duration.ofMillis(200), () -> "ok"))
					.isInstanceOf(LockAcquisitionException.class);
		}

		// 실패 횟수를 별도 카운터로 두지 않는다 — outcome=timeout인 타이머의 count가 곧 그것이다.
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
					distributedLock.executeWithLock(key, Duration.ofSeconds(10), () -> {
						if (!inside.compareAndSet(false, true)) {
							overlaps.incrementAndGet();
						}
						sleep(20);
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

		distributedLock.executeWithLock(key, WAIT, () -> null);

		assertThat(redisson.getLock(key).isLocked()).isFalse();
	}

	@Test
	void 작업이_예외로_끝나도_락이_해제된다() {
		String key = newKey();

		assertThatThrownBy(() -> distributedLock.executeWithLock(key, WAIT, () -> {
			throw new IllegalStateException("작업 실패");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(redisson.getLock(key).isLocked()).isFalse();
	}

	/**
	 * watchdog이 이번 교체의 첫 번째 이유다 (D-006).
	 *
	 * lease를 따로 주지 않으면 Redisson은 {@code lockWatchdogTimeout}(5초)으로 잡고 그 1/3마다 늘린다.
	 * 2.5초를 쥐고 있으면 그 사이 한 번 이상 늘어나 남은 시간이 3초를 넘어야 한다 —
	 * 늘리지 않았다면 5 − 2.5 = 2.5초만 남는다. 자체 구현이었다면 3초 TTL로 여기서 풀려버렸다.
	 */
	@Test
	void 오래_쥐고_있어도_watchdog이_락을_연장한다() {
		String key = newKey();

		long remainingMs = distributedLock.executeWithLock(key, WAIT, () -> {
			sleep(2_500);
			return redisson.getLock(key).remainTimeToLive();
		});

		assertThat(remainingMs)
				.as("연장되지 않았다면 2.5초 안팎만 남는다")
				.isGreaterThan(3_000);
	}

	/**
	 * 내 락이 이미 사라지고 다른 소유자가 같은 키를 잡았다면, 늦게 끝난 쪽이 그 락을 지우면 안 된다.
	 * 자체 구현은 Lua로 토큰을 비교했고, Redisson은 소유 스레드를 비교해 막는다.
	 */
	@Test
	void 남의_락은_지우지_않는다() throws Exception {
		String key = newKey();
		Holder[] another = new Holder[1];

		distributedLock.executeWithLock(key, WAIT, () -> {
			// 내 락이 사라진 뒤(watchdog이 연장하지 못한 것과 같은 모양) 다른 소유자가 잡는다.
			redisson.getLock(key).forceUnlock();
			another[0] = heldByAnotherThreadQuietly(key);
			return null;
		});

		try (Holder ignored = another[0]) {
			assertThat(redisson.getLock(key).isLocked())
					.as("내 락이 아니므로 놓지 않고 그대로 두어야 한다")
					.isTrue();
		}
	}

	/**
	 * 위의 "지우지 않는다"는 피해를 막은 것이지 사고가 안 난 게 아니다. 저 상황이 벌어졌다는 건
	 * 락이 먼저 사라져 임계 구역이 겹쳐 돌았다는 뜻이다. LAYERED에서는 행 락이 뒤에서 막아
	 * 에러율에도 대사에도 안 나타나므로, 여기 말고는 알 곳이 없다.
	 */
	@Test
	void 락을_뺏긴_채로_끝나면_해제_실패로_센다() throws Exception {
		String key = newKey();
		double before = releaseCount("lost");
		Holder[] another = new Holder[1];

		distributedLock.executeWithLock(key, WAIT, () -> {
			redisson.getLock(key).forceUnlock();
			another[0] = heldByAnotherThreadQuietly(key);
			return null;
		});
		another[0].close();

		assertThat(releaseCount("lost"))
				.as("락이 먼저 사라졌다는 신호는 여기 말고 나올 곳이 없다")
				.isEqualTo(before + 1);
	}

	@Test
	void 제_손으로_놓으면_해제_성공으로_센다() {
		double beforeReleased = releaseCount("released");
		double beforeLost = releaseCount("lost");

		distributedLock.executeWithLock(newKey(), WAIT, () -> null);

		assertThat(releaseCount("released")).isEqualTo(beforeReleased + 1);
		assertThat(releaseCount("lost"))
				.as("정상 경로가 lost를 올리면 지표를 믿을 수 없다")
				.isEqualTo(beforeLost);
	}

	/**
	 * 다른 스레드가 진짜로 락을 잡고 쥐고 있다. Redisson 락은 잡은 스레드만 놓을 수 있으므로
	 * 놓는 것도 그 스레드가 한다 — 닫으면 놓는다.
	 */
	private Holder heldByAnotherThread(String key) throws InterruptedException {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService owner = Executors.newSingleThreadExecutor();
		owner.submit(() -> {
			RLock lock = redisson.getLock(key);
			lock.lock();
			locked.countDown();
			awaitQuietly(release);
			lock.unlock();
		});
		assertThat(locked.await(10, TimeUnit.SECONDS)).as("다른 스레드가 락을 잡지 못했다").isTrue();
		return new Holder(release, owner);
	}

	private Holder heldByAnotherThreadQuietly(String key) {
		try {
			return heldByAnotherThread(key);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private record Holder(CountDownLatch release, ExecutorService owner) implements AutoCloseable {

		@Override
		public void close() throws InterruptedException {
			release.countDown();
			owner.shutdown();
			owner.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
