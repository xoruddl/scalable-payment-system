package com.remittance.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>가드가 실제로 막는가</b> — 라우트가 있든 없든 (Phase 4).
 *
 * <h2>왜 통합 테스트로는 부족한가</h2>
 * `GatewayRoutingTest`에서 `/internal/...`이 404가 나는 것을 확인했지만,
 * <b>가드를 꺼도 똑같이 404였다.</b> 맞는 라우트가 애초에 없어서다.
 * 즉 그 테스트가 검증한 것은 "지금 안 열려 있다"이지 <b>"막았다"가 아니었다.</b>
 *
 * <p>가드의 존재 이유는 <b>나중에 누가 catch-all 라우트를 추가했을 때</b>다.
 * 그 상황을 재현하려면 필터를 직접 부르고 <b>체인이 이어지는지</b>를 봐야 한다 —
 * 체인이 곧 "뒤로 흘려보낸다"이기 때문이다.
 */
class InternalPathGuardTest {

	private final InternalPathGuard guard = new InternalPathGuard();

	@Test
	void 내부_경로는_뒤로_흘려보내지_않는다() {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/internal/accounts/a-1/credit"));
		AtomicBoolean 흘려보냈나 = new AtomicBoolean(false);

		guard.filter(exchange, 체인(흘려보냈나)).block();

		assertThat(흘려보냈나)
				.as("라우트가 있었다면 여기서 잔액을 고치는 문이 열린다")
				.isFalse();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void 공개_경로는_그대로_흘려보낸다() {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/accounts/a-1/balance"));
		AtomicBoolean 흘려보냈나 = new AtomicBoolean(false);

		guard.filter(exchange, 체인(흘려보냈나)).block();

		assertThat(흘려보냈나).isTrue();
	}

	/**
	 * `/internal`로 <b>시작하는 다른 이름</b>은 막지 않는다.
	 * 접두사를 `/internal`이 아니라 `/internal/`로 잡은 이유가 이것이다 —
	 * 나중에 `/internal-transfers` 같은 공개 경로가 생겨도 조용히 404가 되면 안 된다.
	 */
	@Test
	void 이름이_비슷할_뿐인_경로는_막지_않는다() {
		MockServerWebExchange exchange = MockServerWebExchange.from(
				MockServerHttpRequest.get("/internal-transfers/t-1"));
		AtomicBoolean 흘려보냈나 = new AtomicBoolean(false);

		guard.filter(exchange, 체인(흘려보냈나)).block();

		assertThat(흘려보냈나).isTrue();
	}

	private WebFilterChain 체인(AtomicBoolean 흘려보냈나) {
		return exchange -> {
			흘려보냈나.set(true);
			return Mono.empty();
		};
	}
}
