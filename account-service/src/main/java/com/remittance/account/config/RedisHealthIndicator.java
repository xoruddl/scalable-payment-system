package com.remittance.account.config;

import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisMaster;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSentinelMasterSlave;
import org.redisson.api.redisnode.RedisSingle;
import org.redisson.config.Config;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * /actuator/health의 Redis 항목 — 락과 같은 Redisson 클라이언트로 본다 (Phase 6.7, D-006).
 *
 * 전에는 spring-boot-starter-data-redis가 붙인 Lettuce가 봤다. 클라이언트도 설정도 락과 따로라,
 * health가 UP이어도 락이 Redis를 쓴다는 뜻이 아니었다 — {@code RedissonConfig}가 읽지 않는 키
 * (password 등)를 넣으면 health는 UP인데 락은 폴백으로 돈다. 2026-09-14에 health만 60초 붙들린 것도
 * 설정이 둘이라서였다.
 *
 * 이제 락이 쓰는 커넥션 풀로 주 노드에 PING을 보낸다. DOWN이면 락은 행 락만으로 돌고 있다(폴백).
 *
 *   단일 서버   그 서버
 *   Sentinel   지금의 주 노드 — 복제본과 Sentinel은 보지 않는다. 락은 주 노드에서만 잡는다
 *
 * readiness에는 넣지 않는다. 넣으면 Redis 하나에 account 전체가 트래픽에서 빠진다 (D-006).
 */
@Component
public class RedisHealthIndicator extends AbstractHealthIndicator {

	private final RedissonClient redisson;

	public RedisHealthIndicator(RedissonClient redisson) {
		super("Redis에 닿지 못했다 — 분산 락은 행 락만으로 돌고 있다");
		this.redisson = redisson;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		RedisMaster master = master();
		builder.withDetail("master", String.valueOf(master.getAddr()));
		if (master.ping(RedissonConfig.REDIS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
			builder.up();
			return;
		}
		builder.down();
	}

	/** 연결을 늦게 여는(lazy) Redisson은 여기서 처음 붙는다. 못 붙으면 던지고, 부모가 DOWN으로 바꾼다. */
	private RedisMaster master() {
		Config config = redisson.getConfig();
		if (config.isSentinelConfig()) {
			RedisSentinelMasterSlave sentinel = redisson.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE);
			return sentinel.getMaster();
		}
		RedisSingle single = redisson.getRedisNodes(RedisNodes.SINGLE);
		return single.getInstance();
	}
}
