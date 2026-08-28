package com.remittance.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 게이트웨이가 토큰을 어떻게 검증하는가 (Phase 4).
 *
 * @param secret     HS256 서명 검증에 쓰는 대칭키. <b>32바이트 이상</b>이어야 한다.
 *                   ⚠️ 대칭키는 <b>검증하는 쪽과 발급하는 쪽이 같은 비밀을 나눠 갖는다</b>는 뜻이다.
 *                   발급자를 따로 만들지 않기로 해서 이렇게 갔고, 발급자가 생기면
 *                   비대칭(RS256) + JWKS로 가야 한다 — 그때는 게이트웨이가 <b>공개키만</b> 안다.
 * @param publicPaths 토큰 없이 통과시킬 경로. 프로브와 지표 수집이 여기 해당한다 —
 *                   헬스체크에 토큰을 요구하면 <b>쿠버네티스가 파드를 못 살린다.</b>
 */
@ConfigurationProperties(prefix = "remittance.auth")
public record AuthProperties(
		String secret,
		List<String> publicPaths
) {

	public AuthProperties {
		publicPaths = publicPaths != null ? publicPaths : List.of("/actuator/");
	}

	boolean isPublic(String path) {
		return publicPaths.stream().anyMatch(path::startsWith);
	}
}
