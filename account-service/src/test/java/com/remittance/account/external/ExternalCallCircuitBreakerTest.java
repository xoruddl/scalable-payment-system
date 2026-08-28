package com.remittance.account.external;

import com.remittance.account.exception.UnknownBankException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCallCircuitBreakerTest {

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final MutableClock clock = new MutableClock();
	private final ExternalCallCircuitBreaker circuitBreaker =
			new ExternalCallCircuitBreaker(3, Duration.ofSeconds(10), meters, clock);

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
		})).isInstanceOf(CallNotPermittedException.class);

		assertThat(calls).hasValue(3);
		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(meters.find("resilience4j.circuitbreaker.not.permitted.calls")
				.tag("name", "external-credit-KB").counter().count()).isEqualTo(1);
	}

	@Test
	void 성공하면_연속_실패_횟수를_처음부터_센다() {
		fail("KB");
		fail("KB");
		circuitBreaker.call("KB", () -> "ok");
		fail("KB");
		fail("KB");

		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void 한_은행의_장애가_다른_은행의_회로를_열지_않는다() {
		fail("KB");
		fail("KB");
		fail("KB");

		assertThat(circuitBreaker.call("SH", () -> "ok")).isEqualTo("ok");
		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(state("SH")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void 대기_시간이_지나면_한_건을_시험하고_성공하면_닫는다() {
		open("KB");
		clock.advance(Duration.ofSeconds(10).plusMillis(1));

		assertThat(circuitBreaker.call("KB", () -> "recovered")).isEqualTo("recovered");
		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(circuitBreaker.call("KB", () -> "normal")).isEqualTo("normal");
	}

	@Test
	void HALF_OPEN에서는_한_건만_시험하고_성공하면_닫는다() throws Exception {
		open("KB");
		circuitBreaker.circuit("KB").transitionToHalfOpenState();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (var executor = Executors.newSingleThreadExecutor()) {
			var probe = executor.submit(() -> circuitBreaker.call("KB", () -> {
				started.countDown();
				await(release);
				return "recovered";
			}));
			try {
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> circuitBreaker.call("KB", () -> "second probe"))
						.isInstanceOf(CallNotPermittedException.class);
			} finally {
				release.countDown();
			}
			assertThat(probe.get()).isEqualTo("recovered");
		}

		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void HALF_OPEN_시험도_실패하면_다시_연다() {
		open("KB");
		circuitBreaker.circuit("KB").transitionToHalfOpenState();
		fail("KB");

		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.OPEN);
		assertThatThrownBy(() -> circuitBreaker.call("KB", () -> "too early"))
				.isInstanceOf(CallNotPermittedException.class);
	}

	@Test
	void 차단된_호출은_전송_직전_작업도_실행하지_않는다() {
		open("KB");
		AtomicInteger beforeCall = new AtomicInteger();

		assertThatThrownBy(() -> circuitBreaker.call("KB", beforeCall::incrementAndGet, () -> "sent"))
				.isInstanceOf(CallNotPermittedException.class);

		assertThat(beforeCall).hasValue(0);
	}

	@Test
	void 전송_직전의_로컬_실패는_상대_은행_실패로_세지_않는다() {
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> circuitBreaker.call("KB", () -> {
				throw new IllegalStateException("DB 저장 실패");
			}, () -> "보내면 안 된다")).isInstanceOf(IllegalStateException.class);
		}

		assertThat(state("KB")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void 주소_설정_오류는_상대_은행_실패로_세지_않는다() {
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> circuitBreaker.call("UNKNOWN", () -> {
				throw new UnknownBankException("UNKNOWN", "KB");
			})).isInstanceOf(UnknownBankException.class);
		}

		assertThat(state("UNKNOWN")).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	private CircuitBreaker.State state(String bankCode) {
		return circuitBreaker.circuit(bankCode).getState();
	}

	private void open(String bankCode) {
		fail(bankCode);
		fail(bankCode);
		fail(bankCode);
	}

	private void fail(String bankCode) {
		assertThatThrownBy(() -> circuitBreaker.call(bankCode, () -> {
			throw new RuntimeException("timeout");
		})).isInstanceOf(RuntimeException.class);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class MutableClock extends Clock {

		private final AtomicLong millis = new AtomicLong();

		void advance(Duration duration) {
			millis.addAndGet(duration.toMillis());
		}

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
			return Instant.ofEpochMilli(millis.get());
		}
	}
}
