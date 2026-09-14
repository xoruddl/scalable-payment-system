package com.remittance.account.config;

import com.github.dockerjava.api.DockerClient;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Redis가 답하지 않아도 /actuator/health가 오래 붙들리지 않는가 (Phase 6.7, D-006).
 *
 * 2026-09-14 홈서버에서 Redis를 끄자 /actuator/health가 60초 뒤에야 DOWN을 냈다 (gateway도 같았다).
 * health의 Redis 항목은 Redisson이 아니라 Lettuce가 본다. spring.data.redis.timeout이 없으면
 * Lettuce는 명령 하나를 60초까지 기다리고, 끊긴 동안의 명령은 재접속을 기다리며 줄을 선다.
 *
 * 컨테이너를 끄지 않고 멈춘다(pause). 끄는 쪽은 Linux에서는 재현되지만(연결 거부 → 명령이 줄을 선다),
 * Docker Desktop에서는 포트 프록시가 재접속을 받자마자 끊어 명령이 바로 실패했다. 멈추면 어디서나
 * "답이 없는 Redis"가 되고, 두 경우를 끝내는 것은 같은 설정(명령 타임아웃)이다.
 *
 * 한 번 붙은 뒤에 멈춰야 해서 이 테스트만의 Redis를 쓴다. 공용 컨테이너(AbstractIntegrationTest)는
 * 다른 테스트가 쓰므로 멈출 수 없다.
 *
 * 설정은 main의 application.yml을 직접 읽는다. 테스트용 application.yml이 main 것을 통째로 가리므로,
 * 클래스패스로 읽으면 운영 설정이 아니라 테스트 설정을 시험하게 된다.
 */
@Tag("integration")
class RedisHealthTimeoutTest {

	private static final FileSystemResource MAIN_CONFIG = new FileSystemResource("src/main/resources/application.yml");

	private static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

	@BeforeAll
	static void Redis를_띄운다() {
		REDIS.start();
	}

	@AfterAll
	static void 내린다() {
		REDIS.stop();
	}

	@Test
	void Redis가_답하지_않으면_health가_몇_초_안에_DOWN으로_답한다() throws IOException {
		PropertySource<?> mainConfig = new YamlPropertySourceLoader().load("main-application", MAIN_CONFIG).get(0);

		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
				.withInitializer(context -> context.getEnvironment().getPropertySources().addLast(mainConfig))
				.withPropertyValues(
						"spring.data.redis.host=" + REDIS.getHost(),
						"spring.data.redis.port=" + REDIS.getFirstMappedPort())
				.run(context -> 멈춰도_DOWN으로_답한다(
						new DataRedisHealthIndicator(context.getBean(RedisConnectionFactory.class))));
	}

	private void 멈춰도_DOWN으로_답한다(DataRedisHealthIndicator health) {
		// 한 번 불러 연결을 연다. 여기서 UP이 아니면 엉뚱한 Redis를 보고 있는 것이다.
		assertThat(health.health().getStatus()).isEqualTo(Status.UP);

		DockerClient docker = REDIS.getDockerClient();
		docker.pauseContainerCmd(REDIS.getContainerId()).exec();
		try {
			// 운영 설정(1초)에 여유를 더했다. 설정이 없으면 Lettuce 기본값 60초를 기다린다.
			Health afterOutage = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> health.health());
			assertThat(afterOutage.getStatus()).isEqualTo(Status.DOWN);
		} finally {
			docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
		}
	}
}
