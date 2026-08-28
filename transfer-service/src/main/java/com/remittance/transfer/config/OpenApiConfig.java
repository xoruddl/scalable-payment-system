package com.remittance.transfer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이 서비스가 <b>자기 계약을 자기 입으로</b> 말하게 한다 (Phase 4).
 *
 * <p>공개(`/transfers`)와 내부(`/internal/*`)를 그룹으로 가른다.
 * Gateway는 <b>공개 그룹만</b> 모으므로, 대사·운영용 문이 바깥으로 새지 않는다.
 * 자세한 이유는 `account-service`의 같은 이름 설정에 적어두었다.
 */
@Configuration
public class OpenApiConfig {

	static final String INTERNAL = "/internal/**";

	@Bean
	OpenAPI transferServiceApi() {
		return new OpenAPI().info(new Info()
				.title("Transfer Service API")
				.description("송금 접수와 상태 조회. 접수는 202로 끝나고 돈은 그 뒤에 움직인다")
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
