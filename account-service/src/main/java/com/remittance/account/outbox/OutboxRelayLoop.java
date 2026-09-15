package com.remittance.account.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 릴레이를 돌리는 전용 스레드 — 커밋이 깨우고, 깨우는 게 없으면 주기마다 돈다 (D-007).
 *
 * 왜 깨우나 (2026-09-15)
 * 전에는 {@code @Scheduled}가 500ms마다 돌았다. 적체가 없으면 커밋된 행이 다음 주기까지 0~500ms를
 * 기다렸고, 송금 한 건은 Kafka를 세 번 건너므로 종결에 폴링 몫만 최대 1.5초가 붙었다 (SLO.md ②).
 * 이제 Outbox 행이 커밋되면 그 자리에서 한 바퀴 돈다.
 *
 * 주기는 남긴다 — 안전망이다
 * 신호가 닿지 않는 행이 있다. Kafka 전송이 실패해 남은 행, 커밋과 신호 사이에 프로세스가 죽어 남은 행,
 * {@code save}를 거치지 않고 적힌 행. 이것들은 주기가 줍는다. 유실 0은 여전히 테이블과 주기가 지키고,
 * 신호는 빨라지는 길을 하나 더할 뿐이다.
 *
 * 신호는 합쳐진다
 * 자리가 하나인 큐에 넣는다. 한 바퀴 도는 사이에 커밋이 여럿 와도 표시는 하나만 남고, 다음 바퀴가
 * 그동안 쌓인 것을 한 배치로 가져간다 — 커밋마다 한 바퀴씩 돌지 않는다. 대신 이미 가져간 행 때문에
 * 한 바퀴를 더 돌 수 있다. 그때는 빈 조회 한 번이다.
 *
 * 왜 {@code @Scheduled}가 아니라 전용 스레드인가
 * 신호를 기다리는 동안 스레드를 쥐고 있어야 한다. 스케줄러 스레드로 기다리면 같은 스케줄러의 다른 일
 * (보관 기간 정리 · 외부 은행 조회 · 조각 수 갱신)이 그만큼 자리를 잃는다. 2026-08-27에 스케줄러 스레드를
 * 오래 붙든 일 때문에 릴레이가 굶어 시스템 전체가 멈췄다 — 이번엔 릴레이가 붙드는 쪽이 된다.
 *
 * 대가
 *   - 스레드의 생사를 우리가 맡는다. {@code @Scheduled}는 예외를 삼키고 다음 주기를 돌려줬지만, 여기서
 *     예외가 새면 스레드가 죽고 발행이 조용히 멈춘다. 그래서 한 바퀴의 예외는 잡아서 남긴다
 *   - 요청이 적당히 오면 한 바퀴가 가져가는 배치가 작아져, 바퀴 수만큼 트랜잭션(발행 표시 UPDATE)이 는다.
 *     밀리면 배치가 차서 전과 같다. 이 비용은 아직 재지 않았다 — {@code remittance.outbox.relay.runs}로 본다
 *
 * transfer-service의 같은 이름 클래스와 구조가 같다 (공유 모듈을 두지 않는다).
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", matchIfMissing = true)
public class OutboxRelayLoop implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelayLoop.class);

	/** 멈추라고 한 뒤 돌던 한 바퀴가 끝나기를 기다려 주는 시간. 배치 하나를 Kafka로 보낼 만큼이다. */
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

	private final OutboxRelay relay;
	private final Duration interval;

	/** 자리가 하나다. 이미 표시가 있으면 새 신호는 버린다 — 다음 바퀴가 어차피 다 가져간다. */
	private final BlockingQueue<OutboxEvent.Recorded> wakeups = new ArrayBlockingQueue<>(1);

	/** 한 바퀴를 무엇이 돌렸나. 깨운 바퀴가 곧 전보다 늘어난 트랜잭션이다. */
	private final Counter wokenRuns;
	private final Counter scheduledRuns;

	private volatile boolean running;
	private Thread thread;

	/**
	 * @param intervalMs 깨우는 게 없을 때 도는 주기. 기본값은 {@code application.yml}과 같은 값으로 둔다 —
	 *                   둘이 다르면 yml이 이기는데, 코드만 읽은 사람은 그 사실을 모른다
	 *                   (2026-09-11에 실제로 어긋나 있었다).
	 */
	public OutboxRelayLoop(OutboxRelay relay, MeterRegistry meterRegistry,
			@Value("${outbox.relay.interval-ms:500}") long intervalMs) {
		this.relay = relay;
		this.interval = Duration.ofMillis(intervalMs);
		this.wokenRuns = runs(meterRegistry, "wakeup");
		this.scheduledRuns = runs(meterRegistry, "interval");
	}

	private static Counter runs(MeterRegistry meterRegistry, String trigger) {
		return Counter.builder("remittance.outbox.relay.runs")
				.description("Outbox 릴레이가 한 바퀴 돈 횟수 — 커밋이 깨웠나(wakeup), 주기가 돌렸나(interval)")
				.tag("trigger", trigger)
				.register(meterRegistry);
	}

	/**
	 * Outbox 행이 커밋됐다 — 깨운다. 행을 적은 트랜잭션이 커밋된 뒤에 불린다.
	 *
	 * 커밋 전에 깨우면 안 된다. 릴레이는 아직 안 보이는 행을 찾다 빈손으로 돌아가고, 그 행은 결국 다음
	 * 주기를 기다린다 — 깨운 효과가 조용히 사라진다. 트랜잭션 없이 저장됐다면 저장이 이미 커밋된 것이라
	 * 바로 깨운다({@code fallbackExecution}).
	 *
	 * 커밋한 요청·컨슈머 스레드가 부르므로 표시만 하고 기다리지 않는다.
	 */
	@TransactionalEventListener(fallbackExecution = true)
	void wakeUp(OutboxEvent.Recorded recorded) {
		wakeups.offer(recorded);
	}

	@Override
	public void start() {
		running = true;
		thread = Thread.ofPlatform().name("outbox-relay").daemon(true).start(this::loop);
	}

	private void loop() {
		// 기동 직후 첫 바퀴는 꺼져 있는 동안 쌓인 것을 줍는다 — 주기가 돌린 바퀴로 센다.
		Counter trigger = scheduledRuns;
		while (running) {
			trigger.increment();
			runOnce();
			trigger = awaitWakeup() ? wokenRuns : scheduledRuns;
		}
	}

	private void runOnce() {
		try {
			relay.publishPending();
		} catch (RuntimeException e) {
			// 여기서 새면 스레드가 죽고 발행이 조용히 멈춘다. 남기고 다음 바퀴에 다시 한다.
			log.error("Outbox 릴레이 한 바퀴가 실패했다 — 다음 바퀴에 다시 시도한다", e);
		}
	}

	/** @return 신호가 깨웠으면 {@code true}, 주기가 다 됐으면 {@code false} */
	private boolean awaitWakeup() {
		try {
			return wakeups.poll(interval.toMillis(), TimeUnit.MILLISECONDS) != null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
			return false;
		}
	}

	@Override
	public void stop() {
		running = false;
		if (thread == null) {
			return;
		}
		// 기다리는 중이면 바로 깨워 끝내게 한다. 도는 중이면 그 바퀴가 끝난 뒤 멈춘다.
		wakeups.offer(OutboxEvent.Recorded.INSTANCE);
		try {
			if (!thread.join(STOP_TIMEOUT)) {
				log.warn("Outbox 릴레이가 {} 안에 멈추지 않아 끊는다 — 표시하지 못한 행은 다음 기동 때 다시 보낸다",
						STOP_TIMEOUT);
				thread.interrupt();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
