package com.remittance.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이 서비스가 <b>자기 계약을 자기 입으로</b> 말하게 한다 (Phase 4).
 *
 * <p>공개(거래 내역 조회)와 내부(`/internal/*`)를 그룹으로 가른다.
 * 자세한 이유는 `account-service`의 같은 이름 설정에 적어두었다.
 *
 * <p><b>이 서비스만 WebFlux다.</b> 그래서 의존성도 `springdoc-openapi-starter-webflux-ui`인데,
 * 설정 코드는 같다 — 그룹은 라우팅 방식과 무관한 개념이기 때문이다.
 */
@Configuration
public class OpenApiConfig {

	static final String INTERNAL = "/internal/**";

	@Bean
	OpenAPI ledgerServiceApi() {
		return new OpenAPI().info(new Info()
				.title("Ledger Service API")
				.description("거래 내역(원장) 조회. 모든 잔액 변경이 분개로 남는다")
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
