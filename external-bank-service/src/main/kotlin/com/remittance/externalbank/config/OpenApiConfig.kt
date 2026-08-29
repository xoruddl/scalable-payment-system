package com.remittance.externalbank.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 이 서비스가 **자기 계약을 자기 입으로** 말하게 한다 (Phase 4).
 *
 * ## 여기는 우리 서비스가 아니다
 * 상대 은행은 **남의 시스템을 흉내 낸 것**이다. 그래서 이 문서의 독자는 고객이 아니라
 * **우리 자신**이다 — 우리가 무엇을 부를 수 있고 무엇이 돌아오는지가 곧 우리가 기대야 하는
 * 계약이고, 그 계약을 우리가 통제할 수 없다는 게 Phase 6.5의 전부였다.
 *
 * `/faults`는 **일부러 나쁘게 굴게 하는 시험용 스위치**라 실제 은행에는 없는 문이다.
 * 문서에는 남긴다 — 없는 척하면 이 서비스가 무엇인지 오해하게 된다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun externalBankApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("External Bank API (상대 은행 시뮬레이터)")
            .description(
                "transferId를 멱등성 키로 받는 입금과 거래 조회. " +
                    "/faults는 지연·타임아웃·5xx·거절을 런타임에 켜는 시험용 스위치다"
            )
            .version("v1")
    )
}
