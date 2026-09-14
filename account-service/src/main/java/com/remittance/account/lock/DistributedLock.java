package com.remittance.account.lock;

import com.remittance.account.exception.LockAcquisitionException;
import com.remittance.account.exception.LockUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 분산 락 — Redisson {@link RLock} (Phase 6.7, DECISIONS.md D-006).
 *
 * 왜 Redisson으로 갈아탔나 (2026-09-14)
 * Phase 2부터 {@code SET NX PX} + Lua로 직접 만들어 썼다. 한계가 둘이었다.
 *
 *   - 자동 갱신(watchdog)이 없다 — 임계 구역이 TTL보다 길면 락이 풀린 채로 진행된다
 *   - 못 잡으면 50ms마다 다시 묻는다 — 락이 5ms 만에 풀려도 50ms를 채운다 (08-31 측정 12.6%)
 *
 * Redisson은 둘 다 없앤다. 쥐고 있는 동안 watchdog이 lease를 늘리고, 풀리면 pub/sub으로 알려준다.
 * 그리고 Sentinel(장애 전환)을 설정만으로 붙일 수 있다 ({@code RedissonConfig}).
 * 측정이 시킨 교체는 아니다 — 08-31에 lost 0건이라 보류했었고(D-005), 소유자가 넣기로 정했다.
 * Sentinel은 장애 전환을 볼 때만 켜는 실험용이고 기본은 단일 Redis다(D-006). 손으로 만든 구현이 가르쳐준 것(토큰 비교 해제, 대기와 보유를 나눠 재기)은
 * 이 클래스의 지표와 {@code PROGRESS.md}에 남는다.
 *
 * Redis가 없을 때 (2026-09-14) ★
 * 이 락은 효율용이다 — 정합성은 뒤의 행 락이 지킨다(LAYERED, D-004). 그래서 Redis에 닿지 못하면
 * 기다리지 않고 {@link LockUnavailableException}을 던져, {@code BalanceGuard}가 행 락만으로
 * 진행하게 한다. 송금이 보호 장치 때문에 멈추면 안 된다.
 *
 *   - 락을 잡는 단계에서만 던진다 — 그래야 호출부가 대신 진행해도 작업이 두 번 돌지 않는다
 *   - 연속 {@value #FAILURES_TO_OPEN}번 닿지 못하면 회로를 열어 {@link #OPEN_DURATION} 동안 부르지 않는다.
 *     안 그러면 Redis가 죽은 동안 모든 요청이 연결 타임아웃을 한 번씩 기다린다
 *   - 붐벼서 못 잡은 것({@link LockAcquisitionException})은 Redis가 답한 것이라 회로에 실패로 세지 않는다
 *   - 해제 단계에서 닿지 못한 것은 삼키고 센다 — 작업은 이미 끝났다
 *
 * 무엇을 재나 — 이름은 자체 구현 때와 같다. 바꾸면 지나간 기록의 숫자와 이어지지 않는다.
 *
 *   remittance.lock.wait{outcome}        락을 잡기까지 기다린 시간 (acquired / timeout)
 *   remittance.lock.hold                 락을 쥐고 있던 시간 — 한 계좌 조각의 처리량 상한이다
 *   remittance.lock.release{outcome}     released / lost / unreachable
 *   remittance.lock.unavailable{reason}  Redis에 닿지 못해 락 없이 넘긴 횟수 (redis_error / circuit_open)
 *
 * lost의 뜻이 바뀌었다
 * 자체 구현에서 lost는 "TTL이 작업보다 먼저 끝났다"였다. 이제는 watchdog이 늘리지 못한 경우 —
 * Redis 단절이나 Sentinel 장애 전환으로 락이 사라진 경우 — 가 여기로 온다.
 */
@Component
public class DistributedLock {

	private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

	/** lease를 따로 주지 않는다는 뜻. 그러면 Redisson이 watchdog으로 쥐고 있는 동안 연장한다. */
	private static final long WATCHDOG_LEASE = -1;

	/** 연속으로 이만큼 Redis에 닿지 못하면 회로를 연다. */
	static final int FAILURES_TO_OPEN = 3;

	/**
	 * 회로를 열어 두는 시간. 지나면 한 건만 보내 Redis가 돌아왔는지 본다.
	 * Sentinel 장애 전환(down-after 3초 + 선출)을 넘길 만큼이다.
	 */
	static final Duration OPEN_DURATION = Duration.ofSeconds(5);

	private final RedissonClient redisson;
	private final MeterRegistry meterRegistry;
	private final CircuitBreaker circuit;

	/** 락을 잡기까지 기다린 시간. 잡았든 못 잡았든 잰다 — 못 잡은 쪽이 더 중요하다. */
	private final Timer acquired;
	private final Timer timedOut;

	/**
	 * 락을 쥐고 있던 시간. 이게 곧 한 계좌 조각의 처리량 상한이다 —
	 * 보유가 10ms면 그 조각은 아무리 서버를 늘려도 초당 100건을 넘지 못한다.
	 */
	private final Timer held;

	public DistributedLock(RedissonClient redisson, MeterRegistry meterRegistry) {
		this.redisson = redisson;
		this.meterRegistry = meterRegistry;
		this.circuit = newCircuit();
		this.acquired = waitTimer(meterRegistry, "acquired");
		this.timedOut = waitTimer(meterRegistry, "timeout");
		this.held = Timer.builder("remittance.lock.hold")
				.description("분산 락을 쥐고 있던 시간 — 한 계좌의 처리량 상한을 정한다")
				.register(meterRegistry);
		// 한 번도 안 찍혀도 0으로 보이게 미리 만든다 — "0건"과 "수집 안 됨"을 가르기 위해서다.
		release("released");
		release("lost");
		release("unreachable");
		unavailable("redis_error");
		unavailable("circuit_open");
	}

	private static CircuitBreaker newCircuit() {
		CircuitBreaker circuit = CircuitBreaker.of("redis-lock", CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(FAILURES_TO_OPEN)
				.minimumNumberOfCalls(FAILURES_TO_OPEN)
				.failureRateThreshold(100)
				.waitDurationInOpenState(OPEN_DURATION)
				.permittedNumberOfCallsInHalfOpenState(1)
				.build());
		circuit.getEventPublisher().onStateTransition(event ->
				log.warn("분산 락 회로 상태 전이 — Redis 없이 행 락만으로 도는지 확인하라 ({})", event.getStateTransition()));
		return circuit;
	}

	private static Timer waitTimer(MeterRegistry meterRegistry, String outcome) {
		return Timer.builder("remittance.lock.wait")
				.description("분산 락을 잡기까지 기다린 시간")
				// 실패 횟수는 별도 카운터를 두지 않는다 — outcome=timeout인 타이머의 count가 곧 그것이다.
				.tag("outcome", outcome)
				.register(meterRegistry);
	}

	/**
	 * 대기 타이머와 같은 모양으로 둔다 — 지표 하나에 결과를 태그로 붙인다.
	 * 성공과 실패를 다른 이름의 지표로 나누면 분모가 사라져서 "몇 건 중 몇 건이 새었나"를 말할 수 없다.
	 */
	private Counter release(String outcome) {
		return Counter.builder("remittance.lock.release")
				.description("분산 락 해제 결과 — lost는 락이 먼저 사라졌다는 뜻, unreachable은 놓으러 갔는데 Redis가 없었다는 뜻")
				.tag("outcome", outcome)
				.register(meterRegistry);
	}

	private Counter unavailable(String reason) {
		return Counter.builder("remittance.lock.unavailable")
				.description("Redis에 닿지 못해 분산 락을 잡지 못한 횟수 — LAYERED에서는 행 락만으로 진행한다")
				.tag("reason", reason)
				.register(meterRegistry);
	}

	/**
	 * {@code key} 락을 잡고 {@code action}을 실행한다.
	 *
	 * TTL은 받지 않는다. 쥐고 있는 동안은 watchdog이 늘리고, 프로세스가 죽으면
	 * {@code RedissonConfig.LOCK_WATCHDOG_TIMEOUT} 뒤에 풀린다.
	 *
	 * @param waitTimeout 락을 기다려보는 최대 시간. 넘기면 {@link LockAcquisitionException}.
	 * @throws LockUnavailableException Redis에 닿지 못했다. 이때 {@code action}은 실행되지 않았다.
	 */
	public <T> T executeWithLock(String key, Duration waitTimeout, Supplier<T> action) {
		RLock lock = acquire(key, waitTimeout);
		long heldFrom = System.nanoTime();
		try {
			return action.get();
		} finally {
			// 해제보다 먼저 잰다. 해제(Redis 왕복)는 임계 구역이 아니라 뒷정리다.
			held.record(System.nanoTime() - heldFrom, TimeUnit.NANOSECONDS);
			release(lock, key);
		}
	}

	private RLock acquire(String key, Duration waitTimeout) {
		if (!circuit.tryAcquirePermission()) {
			unavailable("circuit_open").increment();
			throw new LockUnavailableException(key);
		}
		long startedAt = System.nanoTime();
		Optional<RLock> lock = lockThroughCircuit(key, waitTimeout);
		if (lock.isPresent()) {
			acquired.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
			return lock.get();
		}
		timedOut.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
		throw new LockAcquisitionException(key, waitTimeout);
	}

	/**
	 * Redis가 답하면(잡았든 붐벼서 못 잡았든) 회로에 성공으로, 답하지 않으면 실패로 알린다.
	 * 붐빔을 실패로 세면 핫 계좌에서 회로가 열려, 멀쩡한 Redis를 두고 폴백으로 새게 된다.
	 *
	 * {@code getLock}도 이 안에 둔다. 연결을 늦게 여는(lazy) Redisson은 첫 사용 때 붙으므로
	 * 어느 호출에서 연결 실패가 터질지 모른다.
	 *
	 * @return 잡았으면 그 락, 붐벼서 못 잡았으면 비어 있다
	 */
	private Optional<RLock> lockThroughCircuit(String key, Duration waitTimeout) {
		long startedAt = System.nanoTime();
		try {
			RLock lock = redisson.getLock(key);
			boolean locked = tryLock(lock, waitTimeout);
			circuit.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
			return locked ? Optional.of(lock) : Optional.empty();
		} catch (RuntimeException failure) {
			throw toAcquireFailure(key, failure, startedAt);
		}
	}

	/** 잡는 단계의 실패를 가른다. Redis 탓이면 회로에 알리고 폴백할 수 있게 바꾼다. */
	private RuntimeException toAcquireFailure(String key, RuntimeException failure, long startedAt) {
		if (!isRedisFailure(failure)) {
			// Redis 탓인지 모르는 실패다. 회로를 여닫는 근거로 쓰지 않고 허가만 돌려준다.
			circuit.releasePermission();
			return failure;
		}
		circuit.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, failure);
		unavailable("redis_error").increment();
		return new LockUnavailableException(key, failure);
	}

	/**
	 * Redis에 닿지 못해 난 실패인가. 원인 사슬을 따라간다.
	 *
	 * Redisson은 연결을 늦게 여는 동안 여러 스레드가 몰리면 {@code RedisConnectionException}을
	 * {@code CompletionException}으로 감싸서 던진다. 맨 바깥만 보면 모른다 — 2026-09-14
	 * {@code RedisDownFallbackTest}에서 동시 입금 20건 중 19건이 이 모양으로 폴백하지 못하고 실패했다.
	 */
	static boolean isRedisFailure(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof RedisException) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	/** 폴링이 아니라 pub/sub으로 기다린다 — 앞사람이 놓는 순간 알림을 받는다. */
	private static boolean tryLock(RLock lock, Duration waitTimeout) {
		try {
			return lock.tryLock(waitTimeout.toMillis(), WATCHDOG_LEASE, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * 내 락일 때만 놓는다. Redisson은 다른 스레드(다른 소유자)의 락을 놓으려 하면 예외를 던진다 —
	 * 자체 구현에서 Lua로 토큰을 비교하던 것과 같은 장치다. 그 예외를 삼키지 않고 센다.
	 */
	private void release(RLock lock, String key) {
		try {
			lock.unlock();
			release("released").increment();
		} catch (IllegalMonitorStateException notMine) {
			release("lost").increment();
			log.warn("락 해제 실패 — 이미 내 락이 아니다. watchdog이 연장하지 못했다(Redis 단절·장애 전환). key={}", key);
		} catch (RuntimeException failure) {
			countUnreachableOrRethrow(key, failure);
		}
	}

	/**
	 * 작업은 이미 끝났다. Redis에 닿지 못해 못 놓은 것이라면 여기서 던지면 끝난 작업이 실패로 보인다 —
	 * 삼키고 센다. 락은 watchdog이 더 늘리지 못하므로 LOCK_WATCHDOG_TIMEOUT 뒤에 저절로 풀린다.
	 */
	private void countUnreachableOrRethrow(String key, RuntimeException failure) {
		if (!isRedisFailure(failure)) {
			throw failure;
		}
		release("unreachable").increment();
		log.warn("락을 놓으러 갔는데 Redis에 닿지 못했다 — watchdog 타임아웃 뒤 저절로 풀린다. key={}", key);
	}
}
