package com.remittance.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게이트웨이가 <b>누구의 요청인지</b>를 확인하고 뒤로 넘긴다 (Phase 4).
 *
 * <p>여기서 거는 계약은 넷이다.
 * <ol>
 *   <li>토큰이 없거나 서명이 틀리거나 만료됐으면 <b>401</b></li>
 *   <li>유효하면 통과시키고 {@code X-User-Id}에 <b>토큰의 주인</b>을 담아 넘긴다</li>
 *   <li>★ <b>밖에서 보낸 {@code X-User-Id}는 지운다</b> — 안 그러면 헤더만 붙이면 남이 된다</li>
 *   <li>헬스체크는 토큰 없이 통과한다 — 아니면 K8s가 파드를 못 살린다</li>
 * </ol>
 */
class JwtAuthFilterTest {

	private static final String SECRET = "test-secret-that-is-long-enough-32-bytes!!";

	private final JwtAuthFilter filter = new JwtAuthFilter(
			new AuthProperties(SECRET, List.of("/actuator/")));

	JwtAuthFilterTest() throws JOSEException {
	}

	@Test
	void 토큰이_없으면_거절한다() {
		MockServerWebExchange exchange = 요청("/transfers", null, null);

		filter.filter(exchange, 통과하면_기록(new AtomicReference<>())).block();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void 서명이_다르면_거절한다() throws Exception {
		String 남의_비밀로_만든_토큰 = 토큰("user-1", Duration.ofMinutes(10),
				"another-secret-that-is-also-32-bytes-long!");
		MockServerWebExchange exchange = 요청("/transfers", 남의_비밀로_만든_토큰, null);

		filter.filter(exchange, 통과하면_기록(new AtomicReference<>())).block();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void 만료된_토큰은_거절한다() throws Exception {
		// 만료를 안 보면 한 번 새어 나간 토큰이 영원히 유효하다.
		String 지난_토큰 = 토큰("user-1", Duration.ofMinutes(-1), SECRET);
		MockServerWebExchange exchange = 요청("/transfers", 지난_토큰, null);

		filter.filter(exchange, 통과하면_기록(new AtomicReference<>())).block();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void 유효한_토큰이면_주인을_헤더에_담아_넘긴다() throws Exception {
		AtomicReference<ServerWebExchange> 뒤로_간_요청 = new AtomicReference<>();
		MockServerWebExchange exchange =
				요청("/transfers", 토큰("user-1", Duration.ofMinutes(10), SECRET), null);

		filter.filter(exchange, 통과하면_기록(뒤로_간_요청)).block();

		assertThat(뒤로_간_요청.get()).isNotNull();
		assertThat(뒤로_간_요청.get().getRequest().getHeaders().getFirst(JwtAuthFilter.USER_HEADER))
				.isEqualTo("user-1");
	}

	/**
	 * <b>이게 이 필터에서 가장 중요한 계약이다.</b> 뒤 서비스는 {@code X-User-Id}를 믿을 수밖에
	 * 없으므로, 밖에서 들어온 값이 살아남으면 <b>헤더 한 줄로 남이 될 수 있다.</b>
	 */
	@Test
	void 밖에서_보낸_신원_헤더는_토큰의_주인으로_덮인다() throws Exception {
		AtomicReference<ServerWebExchange> 뒤로_간_요청 = new AtomicReference<>();
		MockServerWebExchange exchange = 요청("/transfers",
				토큰("진짜-주인", Duration.ofMinutes(10), SECRET), "훔친-남의-아이디");

		filter.filter(exchange, 통과하면_기록(뒤로_간_요청)).block();

		assertThat(뒤로_간_요청.get().getRequest().getHeaders().get(JwtAuthFilter.USER_HEADER))
				.as("위조한 값이 하나라도 남으면 안 된다")
				.containsExactly("진짜-주인");
	}

	@Test
	void 공개_경로에서도_신원_헤더는_지운다() {
		AtomicReference<ServerWebExchange> 뒤로_간_요청 = new AtomicReference<>();
		MockServerWebExchange exchange = 요청("/actuator/health", null, "훔친-남의-아이디");

		filter.filter(exchange, 통과하면_기록(뒤로_간_요청)).block();

		assertThat(뒤로_간_요청.get()).as("헬스체크는 토큰 없이 통과해야 한다").isNotNull();
		assertThat(뒤로_간_요청.get().getRequest().getHeaders().get(JwtAuthFilter.USER_HEADER))
				.as("공개 경로로 들어와 뒤에서 쓰이면 그것도 위조다")
				.isNull();
	}

	private MockServerWebExchange 요청(String path, String token, String 위조한_신원) {
		MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		if (위조한_신원 != null) {
			builder.header(JwtAuthFilter.USER_HEADER, 위조한_신원);
		}
		return MockServerWebExchange.from(builder.build());
	}

	private WebFilterChain 통과하면_기록(AtomicReference<ServerWebExchange> 자리) {
		return exchange -> {
			자리.set(exchange);
			return Mono.empty();
		};
	}

	static String 토큰(String subject, Duration 남은_시간, String secret) throws Exception {
		SignedJWT jwt = new SignedJWT(
				new JWSHeader(JWSAlgorithm.HS256),
				new JWTClaimsSet.Builder()
						.subject(subject)
						.expirationTime(Date.from(Instant.now().plus(남은_시간)))
						.build());
		jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
		return jwt.serialize();
	}
}
