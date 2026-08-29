package com.remittance.account.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이 서비스가 <b>자기 계약을 자기 입으로</b> 말하게 한다 (Phase 4).
 *
 * <h2>공개와 내부를 반드시 가른다 ★</h2>
 * `/internal/*`은 대사·운영 도구가 쓰는 문이라 <b>Gateway 밖으로 나가면 안 된다.</b>
 * 한 문서에 섞어두면 Gateway가 그대로 노출할 때 <b>남의 계좌 잔액을 고치는 문이 공개 API로</b>
 * 보이게 된다. 그래서 그룹을 둘로 나눈다.
 *
 * <table>
 *   <tr><th>경로</th><th>무엇</th><th>누가 본다</th></tr>
 *   <tr><td>{@code /v3/api-docs/public}</td><td>외부에 노출할 것</td><td>Gateway가 모아서 낸다</td></tr>
 *   <tr><td>{@code /v3/api-docs/internal}</td><td>{@code /internal/*}</td><td>우리만</td></tr>
 *   <tr><td>{@code /v3/api-docs}</td><td>전부</td><td>이 서비스에 직접 물었을 때</td></tr>
 * </table>
 *
 * <p><b>거르는 쪽이 기본이 아니다</b>는 점이 중요하다 — 새 `/internal` 경로를 만들면
 * 자동으로 internal 그룹에 들어가지만, 공개 그룹에서 빼는 것도 자동이다.
 * 규칙을 경로 규약에 걸어두었기 때문이다.
 */
@Configuration
public class OpenApiConfig {

	static final String INTERNAL = "/internal/**";

	@Bean
	OpenAPI accountServiceApi() {
		return new OpenAPI().info(new Info()
				.title("Account Service API")
				.description("계좌 생성·조회, 잔액 관리, 송금 Saga의 출금·입금 단계")
				.version("v1"));
	}

	@Bean
	GroupedOpenApi publicApi() {
		return GroupedOpenApi.builder()
				.group("public")
				.pathsToExclude(INTERNAL)
				.build();
	}

	@Bean
	GroupedOpenApi internalApi() {
		return GroupedOpenApi.builder()
				.group("internal")
				.pathsToMatch(INTERNAL)
				.build();
	}
}
