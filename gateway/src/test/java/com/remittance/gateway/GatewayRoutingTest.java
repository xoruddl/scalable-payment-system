package com.remittance.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게이트웨이가 <b>어디로 보내고 무엇을 안 보내는가</b> (Phase 4).
 *
 * <h2>가짜 뒤쪽을 세워 쓴다</h2>
 * 진짜 서비스를 띄우면 컨테이너 넷이 필요하고, 그러면 <b>라우팅이 틀린 것</b>과
 * <b>뒤 서비스가 안 뜬 것</b>을 구분하기 어렵다. 여기서 볼 것은 라우팅뿐이므로
 * JDK에 있는 {@link HttpServer}로 <b>자기가 누구인지만 답하는</b> 서버를 둘 세운다.
 * 의존성이 하나도 늘지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingTest {

	private static HttpServer accountBackend;
	private static HttpServer ledgerBackend;
	private static final List<String> 내부로_들어온_요청 = new CopyOnWriteArrayList<>();

	@Autowired
	private WebTestClient client;

	@BeforeAll
	static void 가짜_뒤쪽을_세운다() throws IOException {
		accountBackend = 서버("account");
		ledgerBackend = 서버("ledger");
	}

	@AfterAll
	static void 내린다() {
		accountBackend.stop(0);
		ledgerBackend.stop(0);
	}

	private static final String SECRET = "routing-test-secret-that-is-32-bytes-long!";

	@DynamicPropertySource
	static void 라우트를_가짜_뒤쪽으로_돌린다(DynamicPropertyRegistry registry) {
		registry.add("ACCOUNT_URI", () -> "http://localhost:" + accountBackend.getAddress().getPort());
		registry.add("LEDGER_URI", () -> "http://localhost:" + ledgerBackend.getAddress().getPort());
		registry.add("remittance.auth.secret", () -> SECRET);
	}

	/**
	 * 라우팅을 보려면 <b>인증을 먼저 통과해야 한다</b> (Phase 4의 3/5에서 붙었다).
	 * 토큰을 안 붙이면 라우트가 맞는지와 무관하게 401이라, 여기서 보려는 것을 못 본다.
	 */
	private String 유효한_토큰() {
		try {
			return JwtAuthFilterTest.토큰("routing-test-user", java.time.Duration.ofMinutes(10), SECRET);
		} catch (Exception e) {
			throw new IllegalStateException("테스트 토큰을 못 만들었다", e);
		}
	}

	@Test
	void 계좌_조회는_account로_간다() {
		client.get().uri("/accounts/{id}/balance", "a-1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 유효한_토큰())
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class).isEqualTo("account");
	}

	@Test
	void 송금은_transfer로_간다() {
		// 이 테스트에서는 transfer 뒤쪽을 세우지 않았다. 라우트가 있으면 <b>연결 실패</b>가 나고,
		// 라우트가 아예 없으면 404가 난다. 둘을 구분하는 것이 여기서 보려는 것이다.
		client.get().uri("/transfers/{id}", "t-1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 유효한_토큰())
				.exchange()
				.expectStatus().value(status -> assertThat(status)
						.as("라우트가 없으면 404다 — 여기서는 뒤쪽이 없어서 실패해야 한다")
						.isNotEqualTo(404));
	}

	/** 인증이 라우팅보다 먼저 선다 — 토큰이 없으면 어디로 갈지 따지기 전에 막힌다. */
	@Test
	void 토큰이_없으면_라우팅까지_가지도_않는다() {
		client.get().uri("/accounts/{id}/balance", "a-1").exchange()
				.expectStatus().isUnauthorized();
	}

	/**
	 * <b>이게 이 PR에서 가장 중요한 계약이다.</b> account와 ledger가 `/accounts`로 겹치는데,
	 * 더 구체적인 원장 경로가 먼저 서지 않으면 거래내역 조회가 account로 가서 404가 된다.
	 */
	@Test
	void 겹치는_경로는_더_구체적인_쪽이_이긴다() {
		client.get().uri("/accounts/{id}/transactions", "a-1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 유효한_토큰())
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.isEqualTo("ledger");
	}

	/**
	 * <b>이 테스트만으로는 가드를 검증하지 못한다.</b> `/internal/**`에 맞는 라우트가 애초에 없어서,
	 * 가드를 꺼도 그냥 404이기 때문이다(실제로 꺼보고 확인했다).
	 *
	 * <p>그래서 여기서 확인하는 것은 <b>"지금 내부 경로가 나가지 않는다"</b>까지다.
	 * 가드 자체는 {@link InternalPathGuardTest}가 검증한다 —
	 * <b>라우트가 있어도 막느냐</b>가 거기서 갈린다.
	 */
	@Test
	void 내부_경로는_라우트가_없어_뒤쪽에_닿지_않는다() {
		내부로_들어온_요청.clear();

		client.get().uri("/internal/reconciliation/balances")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + 유효한_토큰())
				.exchange()
				.expectStatus().isNotFound();

		assertThat(내부로_들어온_요청)
				.as("뒤쪽에 닿지도 않아야 한다 — 404를 돌려주는 것만으로는 부족하다")
				.isEmpty();
	}

	private static HttpServer 서버(String 이름) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			if (exchange.getRequestURI().getPath().startsWith(InternalPathGuard.INTERNAL_PREFIX)) {
				내부로_들어온_요청.add(이름 + exchange.getRequestURI().getPath());
			}
			byte[] body = 이름.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		return server;
	}
}
