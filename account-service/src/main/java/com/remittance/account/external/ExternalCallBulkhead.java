package com.remittance.account.external;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 상대 은행 호출이 <b>컨슈머 스레드를 몇 개까지 붙들 수 있는지</b> 제한한다 (격벽).
 *
 * <h2>왜 필요한가 — 숫자로 확인했다 (2026-08-27)</h2>
 * 상대가 2초 느려지자 <b>우리 내부 송금</b>의 종결 p99가 3,071 → 58,790ms가 됐다.
 * 종결 성공률은 1.00 → 0.53. 내부 송금은 상대 은행과 아무 상관이 없는데도 그렇다.
 *
 * <p>원인은 입금 리스너 하나가 내부 입금과 외부 호출을 <b>같이</b> 처리하는 것이었다.
 * 외부 6건/s × 2초 = <b>12 스레드-초/초</b>인데 스레드는 6개다 —
 * 외부 호출만으로 이미 다 쓴다.
 *
 * <h2>격벽은 처리량을 만들어주지 않는다 ★</h2>
 * 이 점을 분명히 해둔다. 스레드 6개가 12 스레드-초의 일을 할 수는 없다.
 * 격벽이 하는 일은 <b>피해를 가두는 것</b>이다 — 외부가 아무리 느려도
 * 스레드 N개까지만 묶이고, 나머지는 내부 송금이 쓴다.
 * <b>남의 사정으로 우리 일이 멈추지 않게</b> 하는 것이 목적이지 외부를 빠르게 만드는 게 아니다.
 *
 * <h2>기다리지 않고 즉시 거절한다</h2>
 * 자리가 없을 때 기다리면 <b>스레드가 묶이는 것은 똑같다.</b> 격벽의 의미가 사라진다.
 * 그래서 즉시 거절하고, 호출부가 "아직 안 보냈다"로 기록해 나중에 보낸다.
 *
 * <h2>직접 만든 이유</h2>
 * Resilience4j의 {@code Bulkhead}가 하는 일이 이것이다. 먼저 손으로 만들어
 * <b>무엇이 필요한지 알고 나서</b> 갈아탄다 — 이 저장소의 방식이고,
 * 갈아탈 때 {@code DECISIONS.md}에 근거를 남긴다.
 */
@Component
public class ExternalCallBulkhead {

	private final Semaphore permits;
	private final int capacity;
	private final Counter rejected;
	private final Counter admitted;

	public ExternalCallBulkhead(
			@Value("${remittance.external-bank.bulkhead.capacity:2}") int capacity,
			MeterRegistry meterRegistry) {
		this.capacity = capacity;
		// 공정성(fair)을 켜지 않는다. 순서가 중요한 게 아니라 <b>몇 개까지냐</b>가 중요하고,
		// 공정 모드는 그 자체로 비용이 있다.
		this.permits = new Semaphore(capacity);
		this.rejected = Counter.builder("remittance.external.bulkhead.rejected")
				.description("격벽이 자리가 없어 거절한 외부 호출 수")
				.register(meterRegistry);
		this.admitted = Counter.builder("remittance.external.bulkhead.admitted")
				.description("격벽을 통과한 외부 호출 수")
				.register(meterRegistry);
		Gauge.builder("remittance.external.bulkhead.available", permits, Semaphore::availablePermits)
				.description("격벽에 남은 자리")
				.register(meterRegistry);
	}

	/**
	 * 자리가 있으면 실행하고, 없으면 {@link BulkheadFullException}을 즉시 던진다.
	 *
	 * @throws BulkheadFullException 자리가 없다. <b>호출은 일어나지 않았다</b> —
	 *                               그래서 "모르는 상태"가 아니라 "아직 안 보낸 상태"다.
	 */
	public <T> T call(Supplier<T> action) {
		if (!permits.tryAcquire()) {
			rejected.increment();
			throw new BulkheadFullException(capacity);
		}
		admitted.increment();
		try {
			return action.get();
		} finally {
			permits.release();
		}
	}

	/** 카운터가 0에서도 보이게 미리 만들어 둔다. 평소에 0인 게 정상인 지표다. */
	@PostConstruct
	void 지표를_미리_만든다() {
		rejected.increment(0);
		admitted.increment(0);
	}

	/** 자리가 없어 외부 호출을 하지 못했다. <b>보내지 않았다</b>는 것이 핵심이다. */
	public static class BulkheadFullException extends RuntimeException {
		public BulkheadFullException(int capacity) {
			super("외부 호출 자리가 없다 (격벽 %d개) - 보내지 않았다".formatted(capacity));
		}
	}
}
