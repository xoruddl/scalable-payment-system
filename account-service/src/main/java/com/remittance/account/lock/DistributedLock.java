package com.remittance.account.lock;

import com.remittance.account.exception.LockAcquisitionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 기반 분산 락.
 *
 * <p>동작은 두 줄로 요약된다.
 * <ul>
 *   <li>획득: {@code SET key <내 토큰> NX PX <ttl>} — 키가 없을 때만 성공한다.</li>
 *   <li>해제: 저장된 값이 <b>내 토큰일 때만</b> 삭제한다. 단순 DEL을 쓰면, 내 작업이 늦어져
 *       TTL로 락이 풀린 뒤 다른 서버가 잡은 락을 내가 지워버릴 수 있다.
 *       비교와 삭제가 원자적이어야 하므로 Lua 스크립트로 처리한다.</li>
 * </ul>
 *
 * <p>Redisson 같은 라이브러리와 달리 <b>자동 갱신(watchdog)이 없다.</b>
 * 따라서 TTL은 임계 구역이 걸리는 최대 시간보다 넉넉해야 하고, 그보다 오래 걸리는 작업을
 * 이 락으로 감싸면 안 된다.
 *
 * <h2>왜 대기 시간을 재는가 (Phase 5 Step 2)</h2>
 * 핫 계좌 부하에서 <b>접수는 계속 202를 주고 HTTP 에러율도 안 오른다.</b> 경합은 비동기
 * 파이프라인 뒤에서 벌어지기 때문이다. 그 뒤에서 무슨 일이 나는지는 이 대기 시간이 답한다 —
 * 같은 계좌로 몰릴수록 여기가 먼저 부풀고, 대기가 {@code waitTimeout}을 넘기는 순간
 * {@code outcome=timeout}으로 떨어진다.
 *
 * <p>Phase 6에서 락을 다른 방식으로 바꿀 때 <b>무엇이 나아졌는지 말할 수 있는 근거</b>가 이 값이다.
 */
@Component
public class DistributedLock {

	/** 값이 내 토큰일 때만 삭제한다. 반환값 1 = 해제 성공, 0 = 이미 남의 락. */
	private static final String RELEASE_SCRIPT = """
			if redis.call('get', KEYS[1]) == ARGV[1] then
				return redis.call('del', KEYS[1])
			else
				return 0
			end
			""";

	private static final Duration RETRY_INTERVAL = Duration.ofMillis(50);

	private final StringRedisTemplate redisTemplate;
	private final RedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);

	/** 락을 잡기까지 기다린 시간. 잡았든 못 잡았든 잰다 — 못 잡은 쪽이 더 중요하다. */
	private final Timer acquired;
	private final Timer timedOut;

	public DistributedLock(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
		this.redisTemplate = redisTemplate;
		this.acquired = waitTimer(meterRegistry, "acquired");
		this.timedOut = waitTimer(meterRegistry, "timeout");
	}

	private static Timer waitTimer(MeterRegistry meterRegistry, String outcome) {
		return Timer.builder("remittance.lock.wait")
				.description("분산 락을 잡기까지 기다린 시간")
				// 실패 횟수는 별도 카운터를 두지 않는다 — outcome=timeout인 타이머의 count가 곧 그것이다.
				// 지표를 둘로 나누면 둘이 어긋났을 때 어느 쪽이 맞는지 알 수 없다.
				.tag("outcome", outcome)
				.register(meterRegistry);
	}

	/**
	 * {@code key} 락을 잡고 {@code action}을 실행한다.
	 *
	 * @param ttl         락 자동 만료 시간. 프로세스가 죽어도 이 시간이 지나면 풀린다.
	 * @param waitTimeout 락을 기다려보는 최대 시간. 넘기면 예외.
	 */
	public <T> T executeWithLock(String key, Duration ttl, Duration waitTimeout, Supplier<T> action) {
		String token = UUID.randomUUID().toString();
		acquire(key, token, ttl, waitTimeout);
		try {
			return action.get();
		} finally {
			release(key, token);
		}
	}

	private void acquire(String key, String token, Duration ttl, Duration waitTimeout) {
		long startedAt = System.nanoTime();
		long deadline = startedAt + waitTimeout.toNanos();
		while (true) {
			if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl))) {
				acquired.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
				return;
			}
			if (System.nanoTime() >= deadline) {
				timedOut.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
				throw new LockAcquisitionException(key, waitTimeout);
			}
			try {
				Thread.sleep(RETRY_INTERVAL.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				timedOut.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
				throw new LockAcquisitionException(key, waitTimeout);
			}
		}
	}

	private void release(String key, String token) {
		redisTemplate.execute(releaseScript, List.of(key), token);
	}
}
