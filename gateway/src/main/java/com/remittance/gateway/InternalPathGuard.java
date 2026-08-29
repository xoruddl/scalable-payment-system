package com.remittance.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * `/internal/*`은 <b>게이트웨이를 통해 나가지 않는다</b> (Phase 4).
 *
 * <h2>왜 라우트를 안 만드는 것으로 충분하지 않나</h2>
 * 지금은 `/internal/**`에 맞는 라우트가 없어서 자연히 404다. 그런데 그건
 * <b>"막았다"가 아니라 "아직 안 열었다"</b>이다. 나중에 누가 편의를 위해 catch-all 라우트를
 * 하나 추가하면 그 순간 <b>잔액을 고치는 문이 조용히 열린다.</b>
 *
 * <p>경계는 <b>없는 것</b>에 기대면 안 되고 <b>있는 것</b>이어야 한다. 그래서 명시적으로 막는다.
 *
 * <h2>이게 진짜 경계는 아니다</h2>
 * 서비스 포트(8081~8085)는 여전히 열려 있으므로 게이트웨이를 건너뛰면 그만이다.
 * <b>진짜 차단은 네트워크에서 해야 한다</b> — Phase 8에서 NetworkPolicy로 서비스 포트를
 * 클러스터 안으로만 열면 그때 완성된다. 여기서 하는 것은
 * <b>"게이트웨이는 이 문을 열어주지 않는다"</b>는 약속까지다.
 *
 * <p>404로 답한다. 403은 <b>"거기 뭔가 있다"</b>를 알려주는 셈이라, 없는 것처럼 구는 편이 낫다.
 */
@Component
public class InternalPathGuard implements WebFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(InternalPathGuard.class);

	static final String INTERNAL_PREFIX = "/internal/";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (path.startsWith(INTERNAL_PREFIX)) {
			// 밖에서 내부 경로를 두드리는 것은 그 자체로 봐야 할 신호다.
			log.warn("게이트웨이 밖에서 내부 경로를 불렀다 - 막았다 (path={}, from={})",
					path, exchange.getRequest().getRemoteAddress());
			exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
			return exchange.getResponse().setComplete();
		}
		return chain.filter(exchange);
	}

	/**
	 * 라우팅보다 <b>먼저</b> 돈다. 뒤에 서면 라우트가 먼저 잡아 흘려보낼 수 있다.
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
}
