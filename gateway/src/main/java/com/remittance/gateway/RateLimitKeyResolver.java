package com.remittance.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * <b>무엇을 기준으로 셀 것인가</b> — 사용자별로 센다 (Phase 4).
 *
 * <h2>왜 사용자인가</h2>
 * 로드맵에는 이 항목의 근거가 *"핫 계좌 보호"*로 적혀 있었다. 그런데 그러려면 게이트웨이가
 * <b>요청 본문을 열어 받는 계좌를 봐야 한다.</b> 그러면 게이트웨이가 도메인을 알게 되고
 * 본문을 버퍼링하느라 논블로킹 이점도 깎인다. 게다가 <b>핫 계좌는 Phase 6에서 잔액 샤딩으로
 * 이미 풀었다</b>(25 → 70 TPS).
 *
 * <p>그래서 <b>게이트웨이가 이미 아는 것</b>으로만 판단한다 — 토큰에서 꺼내 넣은
 * {@code X-User-Id}다. 본문을 열지 않고, 방금 만든 인증이 여기서 값을 한다.
 *
 * <h2>헤더가 없으면 통과시키지 않는다</h2>
 * 이 헤더는 {@link JwtAuthFilter}가 <b>지우고 다시 넣는다.</b> 그러니 라우팅까지 온 요청에
 * 이게 없다는 것은 인증을 지나오지 않았다는 뜻이고, 그건 있을 수 없는 상태다.
 * 그때 <b>키를 비워 통과시키면 제한이 없는 구멍</b>이 되므로, 대신 <b>공용 키 하나</b>로 묶는다 —
 * 정체를 모르는 요청 전부가 한 바구니에서 제한을 나눠 쓴다.
 */
@Component
public class RateLimitKeyResolver implements KeyResolver {

	/** 정체를 모르는 요청이 담기는 바구니. 여기 담기면 서로의 몫을 갉아먹는다 — 그게 의도다. */
	static final String UNKNOWN = "unknown";

	@Override
	public Mono<String> resolve(ServerWebExchange exchange) {
		String userId = exchange.getRequest().getHeaders().getFirst(JwtAuthFilter.USER_HEADER);
		return Mono.just(userId != null && !userId.isBlank() ? userId : UNKNOWN);
	}
}
