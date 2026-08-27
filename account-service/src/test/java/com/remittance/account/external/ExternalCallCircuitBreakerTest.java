package com.remittance.account.external;

import com.remittance.account.exception.UnknownBankException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCallCircuitBreakerTest {

	private final AtomicLong now = new AtomicLong();
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final ExternalCallCircuitBreaker circuitBreaker =
			new ExternalCallCircuitBreaker(3, Duration.ofSeconds(10), meters, now::get);

	@Test
	void 연속_실패가_임계값에_닿으면_회로를_열고_호출하지_않는다() {
		AtomicInteger calls = new AtomicInteger();
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> circuitBreaker.call("KB", () -> {
				calls.incrementAndGet();
				throw new RuntimeException("timeout");
			})).isInstanceOf(RuntimeException.class);
		}

		assertThatThrownBy(() -> circuitBreaker.call("KB", () -> {
			calls.incrementAndGet();
			return null;
		})).isInstanceOf(ExternalCallCircuitBreaker.CircuitOpenException.class);

		assertThat(calls).hasValue(3);
		assertThat(circuitBreaker.stateOf("KB")).isEqualTo(ExternalCallCircuitBreaker.State.OPEN);
		assertThat(meters.counter("remittance.external.circuit.rejected").count()).isEqualTo(1);
	}

	@Test
	void 성공하면_연속_실패_횟수를_처음부터_센다() {
		fail("KB");
		fail("KB");
		circuitBreaker.call("KB", () -> "ok");
		fail("KB");
		fail("KB");

		assertThat(circuitBreaker.stateOf("KB")).isEqualTo(ExternalCallCircuitBreaker.State.CLOSED);
	}

	@Test
	void 한_은행의_장애가_다른_은행의_회로를_열지_않는다() {
		fail("KB");
		fail("KB");
		fail("KB");

		assertThat(circuitBreaker.call("SH", () -> "ok")).isEqualTo("ok");
		assertThat(circuitBreaker.stateOf("KB")).isEqualTo(ExternalCallCircuitBreaker.State.OPEN);
		assertThat(circuitBreaker.stateOf("SH")).isEqualTo(ExternalCallCircuitBreaker.State.CLOSED);
	}

	@Test
	void 대기_시간이_지나면_한_건만_시험하고_성공하면_닫는다() {
		fail("KB");
		fail("KB");
		fail("KB");
		now.addAndGet(Duration.ofSeconds(10).toNanos());

		assertThat(circuitBreaker.call("KB", () -> "recovered")).isEqualTo("recovered");
		assertThat(circuitBreaker.stateOf("KB")).isEqualTo(ExternalCallCircuitBreaker.State.CLOSED);
		assertThat(circuitBreaker.call("KB", () -> "normal")).isEqualTo("normal");
	}

	@Test
	void 시험_호출이_진행_중이면_다른_호출은_허용하지_않는다() throws Exception {
		fail("KB");
		fail("KB");
		fail("KB");
		now.addAndGet(Duration.ofSeconds(10).toNanos());
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (var executor = Executors.newSingleThreadExecutor()) {
			var probe = executor.submit(() -> circuitBreaker.call("KB", () -> {
				started.countDown();
				try {
					release.await();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				return "recovered";
			}));
			try {
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> circuitBreaker.call("KB", () -> "second probe"))
						.isInstanceOf(ExternalCallCircuitBreaker.CircuitOpenException.class);
			} finally {
				release.countDown();
			}
			assertThat(probe.get()).isEqualTo("recovered");
		}
	}

	@Test
	void 시험_호출도_실패하면_다시_열고_대기_시간을_새로_센다() {
		fail("KB");
		fail("KB");
		fail("KB");
		now.addAndGet(Duration.ofSeconds(10).toNanos());
		fail("KB");

		assertThatThrownBy(() -> circuitBreaker.call("KB", () -> "too early"))
				.isInstanceOf(ExternalCallCircuitBreaker.CircuitOpenException.class);
		now.addAndGet(Duration.ofSeconds(10).toNanos());
		assertThat(circuitBreaker.call("KB", () -> "recovered")).isEqualTo("recovered");
	}

	@Test
	void 차단된_호출은_전송_직전_작업도_실행하지_않는다() {
		fail("KB");
		fail("KB");
		fail("KB");
		AtomicInteger beforeCall = new AtomicInteger();

		assertThatThrownBy(() -> circuitBreaker.call("KB", beforeCall::incrementAndGet, () -> "sent"))
				.isInstanceOf(ExternalCallCircuitBreaker.CircuitOpenException.class);

		assertThat(beforeCall).hasValue(0);
	}

	@Test
	void 주소_설정_오류는_상대_은행_실패로_세지_않는다() {
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> circuitBreaker.call("UNKNOWN", () -> {
				throw new UnknownBankException("UNKNOWN", "KB");
			})).isInstanceOf(UnknownBankException.class);
		}

		assertThat(circuitBreaker.stateOf("UNKNOWN"))
				.isEqualTo(ExternalCallCircuitBreaker.State.CLOSED);
	}

	private void fail(String bankCode) {
		assertThatThrownBy(() -> circuitBreaker.call(bankCode, () -> {
			throw new RuntimeException("timeout");
		})).isInstanceOf(RuntimeException.class);
	}
}
