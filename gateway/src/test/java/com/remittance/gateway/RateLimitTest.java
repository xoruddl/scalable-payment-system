package com.remittance.gateway;

import com.redis.testcontainers.RedisContainer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 사용자가 <b>전체 용량을 다 먹지 못하게</b> 문 앞에서 막는다 (Phase 4).
 *
 * <h2>왜 이 숫자인가</h2>
 * 이 시스템의 용량은 <b>100 TPS</b>다(2026-08-29 실측, 종결 p99 4,639ms). 그 위로 받으면
 * 대기행렬이라 지연이 자릿수로 늘고 <b>모든 사용자가 같이 느려진다</b>. 그래서 한 사람이
 * 전체의 10분의 1(초당 10건)까지만 쓰게 한다.
 *
 * <h2>Redis를 실제로 띄워서 잰다</h2>
 * 인메모리 대체물로 재면 <b>여기서 확인하려는 것이 사라진다</b> — 카운터가 프로세스 밖에 있어야
 * 게이트웨이를 여러 대 띄워도 "초당 10건"이 지켜진다. 그래서 컨테이너를 띄운다.
 *
 * <h2>왜 {@code @Tag("integration")}인가</h2>
 * 컨테이너를 띄우므로 <b>Docker가 필요하다.</b> 다른 서비스의 통합 테스트는
 * {@code AbstractIntegrationTest}를 상속해 태그를 물려받는데, 이 테스트는 상속하지 않아
 * <b>태그 없이 {@code unitTest}에 섞여 있었다.</b> 그래서 "Docker 불필요"라던 {@code unitTest}가
 * Docker 없는 로컬에서 {@code Could not find a valid Docker environment}로 죽었다
 * (2026-09-05). Testcontainers를 쓰면서 태그가 없던 <b>유일한 테스트</b>였다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10s")
class RateLimitTest {

	private static final String SECRET = "rate-limit-test-secret-32-bytes-long!!";

	private static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:7-alpine"));

	private static HttpServer backend;

	@Autowired
	private WebTestClient client;

	@BeforeAll
	static void 뒤쪽과_Redis를_세운다() throws IOException {
		REDIS.start();
		backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		backend.createContext("/", exchange -> {
			byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		backend.start();
	}

	@AfterAll
	static void 내린다() {
		backend.stop(0);
		REDIS.stop();
	}

	@DynamicPropertySource
	static void 설정(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("ACCOUNT_URI", () -> "http://localhost:" + backend.getAddress().getPort());
		registry.add("remittance.auth.secret", () -> SECRET);
		// 테스트에서 쓰기 좋게 작게 잡는다. 운영 기본값(10/20)의 뜻은 같다.
		registry.add("RATE_LIMIT_PER_SECOND", () -> 1);
		registry.add("RATE_LIMIT_BURST", () -> 3);
	}

	/**
	 * <b>이 PR에서 가장 중요한 계약이다.</b> 필터가 조용히 안 걸리면 제한이 없는 것과 같은데,
	 * 그건 <b>설정 한 줄이 틀려도 아무 티가 안 난다</b>는 뜻이라 반드시 눌러봐야 안다.
	 */
	@Test
	void 몫을_다_쓰면_429로_막힌다() {
		String token = 토큰("과하게-부르는-사람");
		int 막힌_횟수 = 0;

		// burst 3이므로 처음 몇 건은 통과하고 그 뒤가 막혀야 한다.
		for (int i = 0; i < 10; i++) {
			int status = client.get().uri("/accounts/{id}", "a-1")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.exchange()
					.returnResult(String.class).getStatus().value();
			if (status == 429) {
				막힌_횟수++;
			}
		}

		assertThat(막힌_횟수)
				.as("제한이 안 걸리면 한 사람이 전체 용량을 다 먹는다")
				.isPositive();
	}

	/**
	 * 사용자별로 <b>따로</b> 센다. 합쳐 세면 남이 많이 썼다고 내가 막히는데,
	 * 그건 보호가 아니라 <b>새로운 종류의 사고</b>다.
	 */
	@Test
	void 다른_사용자의_몫은_갉아먹지_않는다() {
		String 많이_쓰는_사람 = 토큰("heavy-" + UUID.randomUUID());
		String 조용한_사람 = 토큰("quiet-" + UUID.randomUUID());

		for (int i = 0; i < 10; i++) {
			client.get().uri("/accounts/{id}", "a-1")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + 많이_쓰는_사람)
					.exchange();
		}

		client.get().uri("/accounts/{id}", "a-1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 조용한_사람)
				.exchange()
				.expectStatus().isOk();
	}

	private String 토큰(String subject) {
		try {
			return JwtAuthFilterTest.토큰(subject, Duration.ofMinutes(10), SECRET);
		} catch (Exception e) {
			throw new IllegalStateException("테스트 토큰을 못 만들었다", e);
		}
	}
}
