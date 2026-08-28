package com.remittance.account.saga;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 6 — 입금 한 건의 80ms를 단계별로 볼 수 있는지 고정한다. */
@SpringBootTest
class SagaStepMetricsTest extends AbstractIntegrationTest {

	@Autowired
	private TransferSagaService transferSagaService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	void 입금_트랜잭션의_각_구간과_커밋까지_한_번씩_잰다() {
		TransferEvents.Debited event = debitedEvent();
		String[] stages = {
				SagaStepMetrics.DEDUPLICATION_FLUSH,
				SagaStepMetrics.BALANCE_LOAD,
				SagaStepMetrics.BALANCE_FLUSH,
				SagaStepMetrics.OUTBOX_ENQUEUE,
				SagaStepMetrics.DEFERRED_WRITES_AND_COMMIT
		};
		long[] before = new long[stages.length];
		for (int i = 0; i < stages.length; i++) {
			before[i] = stageCount(TransferEvents.DEBITED, stages[i]);
		}
		long transactionBefore = transactionCount(TransferEvents.DEBITED, "committed");

		transferSagaService.onDebited(event);

		for (int i = 0; i < stages.length; i++) {
			assertThat(stageCount(TransferEvents.DEBITED, stages[i]))
					.as("입금의 %s 구간을 정확히 한 번 재야 한다", stages[i])
					.isEqualTo(before[i] + 1);
		}
		assertThat(transactionCount(TransferEvents.DEBITED, "committed"))
				.as("트랜잭션 프록시의 커밋이 끝난 뒤 전체 시간을 기록해야 한다")
				.isEqualTo(transactionBefore + 1);
	}

	@Test
	void 중복_이벤트로_롤백된_트랜잭션도_성공과_섞지_않고_잰다() {
		TransferEvents.Debited event = debitedEvent();
		transferSagaService.onDebited(event);
		long rollbackBefore = transactionCount(TransferEvents.DEBITED, "rolled_back");
		long loadBefore = stageCount(TransferEvents.DEBITED, SagaStepMetrics.BALANCE_LOAD);

		// processed_events의 PK 중복으로 첫 flush에서 롤백된다. 재전송은 정상 상황이라 호출자는 삼킨다.
		transferSagaService.onDebited(event);

		assertThat(transactionCount(TransferEvents.DEBITED, "rolled_back"))
				.as("실패한 시도를 committed 지연에 섞으면 병목 결론이 흐려진다")
				.isEqualTo(rollbackBefore + 1);
		assertThat(stageCount(TransferEvents.DEBITED, SagaStepMetrics.BALANCE_LOAD))
				.as("중복은 처리 흔적에서 막혀 잔액을 읽기 전에 끝나야 한다")
				.isEqualTo(loadBefore);
	}

	private TransferEvents.Debited debitedEvent() {
		Account from = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		Account to = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		return TransferEvents.Debited.internal(
				UUID.randomUUID(), from.getAccountId(), to.getAccountId(),
				new BigDecimal("1000.00"), "KRW", new BigDecimal("4000.00"), Instant.now());
	}

	private long stageCount(String event, String stage) {
		Timer timer = meterRegistry.find(SagaStepMetrics.METRIC_STAGE)
				.tags("event", event, "stage", stage)
				.timer();
		return timer == null ? 0 : timer.count();
	}

	private long transactionCount(String event, String outcome) {
		Timer timer = meterRegistry.find(SagaStepMetrics.METRIC_TRANSACTION)
				.tags("event", event, "outcome", outcome)
				.timer();
		return timer == null ? 0 : timer.count();
	}
}
