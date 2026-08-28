package com.remittance.notification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이 서비스가 <b>자기 계약을 자기 입으로</b> 말하게 한다 (Phase 4).
 *
 * <p>지금은 `/internal/*` 경로가 없어 internal 그룹이 비어 있다. <b>그래도 만들어 둔다</b> —
 * 나중에 내부 경로를 추가하는 사람이 <b>공개 문서에 섞어 넣지 않도록</b> 자리를 미리 정해두는 것이
 * 이 설정의 목적이기 때문이다. 자세한 이유는 `account-service`의 같은 이름 설정에 있다.
 */
@Configuration
public class OpenApiConfig {

	static final String INTERNAL = "/internal/**";

	@Bean
	OpenAPI notificationServiceApi() {
		return new OpenAPI().info(new Info()
				.title("Notification Service API")
				.description("송금 알림 조회. 발송은 이벤트를 받아 하고, 여기서는 읽기만 한다")
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
