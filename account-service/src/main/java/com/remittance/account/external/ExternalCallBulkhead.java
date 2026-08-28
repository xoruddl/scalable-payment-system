package com.remittance.account.external;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 상대 은행 호출이 컨슈머 스레드를 몇 개까지 붙들 수 있는지 제한한다.
 *
 * <p>직접 만든 {@code Semaphore} 격벽에서 확인한 계약을 Resilience4j의 semaphore bulkhead로
 * 옮겼다. {@code maxWaitDuration=0}이므로 자리가 없으면 기다리지 않고
 * {@link io.github.resilience4j.bulkhead.BulkheadFullException}을 던진다. 이 예외가 뜻하는 것은
 * <b>상대에게 보내지 않았다</b>는 것이다.
 *
 * <h2>거절 수는 우리가 센다 ★</h2>
 * {@link TaggedBulkheadMetrics}가 내는 것은 <b>게이지 둘뿐</b>이다 — 정원과 남은 자리.
 * 그런데 격벽이 일하고 있는지는 순간값이 아니라 <b>얼마나 거절했나</b>로 판정해왔다
 * (2026-08-27 실측의 "거절 1,662 &gt; 통과 1,273"이 정원을 늘린 근거였다).
 * 직접 만든 격벽에는 있던 그 카운터가 Resilience4j에는 없으므로 이벤트로 다시 만든다.
 *
 * <p>이름은 자체 구현 때와 <b>같게</b> 뒀다. 바꾸면 지나간 기록의 숫자와 이어지지 않는다.
 * 회로 차단기 쪽은 {@code resilience4j.circuitbreaker.not.permitted.calls}가 이미 세주므로
 * 여기만 채운다.
 */
@Component
public class ExternalCallBulkhead {

	private static final String NAME = "external-bank";

	private final Bulkhead bulkhead;
	private final Counter admitted;
	private final Counter rejected;

	public ExternalCallBulkhead(
			@Value("${remittance.external-bank.bulkhead.capacity:2}") int capacity,
			MeterRegistry meterRegistry) {
		BulkheadConfig config = BulkheadConfig.custom()
				.maxConcurrentCalls(capacity)
				.maxWaitDuration(Duration.ZERO)
				.build();
		BulkheadRegistry registry = BulkheadRegistry.of(config);
		TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
		this.bulkhead = registry.bulkhead(NAME);

		// 한 번도 거절이 없어도 0으로 보여야 한다 — 없는 것과 0은 다르다.
		this.admitted = counter(meterRegistry, "admitted", "격벽을 통과해 상대 은행으로 나간 호출 수");
		this.rejected = counter(meterRegistry, "rejected", "정원이 차서 보내지 않은 호출 수");
		bulkhead.getEventPublisher()
				.onCallPermitted(permitted -> admitted.increment())
				.onCallRejected(full -> rejected.increment());
	}

	private static Counter counter(MeterRegistry meterRegistry, String outcome, String description) {
		return Counter.builder("remittance.external.bulkhead." + outcome)
				.description(description)
				.register(meterRegistry);
	}

	public <T> T call(Supplier<T> action) {
		return bulkhead.executeSupplier(action);
	}
}
