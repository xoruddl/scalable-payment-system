package com.remittance.reconciliation.service;

import com.remittance.reconciliation.domain.FindingType;
import com.remittance.reconciliation.domain.ReconciliationFinding;
import com.remittance.reconciliation.domain.ReconciliationRun;
import com.remittance.reconciliation.repository.ReconciliationFindingRepository;
import com.remittance.reconciliation.repository.ReconciliationRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 대사 결과를 <b>알리는 경로</b> (Phase 5 Step 2).
 *
 * <p>Phase 2에서 대사는 만들었지만 결과를 아는 방법이 <b>API를 직접 열어보는 것</b>뿐이었다.
 * 아무도 열어보지 않으면 어긋남은 DB에만 조용히 쌓인다. 대사의 값어치는 찾아내는 데 있는 게
 * 아니라 <b>사람에게 닿는 데</b> 있다.
 *
 * <h2>가장 중요한 지표는 발견 건수가 아니라 경과 시간이다</h2>
 * {@code last.run.age.seconds}가 이 클래스의 핵심이다. 발견 건수만 보면
 * <b>"어긋난 게 없었다"와 "대사가 아예 안 돌았다"가 똑같이 0</b>이다.
 * 배치가 죽은 걸 "깨끗하다"로 오해하는 게 어긋남 자체보다 위험하다 —
 * {@code ReconciliationRun}을 회차로 남기는 이유와 같다.
 * 주기가 60초이므로 이 값이 수백 초로 올라가면 배치가 멈춘 것이다.
 *
 * <h2>스크랩할 때 DB를 읽지 않는다</h2>
 * 값은 회차가 끝날 때 한 번 갱신하고 메모리에 둔다. 게이지에서 DB를 읽으면
 * 5초마다 조회가 나가는데, <b>정작 DB가 흔들릴 때 지표까지 함께 잃는다.</b>
 * 대신 재기동하면 메모리가 비므로 기동 시 마지막 회차를 한 번 읽어 채운다 —
 * 그러지 않으면 재기동 직후 "대사가 한 번도 안 돌았다"처럼 보인다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationMetrics {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationMetrics.class);

	private final ReconciliationRunRepository runRepository;
	private final ReconciliationFindingRepository findingRepository;
	private final MeterRegistry meterRegistry;

	private final Map<FindingType, AtomicLong> findingsByType = new EnumMap<>(FindingType.class);
	private final AtomicLong accountsChecked = new AtomicLong();
	/** 1이면 마지막 회차가 끝까지 못 돌았다는 뜻 — 그 회차의 "발견 0건"을 믿으면 안 된다. */
	private final AtomicLong lastRunFailed = new AtomicLong();
	private final AtomicReference<Instant> lastFinishedAt = new AtomicReference<>();

	@EventListener(ApplicationReadyEvent.class)
	void registerGauges() {
		for (FindingType type : FindingType.values()) {
			AtomicLong holder = findingsByType.computeIfAbsent(type, ignored -> new AtomicLong());
			Gauge.builder("remittance.reconciliation.findings", holder, AtomicLong::doubleValue)
					.description("마지막 대사 회차가 찾아낸 어긋남 수")
					.tag("type", type.name())
					.register(meterRegistry);
		}
		Gauge.builder("remittance.reconciliation.accounts.checked", accountsChecked, AtomicLong::doubleValue)
				.description("마지막 대사 회차가 대조한 계좌 수")
				.register(meterRegistry);
		Gauge.builder("remittance.reconciliation.last.run.failed", lastRunFailed, AtomicLong::doubleValue)
				.description("마지막 대사 회차가 실패로 끝났는가 (1=실패)")
				.register(meterRegistry);
		Gauge.builder("remittance.reconciliation.last.run.age.seconds", this,
						ReconciliationMetrics::secondsSinceLastRun)
				.description("마지막 대사 회차가 끝난 뒤 흐른 시간. 주기(60초)보다 훨씬 크면 배치가 멈춘 것이다")
				.register(meterRegistry);

		seedFromLastRun();
	}

	/** 회차가 끝날 때마다 호출한다. 성공이든 실패든 부른다 — 실패도 알려야 할 사실이다. */
	public void record(ReconciliationRun run, List<ReconciliationFinding> findings) {
		Map<FindingType, Long> counts = new EnumMap<>(FindingType.class);
		for (ReconciliationFinding finding : findings) {
			counts.merge(finding.getType(), 1L, Long::sum);
		}
		for (FindingType type : FindingType.values()) {
			findingsByType.computeIfAbsent(type, ignored -> new AtomicLong())
					.set(counts.getOrDefault(type, 0L));
		}
		accountsChecked.set(run.getAccountsChecked());
		lastRunFailed.set(run.getFailureReason() == null ? 0 : 1);
		lastFinishedAt.set(run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now());
	}

	/**
	 * 기동 직후를 메운다. 실패해도 기동을 막지 않는다 — 지표 하나 때문에 서비스가 안 뜨는 건
	 * 본말전도다. 어차피 다음 회차(최대 60초)에 제 값으로 덮인다.
	 */
	private void seedFromLastRun() {
		try {
			runRepository.findFirstByOrderByIdDesc()
					.ifPresent(run -> record(run, findingRepository.findByRunIdOrderByIdAsc(run.getId())));
		} catch (Exception e) {
			log.warn("마지막 대사 회차를 읽지 못했다 - 다음 회차까지 지표가 비어 있다", e);
		}
	}

	/**
	 * 한 번도 돌지 않았으면 NaN을 낸다. <b>0을 내면 "방금 돌았다"는 거짓말</b>이 되고,
	 * 그게 이 지표가 막으려는 바로 그 오해다.
	 */
	private double secondsSinceLastRun() {
		Instant finishedAt = lastFinishedAt.get();
		if (finishedAt == null) {
			return Double.NaN;
		}
		return Duration.between(finishedAt, Instant.now()).toMillis() / 1000.0;
	}
}
