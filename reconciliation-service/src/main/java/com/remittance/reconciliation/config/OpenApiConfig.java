package com.remittance.reconciliation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이 서비스가 <b>자기 계약을 자기 입으로</b> 말하게 한다 (Phase 4).
 *
 * <h2>여기는 그룹을 나누지 않는다</h2>
 * `/reconciliations`는 경로에 `internal`이 없지만 <b>전부 운영용</b>이다 — 대사를 돌리고
 * 결과를 읽는 문이라 고객에게 열 것이 하나도 없다. 그래서 <b>이 서비스는 Gateway 뒤에
 * 두지 않는다.</b> 노출을 막는 것은 문서 그룹이 아니라 <b>라우팅</b>이 할 일이고,
 * 여기서 공개 그룹을 만들면 "공개할 것이 있다"는 잘못된 신호가 된다.
 *
 * <p>다른 서비스가 그룹을 나누는 이유는 `account-service`의 같은 이름 설정에 있다.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI reconciliationServiceApi() {
		return new OpenAPI().info(new Info()
				.title("Reconciliation Service API (운영용)")
				.description("잔액–원장 대사를 돌리고 발견을 읽는다. 고객에게 열지 않는다")
				.version("v1"));
	}
}
