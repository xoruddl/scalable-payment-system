package com.remittance.account.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정만 보고 Sentinel과 단일 서버 중 어느 쪽으로 붙는가.
 *
 * Sentinel 토폴로지는 홈서버에만 있다. 로컬과 테스트는 단일 서버라 Sentinel 경로가 한 번도
 * 안 돌 수 있다 — 그래서 "설정이 있으면 Sentinel을 고른다"는 분기만큼은 여기서 고정한다.
 * 실제로 붙는 것(장애 전환)은 홈서버 e2e의 몫이다.
 */
class RedissonConfigTest {

	@Test
	void Sentinel_설정이_없으면_단일_서버로_붙는다() throws Exception {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("spring.data.redis.host", "redis.local")
				.withProperty("spring.data.redis.port", "6380");

		Config config = RedissonConfig.configFor(RedissonConfig.RedisEndpoint.from(environment));

		assertThat(config.isSentinelConfig()).isFalse();
		assertThat(config.toYAML()).contains("redis://redis.local:6380");
	}

	@Test
	void Sentinel_master가_있으면_Sentinel로_붙는다() throws Exception {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("spring.data.redis.sentinel.master", "remittance")
				.withProperty("spring.data.redis.sentinel.nodes", "127.0.0.1:26379, 127.0.0.1:26380,127.0.0.1:26381");

		Config config = RedissonConfig.configFor(RedissonConfig.RedisEndpoint.from(environment));

		assertThat(config.isSentinelConfig()).isTrue();
		assertThat(config.toYAML())
				.contains("remittance")
				.as("공백이 섞여도 주소 셋이 모두 들어가야 한다")
				.contains("redis://127.0.0.1:26379", "redis://127.0.0.1:26380", "redis://127.0.0.1:26381");
	}

	/**
	 * Redisson 기본값(30초)이면 인스턴스 하나가 죽는 순간 그 조각이 30초 막힌다.
	 * 자체 구현의 TTL은 3초였고, 연장이 끊긴 순간은 행 락이 지키므로 짧게 둔다.
	 */
	/**
	 * Redis가 죽은 채로 재시작해도 서비스가 떠야 한다. 기동 때 연결을 시도하면 거기서 멈춰
	 * 폴백을 쓸 기회도 없다. 그리고 죽은 Redis에 요청을 오래 붙들지 않게 빨리 포기한다.
	 */
	@Test
	void Redis가_없어도_뜨고_빨리_포기한다() throws Exception {
		Config config = RedissonConfig.configFor(RedissonConfig.RedisEndpoint.from(new MockEnvironment()));

		assertThat(config.isLazyInitialization()).as("기동 때 붙으면 Redis가 죽은 채로는 못 뜬다").isTrue();
		assertThat(config.toYAML()).contains("timeout: 1000", "connectTimeout: 1000", "retryAttempts: 0");
	}

	@Test
	void 락을_쥔_프로세스가_죽으면_5초_뒤에_풀린다() {
		Config config = RedissonConfig.configFor(RedissonConfig.RedisEndpoint.from(new MockEnvironment()));

		assertThat(config.getLockWatchdogTimeout()).isEqualTo(5_000);
	}
}
