package com.remittance.account.external;

import com.remittance.account.exception.UnknownBankException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 장애가 계속되는 상대 은행에 새 입금 요청을 잠시 보내지 않는다 (회로 차단기).
 *
 * <p><b>은행별로 회로를 나눈다.</b> KB가 느리다고 정상인 SH까지 막으면 장애 격리가 아니라
 * 장애 전파다. 같은 은행에서 연속 실패가 임계값에 닿으면 회로를 열고, 대기 시간이 지난 뒤
 * 단 한 호출만 HALF_OPEN 시험으로 보낸다.
 *
 * <p>OPEN에서 거절한 호출은 <b>상대에게 보내지 않았다.</b> 따라서 호출부는
 * {@code CREDIT_UNKNOWN}이 아니라 미전송으로 기록해야 한다. 이미 보낸 돈을 확인하는 조회는
 * 이 회로로 막지 않는다 — 새 요청을 보호하려다 모르는 돈의 해소까지 늦추면 안 된다.
 *
 * <p>지금은 상태 전이가 왜 필요한지 배우기 위한 직접 구현이다. 다음 Step에서 직접 만든 격벽과
 * 함께 Resilience4j로 교체하고, 여기서 확인한 계약을 회귀 테스트로 남긴다.
 */
@Component
public class ExternalCallCircuitBreaker {

	private static final Logger log = LoggerFactory.getLogger(ExternalCallCircuitBreaker.class);

	enum State {
		CLOSED,
		OPEN,
		HALF_OPEN
	}

	private final int failureThreshold;
	private final long openDurationNanos;
	private final LongSupplier nanoTime;
	private final Map<String, BankCircuit> circuits = new ConcurrentHashMap<>();
	private final Map<State, Counter> transitions = new EnumMap<>(State.class);
	private final Counter rejected;

	@Autowired
	public ExternalCallCircuitBreaker(
			@Value("${remittance.external-bank.circuit-breaker.failure-threshold:5}") int failureThreshold,
			@Value("${remittance.external-bank.circuit-breaker.open-duration:30s}") Duration openDuration,
			MeterRegistry meterRegistry) {
		this(failureThreshold, openDuration, meterRegistry, System::nanoTime);
	}

	ExternalCallCircuitBreaker(int failureThreshold, Duration openDuration,
			MeterRegistry meterRegistry, LongSupplier nanoTime) {
		if (failureThreshold < 1) {
			throw new IllegalArgumentException("회로 차단기 실패 임계값은 1 이상이어야 한다");
		}
		if (openDuration.isNegative() || openDuration.isZero()) {
			throw new IllegalArgumentException("회로 차단기 OPEN 시간은 0보다 커야 한다");
		}
		this.failureThreshold = failureThreshold;
		this.openDurationNanos = openDuration.toNanos();
		this.nanoTime = nanoTime;
		this.rejected = Counter.builder("remittance.external.circuit.rejected")
				.description("OPEN 또는 HALF_OPEN 회로가 거절한 외부 호출 수")
				.register(meterRegistry);
		for (State state : State.values()) {
			transitions.put(state, Counter.builder("remittance.external.circuit.transitions")
					.description("상대 은행 회로 차단기 상태 전이 수")
					.tag("state", state.name().toLowerCase())
					.register(meterRegistry));
			Gauge.builder("remittance.external.circuit.banks", circuits,
					map -> map.values().stream().filter(circuit -> circuit.state() == state).count())
					.description("회로 차단기 상태별 상대 은행 수")
					.tag("state", state.name().toLowerCase())
					.register(meterRegistry);
		}
	}

	public <T> T call(String bankCode, Supplier<T> action) {
		return call(bankCode, () -> { }, action);
	}

	/**
	 * 호출 허가를 얻은 뒤 {@code beforeCall}을 먼저 실행하고 상대를 부른다.
	 *
	 * <p>미전송 건은 <b>실제 호출 직전에</b> {@code sent=true}를 영속화해야 한다. OPEN이라
	 * 호출하지 않을 때 먼저 표시하면, 안 보낸 돈을 보냈다고 오인해 조회부터 하는 버그가 생긴다.
	 * 반대로 외부 호출 뒤에 표시하면 응답을 기다리다 프로세스가 죽을 때 이중 전송할 수 있다.
	 */
	public <T> T call(String bankCode, Runnable beforeCall, Supplier<T> action) {
		BankCircuit circuit = circuits.computeIfAbsent(bankCode, BankCircuit::new);
		if (!circuit.tryAcquire(nanoTime.getAsLong())) {
			rejected.increment();
			throw new CircuitOpenException(bankCode);
		}

		try {
			beforeCall.run();
		} catch (RuntimeException localFailure) {
			// 상대를 부르기 전 우리 쪽에서 실패했다. 은행 장애로 세면 안 된다.
			circuit.cancelProbe(nanoTime.getAsLong());
			throw localFailure;
		}

		try {
			T result = action.get();
			circuit.onSuccess();
			return result;
		} catch (RuntimeException externalFailure) {
			if (externalFailure instanceof UnknownBankException) {
				// 주소 설정 오류는 상대 은행의 실패가 아니고, 실제 HTTP 호출도 일어나지 않았다.
				circuit.cancelProbe(nanoTime.getAsLong());
				throw externalFailure;
			}
			circuit.onFailure(nanoTime.getAsLong());
			throw externalFailure;
		}
	}

	State stateOf(String bankCode) {
		BankCircuit circuit = circuits.get(bankCode);
		return circuit == null ? State.CLOSED : circuit.state();
	}

	private final class BankCircuit {

		private final String bankCode;
		private State state = State.CLOSED;
		private int consecutiveFailures;
		private long openedAtNanos;

		private BankCircuit(String bankCode) {
			this.bankCode = bankCode;
		}

		synchronized boolean tryAcquire(long nowNanos) {
			if (state == State.CLOSED) {
				return true;
			}
			if (state == State.OPEN && nowNanos - openedAtNanos >= openDurationNanos) {
				transitionTo(State.HALF_OPEN);
				return true;
			}
			// HALF_OPEN에는 이미 시험 호출 하나가 나가 있다.
			return false;
		}

		synchronized void onSuccess() {
			consecutiveFailures = 0;
			if (state == State.HALF_OPEN) {
				transitionTo(State.CLOSED);
			}
		}

		synchronized void onFailure(long nowNanos) {
			if (state == State.HALF_OPEN) {
				open(nowNanos);
				return;
			}
			consecutiveFailures++;
			if (consecutiveFailures >= failureThreshold) {
				open(nowNanos);
			}
		}

		synchronized void cancelProbe(long nowNanos) {
			if (state == State.HALF_OPEN) {
				// 시험 자체를 못 했다. 닫지도 실패로 세지도 않고 잠시 뒤 다시 시험한다.
				open(nowNanos);
			}
		}

		synchronized State state() {
			return state;
		}

		private void open(long nowNanos) {
			openedAtNanos = nowNanos;
			transitionTo(State.OPEN);
		}

		private void transitionTo(State next) {
			if (state == next) {
				return;
			}
			State previous = state;
			state = next;
			transitions.get(next).increment();
			log.warn("상대 은행 회로 상태 전이 (bank={}, {} -> {})", bankCode, previous, next);
		}
	}

	/** 상대를 부르지 않았다. 호출부는 이 요청을 미전송으로 보관해야 한다. */
	public static class CircuitOpenException extends RuntimeException {
		public CircuitOpenException(String bankCode) {
			super("상대 은행 회로가 열려 호출하지 않았다 (bank=%s)".formatted(bankCode));
		}
	}
}
