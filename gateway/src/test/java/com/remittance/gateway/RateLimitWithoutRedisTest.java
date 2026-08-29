package com.remittance.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * <b>Redis가 죽으면 요청을 받을 것인가</b> — 이 Phase가 미뤄둔 질문의 답 (Phase 4).
 *
 * <h2>답: 받는다 (fail-open)</h2>
 * Spring Cloud Gateway의 기본 동작이고, <b>그대로 두기로 했다.</b> 이 시스템의 다른 두 결정과
 * 나란히 놓으면 기준이 보인다.
 *
 * <table>
 *   <tr><th>없으면 무슨 일이 나나</th><th>판단</th></tr>
 *   <tr><td><b>인증</b> — 누군지 모르는 채 통과시킨다</td><td>틀린 동작. <b>fail-closed</b> (401)</td></tr>
 *   <tr><td><b>설정</b> — 다른 값으로 뜬 다른 프로세스가 된다</td>
 *       <td>틀린 동작. <b>fail-closed</b> (Phase 8에서 결정)</td></tr>
 *   <tr><td><b>제한</b> — 제한 없이 통과한다</td>
 *       <td><b>보호가 약해질 뿐</b> 동작은 맞다. <b>fail-open</b></td></tr>
 * </table>
 *
 * <p>기준은 <b>"그것이 없으면 틀린 동작이 되는가, 보호가 약해지는가"</b>다.
 * 인증 없이 통과시키면 남의 돈이 움직이고, 설정 없이 뜨면 다른 프로세스다.
 * 그런데 제한이 없어도 <b>용량 안에서는 정상으로 돈다</b> — 보호 장치 때문에 서비스가 멈추면
 * 본말전도다.
 *
 * <h2>대신 조용하면 안 된다 ★</h2>
 * fail-open의 대가는 <b>제한이 사라진 걸 아무도 모른다</b>는 것이다. 지금은 `RedisRateLimiter`가
 * ERROR 로그만 남긴다. 로그는 사람이 찾아봐야 보이므로, Redis 상태는 <b>액추에이터 health</b>로
 * 보고 Phase 10의 알림이 그걸 본다. <b>"보호가 꺼진 것"도 사고다.</b>
 *
 * <p>이 테스트는 그 동작을 <b>못 박아두는 것</b>이다. 나중에 라이브러리가 fail-closed로 바뀌면
 * 여기서 먼저 드러난다 — 모르고 넘어가면 Redis 장애가 곧 전면 중단이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10s")
class RateLimitWithoutRedisTest {

	private static final String SECRET = "no-redis-test-secret-32-bytes-long!!!!";

	private static HttpServer backend;

	@Autowired
	private WebTestClient client;

	@BeforeAll
	static void 뒤쪽을_세운다() throws IOException {
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
	}

	@DynamicPropertySource
	static void Redis를_없는_곳으로_돌린다(DynamicPropertyRegistry registry) {
		// 아무것도 듣고 있지 않은 포트. "죽은 Redis"를 이렇게 만든다.
		registry.add("spring.data.redis.port", () -> 6399);
		registry.add("ACCOUNT_URI", () -> "http://localhost:" + backend.getAddress().getPort());
		registry.add("remittance.auth.secret", () -> SECRET);
		registry.add("RATE_LIMIT_PER_SECOND", () -> 1);
		registry.add("RATE_LIMIT_BURST", () -> 1);
	}

	@Test
	void Redis가_죽어도_요청은_지나간다() throws Exception {
		String token = JwtAuthFilterTest.토큰("아무개", Duration.ofMinutes(10), SECRET);

		// 몫(초당 1건)을 훌쩍 넘겨 불러도 막히지 않는다 — 셀 수가 없기 때문이다.
		for (int i = 0; i < 5; i++) {
			client.get().uri("/accounts/{id}", "a-1")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.exchange()
					.expectStatus().isOk();
		}
	}

	@Test
	void 인증은_그대로_막는다() {
		// Redis가 죽어도 <b>인증은 fail-closed</b>다. 둘의 성격이 다르다는 것이 이 테스트의 요지다.
		client.get().uri("/accounts/{id}", "a-1").exchange()
				.expectStatus().isUnauthorized();
	}
}
