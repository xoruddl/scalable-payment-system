package com.remittance.reconciliation.service;

import com.remittance.reconciliation.domain.FindingType;
import com.remittance.reconciliation.domain.ReconciliationFinding;
import com.remittance.reconciliation.domain.ReconciliationRun;
import com.remittance.reconciliation.repository.ReconciliationFindingRepository;
import com.remittance.reconciliation.repository.ReconciliationRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사 결과가 <b>메트릭으로 나가는지</b> 지킨다 (Phase 5 Step 2).
 *
 * <p>Phase 2에서 대사는 만들었지만 결과를 아는 방법이 API를 열어보는 것뿐이었다.
 * 아무도 열어보지 않으면 어긋남은 DB에만 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationMetricsTest {

	@Mock
	private ReconciliationRunRepository runRepository;

	@Mock
	private ReconciliationFindingRepository findingRepository;

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
	private ReconciliationMetrics metrics;

	@BeforeEach
	void setUp() {
		metrics = new ReconciliationMetrics(runRepository, findingRepository, meterRegistry);
		metrics.registerGauges();
	}

	private double gauge(String name) {
		return meterRegistry.get(name).gauge().value();
	}

	private double findings(FindingType type) {
		return meterRegistry.get("remittance.reconciliation.findings")
				.tag("type", type.name()).gauge().value();
	}

	private static ReconciliationRun completedRun(int accountsChecked, int findingCount) {
		ReconciliationRun run = new ReconciliationRun(Instant.now());
		run.complete(accountsChecked, findingCount, Instant.now());
		return run;
	}

	private static ReconciliationFinding finding(FindingType type) {
		return ReconciliationFinding.builder()
				.runId(1L).type(type).subject("x").detail("y").detectedAt(Instant.now()).build();
	}

	@Test
	void 어긋남을_유형별로_나눠_내보낸다() {
		metrics.record(completedRun(65, 3), List.of(
				finding(FindingType.BALANCE_MISMATCH),
				finding(FindingType.BALANCE_MISMATCH),
				finding(FindingType.UNSETTLED_TRANSFER)));

		assertThat(findings(FindingType.BALANCE_MISMATCH)).isEqualTo(2);
		assertThat(findings(FindingType.UNSETTLED_TRANSFER)).isEqualTo(1);
		// 유형별로 갈라야 무게가 구분된다 — 돈이 어긋난 것과 키가 묶인 것은 급이 다르다.
		assertThat(findings(FindingType.STRANDED_IDEMPOTENCY_KEY)).isZero();
		assertThat(gauge("remittance.reconciliation.accounts.checked")).isEqualTo(65);
	}

	@Test
	void 지난_회차의_발견이_다음_회차로_새지_않는다() {
		metrics.record(completedRun(65, 2), List.of(
				finding(FindingType.BALANCE_MISMATCH), finding(FindingType.BALANCE_MISMATCH)));

		metrics.record(completedRun(65, 0), List.of());

		// 게이지는 누적이 아니라 "지금 상태"다. 고쳐서 사라진 어긋남이 계속 남아 있으면
		// 고쳤는지 아닌지를 화면으로 판단할 수 없다.
		assertThat(findings(FindingType.BALANCE_MISMATCH)).isZero();
	}

	@Test
	void 실패한_회차는_실패했다고_알린다() {
		ReconciliationRun failed = new ReconciliationRun(Instant.now());
		failed.fail("원장 서비스를 못 읽었다", Instant.now());

		metrics.record(failed, List.of());

		// 이 값이 1이면 그 회차의 "발견 0건"은 믿을 수 없다.
		// "깨끗했다"와 "못 읽었다"를 구분하는 게 이 지표의 전부다.
		assertThat(gauge("remittance.reconciliation.last.run.failed")).isEqualTo(1);
	}

	@Test
	void 한_번도_안_돌았으면_경과_시간은_0이_아니라_NaN이다() {
		// 0으로 내면 "방금 돌았다"는 거짓말이 된다 — 이 지표가 막으려는 바로 그 오해다.
		assertThat(gauge("remittance.reconciliation.last.run.age.seconds")).isNaN();
	}

	@Test
	void 회차가_끝나면_경과_시간이_다시_흐르기_시작한다() {
		metrics.record(completedRun(65, 0), List.of());

		double age = gauge("remittance.reconciliation.last.run.age.seconds");

		assertThat(age).isNotNaN();
		// 주기가 60초이므로 이 값이 수백 초로 올라가면 배치가 멈춘 것이다.
		assertThat(age).isBetween(0.0, 5.0);
	}
}
