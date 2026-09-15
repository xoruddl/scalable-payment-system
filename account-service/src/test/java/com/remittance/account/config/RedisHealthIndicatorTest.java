package com.remittance.account.config;

import com.github.dockerjava.api.DockerClient;
import com.redis.testcontainers.RedisContainer;
import com.remittance.account.exception.LockUnavailableException;
import com.remittance.account.lock.DistributedLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * /actuator/health의 Redis 항목이 락과 같은 말을 하는가 (Phase 6.7, D-006).
 *
 * 2026-09-15까지 health는 Lettuce가, 락은 Redisson이 봤다. 비밀번호가 걸린 Redis에
 * spring.data.redis.password를 주자 health는 UP, 락은 폴백이었다 — Lettuce는 그 키를 읽고
 * RedissonConfig는 읽지 않는다. 마지막 시험을 Lettuce health로 돌리면 실패한다.
 *
 * Redis가 답하지 않을 때는 컨테이너를 끄지 않고 멈춘다(pause). 끄는 쪽은 Linux에서는 재현되지만,
 * Docker Desktop에서는 포트 프록시가 재접속을 받자마자 끊어 명령이 바로 실패했다. 멈추면 어디서나
 * "답이 없는 Redis"가 된다 (2026-09-14, 옛 RedisHealthTimeoutTest).
 *
 * 멈추는 시험이 있어서 이 테스트만의 Redis를 쓴다. 공용 컨테이너(AbstractIntegrationTest)는
 * 다른 테스트가 쓰므로 멈출 수 없다.
 */
@Tag("integration")
class RedisHealthIndicatorTest {

	private static final DockerImageName IMAGE = DockerImageName.parse("redis:7-alpine");

	private static final RedisContainer REDIS = new RedisContainer(IMAGE);

	private static final RedisContainer PASSWORD_REDIS = new RedisContainer(IMAGE)
			.withCommand("redis-server", "--requirepass", "secret");

	@BeforeAll
	static void Redis를_띄운다() {
		REDIS.start();
		PASSWORD_REDIS.start();
	}

	@AfterAll
	static void 내린다() {
		REDIS.stop();
		PASSWORD_REDIS.stop();
	}

	@Test
	void Redis가_답하지_않으면_health가_몇_초_안에_DOWN으로_답한다() {
		withRedisson(environmentOf(REDIS), redisson -> 멈춰도_DOWN으로_답한다(new RedisHealthIndicator(redisson)));
	}

	private void 멈춰도_DOWN으로_답한다(RedisHealthIndicator health) {
		// 한 번 불러 연결을 연다. 여기서 UP이 아니면 엉뚱한 Redis를 보고 있는 것이다.
		assertThat(health.health().getStatus()).isEqualTo(Status.UP);

		DockerClient docker = REDIS.getDockerClient();
		docker.pauseContainerCmd(REDIS.getContainerId()).exec();
		try {
			// 명령 타임아웃(1초)에 여유를 더했다. Lettuce health는 타임아웃이 없을 때 60초를 기다렸다.
			Health afterOutage = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> health.health());
			assertThat(afterOutage.getStatus()).isEqualTo(Status.DOWN);
		} finally {
			docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
		}
	}

	/** 연결을 늦게 여는 Redisson은 health에서 처음 붙는다. 못 붙으면 던지지 않고 DOWN으로 답해야 한다. */
	@Test
	void 한_번도_붙지_못한_Redis면_DOWN으로_답한다() {
		MockEnvironment nowhere = new MockEnvironment()
				.withProperty("spring.data.redis.host", "127.0.0.1")
				.withProperty("spring.data.redis.port", "1");

		withRedisson(nowhere, redisson -> {
			RedisHealthIndicator health = new RedisHealthIndicator(redisson);
			Health status = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> health.health());
			assertThat(status.getStatus()).isEqualTo(Status.DOWN);
		});
	}

	@Test
	void 락이_Redis를_쓰지_못하면_health도_UP이라고_답하지_않는다() {
		MockEnvironment withPassword = environmentOf(PASSWORD_REDIS)
				.withProperty("spring.data.redis.password", "secret");

		withRedisson(withPassword, redisson -> {
			boolean healthUp = new RedisHealthIndicator(redisson).health().getStatus().equals(Status.UP);
			assertThat(healthUp).as("health가 UP인가 = 락이 Redis를 쓰는가").isEqualTo(lockWorks(redisson));
		});
	}

	private static boolean lockWorks(RedissonClient redisson) {
		DistributedLock lock = new DistributedLock(redisson, new SimpleMeterRegistry());
		try {
			return lock.executeWithLock("health-check", Duration.ofMillis(100), () -> true);
		} catch (LockUnavailableException unreachable) {
			return false;
		}
	}

	private static MockEnvironment environmentOf(RedisContainer redis) {
		return new MockEnvironment()
				.withProperty("spring.data.redis.host", redis.getHost())
				.withProperty("spring.data.redis.port", String.valueOf(redis.getFirstMappedPort()));
	}

	/** 운영과 같은 길로 만든다 — spring.data.redis.* 를 RedissonConfig가 읽는다. */
	private static void withRedisson(MockEnvironment environment, Consumer<RedissonClient> test) {
		RedissonClient redisson = Redisson.create(RedissonConfig.configFor(RedissonConfig.RedisEndpoint.from(environment)));
		try {
			test.accept(redisson);
		} finally {
			redisson.shutdown();
		}
	}
}
