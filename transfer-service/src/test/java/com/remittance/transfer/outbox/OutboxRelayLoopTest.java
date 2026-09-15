package com.remittance.transfer.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 릴레이 루프의 약속 — 컨텍스트 없이 스레드만 본다 (D-007).
 *
 * 신호가 커밋 뒤에 오는지는 {@link OutboxRelayWakeupTest}가 본다. 여기서는 루프가
 *   - 신호에 답하는가 — 주기를 기다리지 않는가
 *   - 한 바퀴가 실패해도 살아 있는가 — {@code @Scheduled}가 해 주던 일을 이제 우리가 한다
 *   - 멈추라고 하면 바로 멈추는가
 * 를 본다. account-service의 같은 이름 테스트와 짝이다.
 */
class OutboxRelayLoopTest {

	/** 이 주기로는 테스트 동안 두 번째 바퀴가 오지 않는다. 왔다면 신호가 돌린 것이다. */
	private static final long 주기_10분 = Duration.ofMinutes(10).toMillis();

	private final OutboxRelay relay = mock(OutboxRelay.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private OutboxRelayLoop loop;

	@AfterEach
	void 루프를_멈춘다() {
		if (loop != null && loop.isRunning()) {
			loop.stop();
		}
	}

	@Test
	void 깨우면_주기를_기다리지_않고_한_바퀴_돈다() {
		loop = 띄운다(주기_10분);
		verify(relay, timeout(1_000)).publishPending(); // 기동 직후 첫 바퀴

		loop.wakeUp(OutboxEvent.Recorded.INSTANCE);

		verify(relay, timeout(1_000).times(2)).publishPending();
		assertThat(바퀴("wakeup"))
				.as("깨운 바퀴는 따로 센다 — 전보다 늘어난 트랜잭션이 이것이다")
				.isEqualTo(1);
	}

	@Test
	void 한_바퀴가_실패해도_스레드가_살아서_다음_바퀴를_돈다() {
		doThrow(new IllegalStateException("DB에 닿지 못했다")).doNothing().when(relay).publishPending();

		loop = 띄운다(50);

		verify(relay, timeout(2_000).atLeast(2)).publishPending();
	}

	@Test
	void 멈추라고_하면_기다리던_중이어도_바로_멈추고_다시_돌지_않는다() {
		loop = 띄운다(주기_10분);
		verify(relay, timeout(1_000)).publishPending();

		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> loop.stop(),
				"주기(10분)를 기다리던 스레드를 깨우지 않으면 종료 대기 상한(10초)을 다 쓰고서야 멈춘다");
		loop.wakeUp(OutboxEvent.Recorded.INSTANCE);

		verify(relay, after(300).times(1)).publishPending();
		assertThat(loop.isRunning()).isFalse();
	}

	private OutboxRelayLoop 띄운다(long intervalMs) {
		OutboxRelayLoop started = new OutboxRelayLoop(relay, meterRegistry, intervalMs);
		started.start();
		return started;
	}

	private double 바퀴(String trigger) {
		return meterRegistry.get("remittance.outbox.relay.runs").tag("trigger", trigger).counter().count();
	}
}
