package com.remittance.gateway;

import com.github.dockerjava.api.DockerClient;
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

/**
 * 붙어 있던 Redis가 답하지 않아도 요청이 오래 붙들리지 않는가 (Phase 6.7, D-006).
 *
 * {@code RateLimitWithoutRedisTest}는 처음부터 없는 Redis를 본다. 연결을 못 여는 것은 바로 실패하고
 * 요청은 fail-open으로 지나간다. 그런데 운영에서 Redis가 죽는 것은 붙어 있다가 끊기는 쪽이다.
 * 그때 Lettuce는 명령을 재접속까지 줄 세우고, spring.data.redis.timeout이 없으면 60초를 기다린다 —
 * fail-open이 되기 전에 요청이 먼저 붙들린다. 2026-09-14 홈서버에서 Redis를 끄자 gateway의
 * /actuator/health가 60초 뒤에야 답했다. 그 장애 시험은 부하를 서비스에 바로 걸어 게이트웨이를
 * 지나지 않았으므로, 요청 경로는 여기서 본다.
 *
 * 끄지 않고 멈추는(pause) 이유는 {@code RedisHealthTimeoutTest}(account)에 적었다 — Docker Desktop에서는
 * 끄면 재현되지 않는다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10s")
class RateLimitRedisDisconnectTest {

	private static final String SECRET = "disconnect-test-secret-32-bytes-long!!";

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
	}

	@Test
	void Redis가_답하지_않아도_요청은_몇_초_안에_지나간다() throws Exception {
		String token = JwtAuthFilterTest.토큰("아무개", Duration.ofMinutes(10), SECRET);

		// 한 번 불러 연결을 연다. 요청 제한이 Redis를 부르는 순간 붙는다.
		client.get().uri("/accounts/{id}", "a-1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange()
				.expectStatus().isOk();

		DockerClient docker = REDIS.getDockerClient();
		docker.pauseContainerCmd(REDIS.getContainerId()).exec();
		try {
			// 운영 설정(1초)에 여유를 더했다. 설정이 없으면 Lettuce 기본값 60초를 기다린다.
			client.mutate().responseTimeout(Duration.ofSeconds(3)).build()
					.get().uri("/accounts/{id}", "a-1")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.exchange()
					.expectStatus().isOk();
		} finally {
			docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
		}
	}
}
