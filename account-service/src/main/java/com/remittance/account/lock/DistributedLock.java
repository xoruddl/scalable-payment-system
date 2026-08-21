package com.remittance.account.lock;

import com.remittance.account.exception.LockAcquisitionException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

	public DistributedLock(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
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
		long deadline = System.nanoTime() + waitTimeout.toNanos();
		while (true) {
			if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl))) {
				return;
			}
			if (System.nanoTime() >= deadline) {
				throw new LockAcquisitionException(key, waitTimeout);
			}
			try {
				Thread.sleep(RETRY_INTERVAL.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new LockAcquisitionException(key, waitTimeout);
			}
		}
	}

	private void release(String key, String token) {
		redisTemplate.execute(releaseScript, List.of(key), token);
	}
}
