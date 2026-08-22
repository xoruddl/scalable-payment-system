package com.remittance.account.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox에 <b>아직 발행되지 않고 쌓인 이벤트 수</b>를 내보낸다 (Phase 5 Step 2).
 *
 * <p>이 숫자가 없으면 릴레이가 부하를 못 따라가는 순간을 <b>바깥에서 볼 방법이 없다.</b>
 * 접수는 계속 202를 주고 HTTP 에러율도 안 오르는데 돈만 안 움직이는 상태가 되기 때문이다.
 * k6가 재는 "종결 지연"이 늘어나는 건 보이지만, <b>어디서</b> 늘어나는지는 이 값이 답한다.
 *
 * <ul>
 *   <li>0 근처에서 머문다 → 릴레이가 따라가고 있다. 병목은 그 뒤(Kafka·컨슈머·DB)에 있다.</li>
 *   <li>계속 올라간다 → 릴레이가 못 따라간다. 폴링 간격(500ms)과 배치 크기(100)가 상한이다.</li>
 * </ul>
 */
@Component
public class OutboxBacklogMetrics {

	private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);

	public OutboxBacklogMetrics(OutboxEventRepository outboxEventRepository, MeterRegistry meterRegistry) {
		Gauge.builder("remittance.outbox.backlog", () -> backlog(outboxEventRepository))
				.description("아직 발행되지 않은 Outbox 이벤트 수")
				.register(meterRegistry);
	}

	/**
	 * 게이지 값은 <b>스크랩 시점에</b> 계산된다. 즉 이 메서드는 Prometheus가 긁을 때마다
	 * (5초에 한 번) DB에 {@code COUNT} 한 번을 던진다. 인덱스가 있는 단순 카운트라 감당할 만하고,
	 * 그 대가로 <b>항상 지금 값</b>을 본다.
	 *
	 * <p><b>예외를 삼키는 이유가 중요하다.</b> 여기서 예외가 나가면 스크랩 전체가 실패해
	 * <b>JVM·커넥션 풀·컨슈머 지표까지 통째로 사라진다.</b> DB가 죽었을 때가 바로 그 지표들이
	 * 가장 필요한 순간인데, Outbox 하나 때문에 다 잃는 건 손해가 크다.
	 * 이 값만 NaN(그래프의 끊긴 선)으로 떨어지고 나머지는 계속 나온다.
	 */
	private static double backlog(OutboxEventRepository repository) {
		try {
			return repository.countByPublishedAtIsNull();
		} catch (Exception e) {
			log.warn("Outbox 적체를 세지 못했다 - 이 지표만 NaN으로 떨어진다", e);
			return Double.NaN;
		}
	}
}
