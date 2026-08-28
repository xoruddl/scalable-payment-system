package com.remittance.account.saga;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Saga 잔액 변경 트랜잭션의 <b>80ms가 어디서 쓰이는지</b> 가르는 계측기 (Phase 6 Step 1).
 *
 * <p>{@code save()}는 SQL을 즉시 실행하지 않고 커밋까지 미룰 수 있다. 그래서 Outbox 구간을
 * "INSERT"라고 부르지 않고 {@code outbox_enqueue}라고 부른다. 실제 지연 쓰기와 커밋은
 * 메서드 본문이 끝난 뒤 트랜잭션 프록시에서 일어나므로 {@code deferred_writes_and_commit}이 잰다.
 * 이름을 잘못 붙이면 그래프는 맞아도 결론이 틀린다.
 *
 * <p>이벤트 종류와 단계는 코드에 정해진 작은 집합이라 태그 cardinality가 제한된다.
 * transferId나 accountId는 절대 태그로 싣지 않는다.
 */
@Component
public class SagaStepMetrics {

	public static final String METRIC_STAGE = "remittance.account.saga.stage";
	public static final String METRIC_TRANSACTION = "remittance.account.saga.transaction";

	public static final String DEDUPLICATION_FLUSH = "deduplication_flush";
	public static final String BALANCE_LOAD = "balance_load";
	public static final String BALANCE_FLUSH = "balance_flush";
	public static final String OUTBOX_ENQUEUE = "outbox_enqueue";
	public static final String DEFERRED_WRITES_AND_COMMIT = "deferred_writes_and_commit";

	private final MeterRegistry meterRegistry;

	public SagaStepMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/** 이미 열린 {@code @Transactional} 경계 안에서 한 번만 시작한다. */
	public Measurement start(String eventType) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("Saga 단계 계측은 트랜잭션 안에서 시작해야 한다");
		}
		return new Measurement(eventType);
	}

	public final class Measurement {

		private final String eventType;
		private final long transactionStartedAt = System.nanoTime();
		private Long workFinishedAt;

		private Measurement(String eventType) {
			this.eventType = eventType;
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					long completedAt = System.nanoTime();
					transactionTimer(outcome(status)).record(
							completedAt - transactionStartedAt, TimeUnit.NANOSECONDS);
					if (workFinishedAt != null) {
						stageTimer(DEFERRED_WRITES_AND_COMMIT).record(
								completedAt - workFinishedAt, TimeUnit.NANOSECONDS);
					}
				}
			});
		}

		public void record(String stage, Runnable action) {
			stageTimer(stage).record(action);
		}

		public <T> T record(String stage, Supplier<T> action) {
			return stageTimer(stage).record(action);
		}

		/**
		 * 메서드 본문의 작업이 끝난 시각. 여기부터 트랜잭션 완료 콜백까지가
		 * 지연된 INSERT·flush·commit 구간이다.
		 */
		public void markWorkFinished() {
			if (workFinishedAt != null) {
				throw new IllegalStateException("Saga 단계 작업 종료는 한 번만 표시할 수 있다");
			}
			workFinishedAt = System.nanoTime();
		}

		private Timer stageTimer(String stage) {
			return Timer.builder(METRIC_STAGE)
					.description("Account Saga 트랜잭션 내부 단계별 처리 시간")
					.tag("event", eventType)
					.tag("stage", stage)
					.register(meterRegistry);
		}

		private Timer transactionTimer(String outcome) {
			return Timer.builder(METRIC_TRANSACTION)
					.description("커밋 또는 롤백까지 포함한 Account Saga 트랜잭션 전체 시간")
					.tag("event", eventType)
					.tag("outcome", outcome)
					.register(meterRegistry);
		}
	}

	private static String outcome(int status) {
		return switch (status) {
			case TransactionSynchronization.STATUS_COMMITTED -> "committed";
			case TransactionSynchronization.STATUS_ROLLED_BACK -> "rolled_back";
			default -> "unknown";
		};
	}
}
