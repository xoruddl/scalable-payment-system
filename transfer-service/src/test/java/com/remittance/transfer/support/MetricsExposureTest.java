package com.remittance.transfer.support;

import com.remittance.transfer.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드가 기대는 메트릭이 <b>실제로 노출되는지</b> 지킨다 (Phase 5 Step 2).
 *
 * <p>이 테스트가 생긴 이유가 둘 다 실제로 겪은 일이다.
 *
 * <p><b>하나 — 히스토그램을 켜지 않으면 p95는 영원히 안 나온다.</b>
 * 기본값으로는 {@code _count}와 {@code _sum}만 나온다. 그 둘로는 평균밖에 못 구하는데,
 * Grafana의 {@code histogram_quantile()}은 버킷이 없으면 오류를 내지 않고 <b>조용히 빈 패널</b>을
 * 낸다. 화면을 열어보기 전까지 아무도 모른다.
 *
 * <p><b>둘 — 잘못된 분포 설정은 요청 자체를 죽인다.</b>
 * {@code maximum-expected-value: 10s}를 걸었더니 같은 이름 접두사를 쓰는 LongTaskTimer
 * ({@code http.server.requests.active})의 기본 최솟값(120초)과 충돌해,
 * {@code InvalidConfigurationException}이 <b>요청마다</b> 터졌다. 기동은 멀쩡히 됐고
 * 헬스체크만 500을 냈다. 기존 테스트는 하나도 빨개지지 않았는데,
 * <b>진짜 포트로 요청을 보내는 테스트가 없어서</b>였다 — 예외는 서블릿 필터
 * ({@code ServerHttpObservationFilter})에서 터지므로 MockMvc로는 재현되지 않는다.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		// 엔드포인트 노출은 여기서 직접 켠다. 운영의 application.yml에도 켜져 있지만,
		// 테스트에서는 같은 이름의 src/test/resources/application.yml이 그걸 통째로 가려서
		// 이 테스트만으로는 운영 노출 설정을 확인할 수 없다.
		// 노출이 실제로 살아 있는지는 Prometheus의 수집 대상 상태(up) 패널이 답한다.
		properties = "management.endpoints.web.exposure.include=health,prometheus")
class MetricsExposureTest extends AbstractIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private KafkaListenerEndpointRegistry listenerRegistry;

	private HttpResponse<String> get(String path) throws Exception {
		return HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void 분포_설정이_잘못되면_요청이_죽으므로_진짜_포트로_때려본다() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertThat(response.statusCode())
				.as("500이면 대개 메트릭 분포 설정이 유효하지 않은 것이다 — 로그의 InvalidConfigurationException을 보라")
				.isEqualTo(200);
	}

	/**
	 * 대시보드의 p95·p99 패널이 이 세 타이머에 걸려 있다. 이름이 바뀌거나 slo 설정이 빠지면
	 * 여기서 먼저 잡힌다.
	 *
	 * <p>메트릭을 하나 만들어 보고 버킷이 붙는지 확인한다. 실제 요청을 기다리지 않아도
	 * <b>"이 이름에 분포 설정이 걸려 있는가"</b>는 이걸로 답할 수 있다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"http.server.requests", "spring.kafka.listener", "hikaricp.connections.acquire"})
	void 대시보드가_쓰는_타이머에는_히스토그램_버킷이_붙는다(String meterName) {
		Timer timer = Timer.builder(meterName).register(meterRegistry);
		HistogramSnapshot snapshot = timer.takeSnapshot();

		assertThat(snapshot.histogramCounts())
				.as("%s에 버킷이 없다 — application.yml의 management.metrics.distribution.slo를 확인하라. "
						+ "버킷이 없으면 histogram_quantile()이 조용히 빈 패널을 낸다", meterName)
				.isNotEmpty();
	}

	/**
	 * 리스너 지표의 {@code name} 라벨은 <b>컨테이너 빈 이름</b>이 그대로 쓰인다.
	 * {@code @KafkaListener}에 {@code id}를 주지 않으면
	 * {@code org.springframework.kafka.KafkaListenerEndpointContainer#0-0}이 되는데,
	 * 그걸로는 <b>어느 토픽이 느린지 화면에서 읽을 수 없다.</b>
	 *
	 * <p>라벨은 <b>baseline을 재기 전에</b> 확정해야 한다. 나중에 바꾸면 시계열이 갈라져
	 * Phase 6의 재측정을 baseline과 같은 잣대로 비교할 수 없다.
	 */
	@Test
	void 리스너_지표가_어느_토픽인지_읽을_수_있다() {
		assertThat(listenerRegistry.getListenerContainers())
				.isNotEmpty()
				.allSatisfy(container -> assertThat(container.getListenerId())
						.as("id를 안 주면 라벨이 컨테이너 빈 이름이 되어 어느 토픽인지 못 읽는다")
						.doesNotContain("KafkaListenerEndpointContainer")
						.startsWith("transfer."));
	}

	@Test
	void prometheus_엔드포인트가_버킷을_실제로_내보낸다() throws Exception {
		// 스크랩되는 것은 레지스트리가 아니라 이 엔드포인트의 본문이다. 거기까지 확인해야 한다.
		get("/actuator/health");

		String body = get("/actuator/prometheus").body();

		assertThat(body)
				.as("Prometheus가 긁어가는 본문에 버킷이 없으면 대시보드는 비어 있다")
				.contains("http_server_requests_seconds_bucket");
	}
}
