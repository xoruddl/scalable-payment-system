package com.remittance.account;

import com.redis.testcontainers.RedisContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.utility.DockerImageName;

/**
 * 분산 락이 필요한 테스트의 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 * DB는 H2를 그대로 쓰고 Redis만 컨테이너로 띄운다.
 *
 * <p><b>싱글턴 컨테이너 패턴</b>을 쓴다. {@code @Testcontainers} + {@code @Container} 조합은
 * <b>테스트 클래스가 끝날 때마다 컨테이너를 멈추기 때문에</b>, 이 베이스를 상속한 두 번째 클래스부터는
 * 이미 죽은 컨테이너를 붙잡고 "Unable to connect" 로 실패한다.
 * static 블록에서 한 번만 띄우고 JVM이 끝날 때 Ryuk이 정리하도록 맡긴다.
 *
 * <p>로컬 Redis(docker compose)에 실수로 붙는 것도 막아준다 — 포트를 컨테이너 것으로 덮어쓰기 때문.
 */
public abstract class AbstractRedisIntegrationTest {

	private static final RedisContainer REDIS_CONTAINER =
			new RedisContainer(DockerImageName.parse("redis:7-alpine"));

	static {
		REDIS_CONTAINER.start();
	}

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
		registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);
	}
}
