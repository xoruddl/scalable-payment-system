package com.remittance.account.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * p95·p99를 계산할 수 있도록 <b>히스토그램 버킷</b>을 붙인다 (Phase 5 Step 2).
 *
 * <p>기본값으로는 {@code _count}와 {@code _sum}만 나온다. 그 둘로 구할 수 있는 건 평균뿐인데,
 * <b>평균은 꼬리를 감춘다</b> — 이 프로젝트가 보려는 건 정확히 그 꼬리다.
 * 버킷이 없으면 Grafana의 {@code histogram_quantile()}은 오류를 내지 않고
 * <b>조용히 빈 패널</b>을 낸다. 화면을 열어보기 전까지 아무도 모른다.
 *
 * <h2>왜 YAML이 아니라 코드인가</h2>
 * 처음에는 {@code application.yml}의 {@code management.metrics.distribution}에 적었다.
 * 그런데 <b>{@code src/test/resources/application.yml}이 같은 이름이라 운영 설정을 통째로 가린다.</b>
 * 그래서 테스트에서는 이 설정이 아예 존재하지 않았고, 메트릭을 검증하는 테스트를 써도
 * 정작 운영에서 무슨 값이 쓰이는지는 확인할 수 없었다.
 * 코드로 두면 컴포넌트 스캔을 타므로 테스트와 운영이 같은 설정을 쓴다.
 *
 * <h2>왜 자동 버킷이 아니라 slo인가</h2>
 * {@code percentilesHistogram(true)}는 전 구간에 수십 개의 버킷을 만들고,
 * 그게 {@code uri × status × method} 조합마다 곱해진다. 여기 적은 경계는
 * <b>"이 값을 넘으면 이상하다"</b>고 볼 지점들이다.
 * 대가는 분위수 정밀도다 — p95가 250ms와 500ms 사이면 그 구간에서 보간된 값이 나온다.
 * 절대값보다 추세를 보려는 화면이라 이쪽을 택했다.
 *
 * <h2>이름을 정확히 일치시키는 이유</h2>
 * 접두사로 매칭하면 {@code http.server.requests.active}(LongTaskTimer)까지 걸린다.
 * 실제로 {@code maximum-expected-value}를 접두사로 걸었다가 그 타이머의 기본 최솟값(120초)과
 * 충돌해 <b>요청마다</b> {@code InvalidConfigurationException}이 터진 적이 있다.
 * 기동은 멀쩡했고 헬스체크만 500을 냈다.
 *
 * <p>다섯 서비스가 같은 정책을 각자 정의한다. 공유 모듈을 두지 않기로 했으므로
 * <b>바꿀 때는 다섯 곳을 함께 확인</b>해야 한다 (이벤트 계약·에러 핸들링과 같은 규칙).
 */
@Configuration
public class MetricsDistributionConfig {

	/** 접수 지연. 10초를 넘는 HTTP 요청은 이 시스템에서 사고다. */
	private static final Duration[] HTTP_BUCKETS = buckets(5, 10, 25, 50, 100, 250, 500, 1_000, 2_000, 5_000, 10_000);

	/** 컨슈머 한 건 처리 시간. 락 대기가 길어지면 여기가 먼저 부푼다. */
	private static final Duration[] LISTENER_BUCKETS =
			buckets(5, 10, 25, 50, 100, 250, 500, 1_000, 2_000, 5_000, 10_000, 30_000);

	/** 커넥션 획득 대기. HikariCP의 타임아웃이 30초라 그보다 긴 대기는 존재할 수 없다. */
	private static final Duration[] ACQUIRE_BUCKETS = buckets(1, 5, 10, 50, 100, 500, 1_000, 5_000, 10_000, 30_000);

	private static final Map<String, Duration[]> BUCKETS_BY_METER = Map.of(
			"http.server.requests", HTTP_BUCKETS,
			"spring.kafka.listener", LISTENER_BUCKETS,
			"hikaricp.connections.acquire", ACQUIRE_BUCKETS);

	private static Duration[] buckets(long... millis) {
		Duration[] durations = new Duration[millis.length];
		for (int i = 0; i < millis.length; i++) {
			durations[i] = Duration.ofMillis(millis[i]);
		}
		return durations;
	}

	@Bean
	MeterFilter 히스토그램버킷() {
		return new MeterFilter() {
			@Override
			public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
				Duration[] slo = BUCKETS_BY_METER.get(id.getName());
				if (slo == null) {
					return config;
				}
				double[] nanos = new double[slo.length];
				for (int i = 0; i < slo.length; i++) {
					// 타이머의 기준 단위는 나노초다. 밀리초를 그대로 넘기면 버킷이 전부
					// 0에 몰려 아무것도 구분하지 못한다.
					nanos[i] = slo[i].toNanos();
				}
				return DistributionStatisticConfig.builder()
						.serviceLevelObjectives(nanos)
						.build()
						.merge(config);
			}
		};
	}
}
