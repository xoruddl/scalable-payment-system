package com.remittance.account.external;

import com.remittance.account.exception.UnknownBankException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 장애가 계속되는 상대 은행에 새 입금 요청을 잠시 보내지 않는다.
 *
 * <p>직접 만든 CLOSED/OPEN/HALF_OPEN 상태 머신을 Resilience4j로 교체했다. 회로는 은행별로
 * 나뉘며, 크기가 실패 임계값인 count-based window와 실패율 100%를 조합해
 * <b>N회 연속 실패</b>를 표현한다. 중간에 성공이 하나라도 끼면 최근 N건의 실패율이 100%가
 * 아니므로 열리지 않는다.
 *
 * <p>이미 보낸 돈을 확인하는 조회는 이 회로를 지나지 않는다. 새 전송을 막느라
 * {@code CREDIT_UNKNOWN} 해소까지 막으면 안 되기 때문이다.
 */
@Component
public class ExternalCallCircuitBreaker {

	private static final Logger log = LoggerFactory.getLogger(ExternalCallCircuitBreaker.class);

	private final CircuitBreakerRegistry registry;
	private final Map<String, CircuitBreaker> circuits = new ConcurrentHashMap<>();

	@Autowired
	public ExternalCallCircuitBreaker(
			@Value("${remittance.external-bank.circuit-breaker.failure-threshold:5}") int failureThreshold,
			@Value("${remittance.external-bank.circuit-breaker.open-duration:30s}") Duration openDuration,
			MeterRegistry meterRegistry) {
		this(failureThreshold, openDuration, meterRegistry, new MonotonicClock());
	}

	ExternalCallCircuitBreaker(int failureThreshold, Duration openDuration,
			MeterRegistry meterRegistry, Clock clock) {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(failureThreshold)
				.minimumNumberOfCalls(failureThreshold)
				.failureRateThreshold(100)
				.waitDurationInOpenState(openDuration)
				.permittedNumberOfCallsInHalfOpenState(1)
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				// Resilience4j의 OPEN 전이는 Clock을 본다. 벽시계 보정에 대기시간이 흔들리지 않게 한다.
				.clock(clock)
				.currentTimestampFunction(Clock::millis, TimeUnit.MILLISECONDS)
				.build();
		this.registry = CircuitBreakerRegistry.of(config);
		TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
	}

	public <T> T call(String bankCode, Supplier<T> action) {
		return call(bankCode, () -> { }, action);
	}

	/**
	 * 회로의 허가를 얻은 뒤 {@code beforeCall}을 실행하고 상대를 부른다.
	 *
	 * <p>미전송 건의 {@code sent=true} 영속화는 허가 뒤, HTTP 직전에 와야 한다. 그래서
	 * 데코레이터 한 줄 대신 Resilience4j의 저수준 permission API를 쓴다. {@code beforeCall}이
	 * 실패하면 상대 장애로 기록하지 않고 허가를 반환한다.
	 */
	public <T> T call(String bankCode, Runnable beforeCall, Supplier<T> action) {
		CircuitBreaker circuit = circuit(bankCode);
		circuit.acquirePermission();

		try {
			beforeCall.run();
		} catch (RuntimeException localFailure) {
			circuit.releasePermission();
			throw localFailure;
		}

		long startedAt = System.nanoTime();
		try {
			T result = action.get();
			circuit.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
			return result;
		} catch (RuntimeException externalFailure) {
			if (externalFailure instanceof UnknownBankException) {
				// 주소 설정 오류라 HTTP 호출 자체가 없었다. 상대 은행 실패로 세면 안 된다.
				circuit.releasePermission();
			} else {
				circuit.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, externalFailure);
			}
			throw externalFailure;
		}
	}

	CircuitBreaker circuit(String bankCode) {
		return circuits.computeIfAbsent(bankCode, code -> {
			CircuitBreaker circuit = registry.circuitBreaker("external-credit-" + code);
			circuit.getEventPublisher().onStateTransition(event ->
					log.warn("상대 은행 회로 상태 전이 (bank={}, {})", code, event.getStateTransition()));
			return circuit;
		});
	}

	/** 기동 시각에 고정한 뒤 {@code nanoTime}만큼 전진하는 단조 증가 시계. */
	private static final class MonotonicClock extends Clock {

		private final Instant origin = Instant.now();
		private final long originNanos = System.nanoTime();

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return origin.plusNanos(System.nanoTime() - originNanos);
		}
	}
}
