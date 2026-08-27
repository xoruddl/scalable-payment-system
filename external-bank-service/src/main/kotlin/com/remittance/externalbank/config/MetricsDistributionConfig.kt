package com.remittance.externalbank.config

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * p95·p99를 계산할 수 있도록 **히스토그램 버킷**을 붙인다.
 *
 * <p>기본값으로는 `_count`와 `_sum`만 나온다. 그 둘로 구할 수 있는 건 평균뿐인데,
 * **평균은 꼬리를 감춘다** — 이 프로젝트가 보려는 건 정확히 그 꼬리다.
 * 버킷이 없으면 Grafana의 `histogram_quantile()`은 오류를 내지 않고
 * **조용히 빈 패널**을 낸다.
 *
 * <p>다른 다섯 서비스에 같은 정책이 Java로 있다. **여섯 번째 복사본이고, 언어만 다르다** —
 * 공유 모듈을 두지 않기로 했으므로 바꿀 때는 여섯 곳을 함께 확인해야 한다.
 * 언어가 달라도 정책은 같아야 한다는 것이 오히려 이 저장소가 확인하려던 것이기도 하다.
 *
 * <h2>이 서비스만 버킷이 다르다</h2>
 * 여기는 **일부러 느리게 답하는 상대**다. 응답을 삼킬 때 30초를 매달아 두므로,
 * 다른 서비스의 상한(10초)으로는 그 구간이 전부 마지막 버킷에 뭉친다.
 * "느린 상대가 얼마나 느린가"를 봐야 하는 서비스라 위쪽을 더 잡는다.
 */
@Configuration
class MetricsDistributionConfig {

	@Bean
	fun 히스토그램버킷(): MeterFilter = object : MeterFilter {
		override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig {
			val slo = BUCKETS_BY_METER[id.name] ?: return config
			return DistributionStatisticConfig.builder()
				// 타이머의 기준 단위는 나노초다. 밀리초를 그대로 넘기면 버킷이 전부
				// 0에 몰려 아무것도 구분하지 못한다.
				.serviceLevelObjectives(*slo.map { it.toNanos().toDouble() }.toDoubleArray())
				.build()
				.merge(config)
		}
	}

	companion object {
		/** 응답 지연. 30초 매달아 두는 구간까지 봐야 한다. */
		private val HTTP_BUCKETS =
			buckets(5, 10, 25, 50, 100, 250, 500, 1_000, 2_000, 5_000, 10_000, 30_000, 60_000)

		/** 커넥션 획득 대기. HikariCP의 타임아웃이 30초라 그보다 긴 대기는 존재할 수 없다. */
		private val ACQUIRE_BUCKETS = buckets(1, 5, 10, 50, 100, 500, 1_000, 5_000, 10_000, 30_000)

		private val BUCKETS_BY_METER = mapOf(
			// 이름을 정확히 일치시킨다. 접두사로 매칭하면 http.server.requests.active
			// (LongTaskTimer)까지 걸려 요청마다 예외가 터진다 — 실제로 겪었다.
			"http.server.requests" to HTTP_BUCKETS,
			"hikaricp.connections.acquire" to ACQUIRE_BUCKETS,
		)

		private fun buckets(vararg millis: Long): List<Duration> = millis.map(Duration::ofMillis)
	}
}
