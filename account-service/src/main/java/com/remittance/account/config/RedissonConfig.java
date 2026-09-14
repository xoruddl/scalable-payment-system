package com.remittance.account.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.BaseConfig;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;

/**
 * 잔액 분산 락이 붙을 Redis (Phase 6.7, DECISIONS.md D-006).
 *
 * 설정 키는 Spring Boot의 {@code spring.data.redis.*}를 그대로 읽는다. gateway(Lettuce)도 같은 키로
 * 붙으므로, 환경마다 한 곳만 바꾸면 두 서비스가 같은 Redis를 본다.
 *
 *   sentinel.master가 있으면  Sentinel 모드 — 주 노드가 죽으면 Sentinel이 복제본을 올리고,
 *                             클라이언트는 Sentinel에게 새 주 노드를 물어 옮겨 간다 (장애 전환 실험 때)
 *   없으면                    단일 서버 — host:port 하나 (기본)
 *
 * 기본이 단일 서버인 이유: 잔액 락은 Redis에 닿지 못하면 행 락만으로 진행하고(폴백), 게이트웨이 요청
 * 제한은 Redis가 없으면 통과시킨다(fail-open). 둘 다 Redis 없이 맞게 돌므로 노드 다섯을 상시로 둘
 * 이유가 없다 (D-006).
 *
 * 코드 경로는 환경과 무관하게 같다. 로컬에서 안 도는 것은 Sentinel 토폴로지 자체이고,
 * 어느 모드를 고르는지는 {@code RedissonConfigTest}가 고정한다.
 */
@Configuration
public class RedissonConfig {

	/**
	 * 락을 쥔 프로세스가 죽었을 때 락이 풀리기까지의 시간. watchdog은 이것의 1/3마다 연장한다.
	 *
	 * Redisson 기본값은 30초다. 그대로 두면 한 인스턴스가 죽는 순간 그 계좌 조각이 30초 동안
	 * 막힌다 — 자체 구현의 TTL은 3초였다. 길게 잡을 이유가 연장이 끊겨도 버티는 것뿐인데,
	 * 끊긴 순간은 행 락이 지키므로(LAYERED) 짧게 둔다. 임계 구역은 수십 ms라 연장이 거의 안 일어난다.
	 */
	static final Duration LOCK_WATCHDOG_TIMEOUT = Duration.ofSeconds(5);

	/** 명령 하나 · 연결 하나를 기다리는 시간. 넘기면 포기하고 폴백으로 간다. */
	static final Duration REDIS_TIMEOUT = Duration.ofSeconds(1);

	@Bean(destroyMethod = "shutdown")
	RedissonClient redissonClient(Environment environment) {
		return Redisson.create(configFor(RedisEndpoint.from(environment)));
	}

	static Config configFor(RedisEndpoint endpoint) {
		Config config = new Config();
		config.setLockWatchdogTimeout(LOCK_WATCHDOG_TIMEOUT.toMillis());
		// Redis 없이도 뜬다. 기본값은 기동할 때 연결해 보고, 못 붙으면 서비스가 아예 안 뜬다 —
		// Redis가 죽은 채로 재시작하면 폴백을 쓸 기회도 없이 송금이 멈춘다. 첫 사용 때 붙는다.
		config.setLazyInitialization(true);
		if (endpoint.usesSentinel()) {
			failFast(config.useSentinelServers())
					.setMasterName(endpoint.sentinel().master())
					.addSentinelAddress(endpoint.sentinel().addresses())
					// Redisson 기본값은 복제본에서 읽기다. 락의 상태를 복제본에서 읽으면 비동기 복제만큼
					// 늦은 값을 본다 — 락은 읽기도 주 노드에서 한다.
					.setReadMode(ReadMode.MASTER);
			return config;
		}
		failFast(config.useSingleServer()).setAddress(endpoint.singleAddress());
		return config;
	}

	/**
	 * Redis가 죽었을 때 요청을 오래 붙들지 않는다.
	 *
	 * 기본값은 명령마다 3초를 기다리고 여러 번 다시 보낸다. 폴백(행 락만으로 진행)이 있으므로
	 * 오래 버티는 것보다 빨리 포기하고 넘어가는 편이 낫다. 연달아 실패하면 그마저 부르지 않는다
	 * ({@code DistributedLock}의 회로).
	 */
	private static <T extends BaseConfig<T>> T failFast(T server) {
		return server
				.setTimeout((int) REDIS_TIMEOUT.toMillis())
				.setConnectTimeout((int) REDIS_TIMEOUT.toMillis())
				.setRetryAttempts(0);
	}

	/** {@code spring.data.redis.*}에서 읽은 접속 정보. */
	record RedisEndpoint(String host, int port, Sentinel sentinel) {

		static RedisEndpoint from(Environment environment) {
			Binder binder = Binder.get(environment);
			String master = binder.bind("spring.data.redis.sentinel.master", String.class).orElse("");
			// 쉼표로 이은 한 줄이든 YAML 목록이든 받는다.
			List<String> nodes = binder.bind("spring.data.redis.sentinel.nodes", Bindable.listOf(String.class))
					.orElse(List.of());
			return new RedisEndpoint(
					binder.bind("spring.data.redis.host", String.class).orElse("localhost"),
					binder.bind("spring.data.redis.port", Integer.class).orElse(6379),
					new Sentinel(master, nodes));
		}

		boolean usesSentinel() {
			return !sentinel.master().isBlank();
		}

		String singleAddress() {
			return "redis://" + host + ":" + port;
		}
	}

	/** Sentinel 모드일 때만 쓴다. master가 비어 있으면 단일 서버다. */
	record Sentinel(String master, List<String> nodes) {

		String[] addresses() {
			return nodes.stream()
					.map(String::trim)
					.map(node -> "redis://" + node)
					.toArray(String[]::new);
		}
	}
}
