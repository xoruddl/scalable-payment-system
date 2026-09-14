package com.remittance.account.lock;

import com.remittance.account.exception.LockAcquisitionException;
import com.remittance.account.exception.LockUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Redis가 없을 때 분산 락이 어떻게 굴어야 하는가 (Phase 6.7, D-006).
 *
 * 폴백(행 락만으로 진행)이 안전하려면 이 클래스가 약속 셋을 지켜야 한다.
 *   - 닿지 못하면 작업을 돌리지 않은 채 알린다 — 그래야 호출부가 대신 돌려도 두 번 돌지 않는다
 *   - 연달아 닿지 못하면 한동안 부르지 않는다 — 요청마다 연결 타임아웃을 기다리지 않게
 *   - 붐빈 것은 Redis가 없는 것과 다르다 — 핫 계좌에서 회로가 열리면 멀쩡한 Redis를 두고 폴백으로 샌다
 * 그리고 작업이 끝난 뒤 놓으러 갔을 때 Redis가 없으면, 끝난 작업을 실패로 만들지 않는다.
 *
 * 진짜 Redis를 멈추면 같은 JVM의 다른 통합 시험이 흔들리므로 여기서는 목으로 모양만 만든다.
 * Redis 없이 실제로 입금이 되는지는 {@code RedisDownFallbackTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockUnavailableTest {

	private static final String KEY = "lock:account:test:s0";
	private static final Duration WAIT = Duration.ofSeconds(1);

	@Mock
	private RedissonClient redisson;

	@Mock
	private RLock lock;

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private DistributedLock distributedLock;

	@BeforeEach
	void setUp() {
		given(redisson.getLock(anyString())).willReturn(lock);
		distributedLock = new DistributedLock(redisson, meters);
	}

	private double unavailable(String reason) {
		return meters.get("remittance.lock.unavailable").tag("reason", reason).counter().count();
	}

	private double release(String outcome) {
		return meters.get("remittance.lock.release").tag("outcome", outcome).counter().count();
	}

	@Test
	void Redis에_못_닿으면_작업을_돌리지_않고_알린다() throws Exception {
		given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
				.willThrow(new RedisConnectionException("연결 거부"));
		AtomicBoolean ran = new AtomicBoolean();

		assertThatThrownBy(() -> distributedLock.executeWithLock(KEY, WAIT, () -> {
			ran.set(true);
			return null;
		})).isInstanceOf(LockUnavailableException.class);

		assertThat(ran).as("돌렸다면 호출부의 폴백이 같은 작업을 한 번 더 한다").isFalse();
		assertThat(unavailable("redis_error")).isEqualTo(1);
	}

	/**
	 * Redisson은 연결을 늦게 여는 동안 여러 스레드가 몰리면 연결 실패를 {@code CompletionException}으로
	 * 감싸서 던진다. 맨 바깥만 보고 가르면 폴백하지 못하고 송금이 실패한다 —
	 * {@code RedisDownFallbackTest}에서 동시 입금 20건 중 19건이 실제로 이렇게 실패했다.
	 */
	@Test
	void 감싸져_올라와도_Redis에_못_닿은_줄_안다() throws Exception {
		given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
				.willThrow(new CompletionException(new RedisConnectionException("SENTINEL SENTINELS 응답 없음")));

		assertThatThrownBy(() -> distributedLock.executeWithLock(KEY, WAIT, () -> null))
				.as("감싼 채로 올리면 BalanceGuard가 폴백하지 못한다")
				.isInstanceOf(LockUnavailableException.class);
		assertThat(unavailable("redis_error")).isEqualTo(1);
	}

	@Test
	void 연달아_못_닿으면_회로를_열고_Redis를_부르지_않는다() throws Exception {
		given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
				.willThrow(new RedisConnectionException("연결 거부"));

		for (int i = 0; i < DistributedLock.FAILURES_TO_OPEN + 1; i++) {
			assertThatThrownBy(() -> distributedLock.executeWithLock(KEY, WAIT, () -> null))
					.isInstanceOf(LockUnavailableException.class);
		}

		verify(lock, times(DistributedLock.FAILURES_TO_OPEN)).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
		assertThat(unavailable("circuit_open"))
				.as("회로가 열린 뒤에는 연결 타임아웃을 기다리지 않고 바로 넘긴다")
				.isEqualTo(1);
	}

	@Test
	void 붐벼서_못_잡은_것은_회로를_열지_않는다() throws Exception {
		given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);
		int attempts = DistributedLock.FAILURES_TO_OPEN * 2;

		for (int i = 0; i < attempts; i++) {
			assertThatThrownBy(() -> distributedLock.executeWithLock(KEY, WAIT, () -> null))
					.isInstanceOf(LockAcquisitionException.class);
		}

		verify(lock, times(attempts)).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
		assertThat(unavailable("circuit_open"))
				.as("Redis는 답했다 — 붐빔으로 회로가 열리면 핫 계좌마다 폴백으로 샌다")
				.isZero();
	}

	@Test
	void 작업이_끝난_뒤_놓을_때_Redis가_없어도_작업은_성공이다() throws Exception {
		given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
		willThrow(new RedisConnectionException("연결 끊김")).given(lock).unlock();

		String result = distributedLock.executeWithLock(KEY, WAIT, () -> "끝났다");

		assertThat(result).as("이미 커밋된 작업을 실패로 돌려주면 안 된다").isEqualTo("끝났다");
		assertThat(release("unreachable")).isEqualTo(1);
		assertThat(release("released")).isZero();
	}
}
