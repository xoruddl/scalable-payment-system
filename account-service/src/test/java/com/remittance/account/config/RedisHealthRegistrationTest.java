package com.remittance.account.config;

import com.remittance.account.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /actuator/health의 redis 항목을 RedisHealthIndicator가 맡는가 (Phase 6.7, D-006).
 *
 * 항목 이름은 빈 이름에서 HealthIndicator를 뗀 것이라 Lettuce 때와 같은 redis다 — 보던 사람과 스크립트가
 * 그대로 본다. spring-data-redis가 다른 의존성을 타고 되돌아와 Boot가 제 Redis health를 붙이면,
 * 이 항목이 누구 것인지 여기서 드러난다.
 */
@SpringBootTest
class RedisHealthRegistrationTest extends AbstractIntegrationTest {

	@Autowired
	private HealthContributorRegistry registry;

	@Test
	void health의_redis_항목은_락과_같은_Redisson으로_본다() {
		assertThat(registry.getContributor("redis")).isInstanceOf(RedisHealthIndicator.class);
	}
}
