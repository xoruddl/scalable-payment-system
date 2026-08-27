package com.remittance.account.external;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
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
 */
@Component
public class ExternalCallBulkhead {

	private static final String NAME = "external-bank";

	private final Bulkhead bulkhead;

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
	}

	public <T> T call(Supplier<T> action) {
		return bulkhead.executeSupplier(action);
	}
}
