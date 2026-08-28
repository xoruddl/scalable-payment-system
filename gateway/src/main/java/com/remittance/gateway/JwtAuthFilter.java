package com.remittance.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Date;

/**
 * 누가 보낸 요청인지 <b>게이트웨이에서 한 번만</b> 확인한다 (Phase 4).
 *
 * <h2>여기서 하는 것과 안 하는 것</h2>
 * 토큰을 <b>발급하지 않는다.</b> 검증만 한다 — 이 Phase의 주제가 "게이트웨이가 횡단 관심사를
 * 한 곳에서 처리한다"이지 "인증 서비스를 만든다"가 아니기 때문이다. 발급자가 생기면
 * 비대칭 키와 JWKS로 갈아탄다.
 *
 * <h2>★ 들어온 {@code X-User-Id}는 무조건 지운다</h2>
 * 토큰에서 꺼낸 값으로 <b>덮어쓰는 게 아니라, 먼저 지우고 나서 넣는다.</b> 공개 경로에서도 지운다.
 * 안 그러면 밖에서 헤더를 그냥 붙여 보내는 것만으로 <b>남이 될 수 있다.</b>
 * 뒤 서비스는 이 헤더를 믿을 수밖에 없으므로, 믿을 수 있게 만드는 것이 여기 책임이다.
 *
 * <h2>그런데 이것도 진짜 경계는 아니다</h2>
 * 서비스 포트가 열려 있는 한 게이트웨이를 건너뛰고 헤더를 위조하면 그만이다.
 * `/internal` 문제와 <b>같은 뿌리이고 답도 같다</b> — Phase 8의 NetworkPolicy로
 * 서비스가 게이트웨이 말고는 아무에게도 대답하지 않게 될 때 완성된다.
 * 여기서 하는 것은 <b>"게이트웨이를 통과한 요청의 신원은 진짜다"</b>까지다.
 */
@Component
public class JwtAuthFilter implements WebFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

	static final String USER_HEADER = "X-User-Id";
	private static final String BEARER = "Bearer ";

	private final AuthProperties properties;
	private final MACVerifier verifier;

	public JwtAuthFilter(AuthProperties properties) throws JOSEException {
		this.properties = properties;
		this.verifier = new MACVerifier(properties.secret().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();

		if (properties.isPublic(path)) {
			// 공개 경로에서도 신원 헤더는 지운다. 여기로 들어와 뒤에서 쓰이면 그것도 위조다.
			return chain.filter(신원을_지운다(exchange));
		}

		String token = 토큰을_꺼낸다(exchange);
		if (token == null) {
			return 거절한다(exchange, "토큰이 없다", path);
		}

		String userId;
		try {
			userId = 검증하고_주인을_읽는다(token);
		} catch (ParseException | JOSEException | IllegalStateException invalid) {
			return 거절한다(exchange, invalid.getMessage(), path);
		}

		return chain.filter(신원을_붙인다(exchange, userId));
	}

	/** 서명·만료·주인을 본다. 셋 중 하나라도 틀리면 통과시키지 않는다. */
	private String 검증하고_주인을_읽는다(String token) throws ParseException, JOSEException {
		SignedJWT jwt = SignedJWT.parse(token);
		if (!jwt.verify(verifier)) {
			throw new IllegalStateException("서명이 맞지 않는다");
		}

		JWTClaimsSet claims = jwt.getJWTClaimsSet();
		Date expiresAt = claims.getExpirationTime();
		if (expiresAt == null || expiresAt.before(new Date())) {
			// 만료를 안 보면 한 번 새어 나간 토큰이 영원히 유효하다.
			throw new IllegalStateException("만료됐거나 만료 시각이 없다");
		}

		String subject = claims.getSubject();
		if (subject == null || subject.isBlank()) {
			throw new IllegalStateException("누구의 토큰인지 없다");
		}
		return subject;
	}

	private String 토큰을_꺼낸다(ServerWebExchange exchange) {
		String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()) : null;
	}

	private ServerWebExchange 신원을_지운다(ServerWebExchange exchange) {
		return exchange.mutate()
				.request(request -> request.headers(headers -> headers.remove(USER_HEADER)))
				.build();
	}

	private ServerWebExchange 신원을_붙인다(ServerWebExchange exchange, String userId) {
		return exchange.mutate()
				.request(request -> request.headers(headers -> headers.set(USER_HEADER, userId)))
				.build();
	}

	private Mono<Void> 거절한다(ServerWebExchange exchange, String 이유, String path) {
		// 왜 막혔는지는 로그에만 남긴다. 응답에 담으면 토큰을 맞춰보는 데 쓰인다.
		log.debug("인증 실패로 막았다 (path={}, 이유={})", path, 이유);
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return exchange.getResponse().setComplete();
	}

	/**
	 * {@link InternalPathGuard} <b>다음</b>에 돈다.
	 *
	 * <p>순서가 뜻을 갖는다 — 내부 경로는 <b>토큰이 유효해도</b> 나가면 안 된다.
	 * 인증이 먼저 서면 "토큰만 있으면 내부 경로도 되는" 것처럼 읽힌다.
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}
}
