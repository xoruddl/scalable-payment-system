package com.remittance.account.saga;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.domain.ProcessedEvent;
import com.remittance.account.outbox.BalanceJournal;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.ProcessedEventRepository;
import com.remittance.account.service.BalanceShards;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Saga 한 단계를 하나의 트랜잭션으로 실행한다. 셋이 함께 커밋되어야 한다.
 *   1. 처리 흔적({@link ProcessedEvent}) — 같은 이벤트가 다시 와도 두 번 처리하지 않기 위해
 *   2. 잔액 변경
 *   3. 다음 단계 이벤트를 Outbox에 기록
 *
 * 하나라도 밖으로 나가면 흐름이 끊긴다.
 * 처리 흔적만 남고 잔액이 롤백되면 재전송이 와도 건너뛰어 돈이 움직이지 않은 채 진행되고,
 * 잔액만 바뀌고 이벤트가 유실되면 Saga가 중간에서 멈춘다.
 *
 * 트랜잭션 프록시가 걸리려면 호출부가 다른 빈을 통해 이 메서드를 불러야 한다.
 * 같은 클래스 안에서 부르면(self-invocation) {@code @Transactional}이 적용되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SagaStepExecutor {

	private static final String AGGREGATE_TYPE = "Account";

	private final BalanceShards balanceShards;
	private final ProcessedEventRepository processedEventRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final BalanceJournal balanceJournal;
	private final ObjectMapper objectMapper;
	private final SagaStepMetrics metrics;

	/**
	 * @param consumed 방금 소비한 이벤트. 중복 판정 키가 된다.
	 * @param step     무엇을 바꾸고 무엇을 발행할지. 계좌는 호출부가 이미 락을 잡고 있어야 한다.
	 * @param shardNo  입금이면 넣을 조각, 출금이면 {@code BalanceGuard.ALL_SHARDS}.
	 *                 락 키와 같은 번호여야 한다 — 다르면 잠그지 않은 조각을 만지게 된다.
	 */
	@Transactional
	public void execute(ConsumedEvent consumed, SagaStep step, short shardNo) {
		SagaStepMetrics.Measurement measurement = metrics.start(consumed.type());
		UUID transferId = consumed.transferId();
		BalanceChange change = step.balanceChange();

		// 이미 처리한 이벤트면 PK 중복으로 여기서 DataIntegrityViolationException이 난다.
		// 조회 후 INSERT가 아니라 INSERT 먼저인 이유는, 두 스레드가 동시에 "없다"를 보는 경합을 막기 위함.
		measurement.record(SagaStepMetrics.DEDUPLICATION_FLUSH,
				() -> {
					processedEventRepository.saveAndFlush(new ProcessedEvent(consumed.type(), transferId));
				});

		// 방향이 읽을 조각 수를 정한다 — 넣는 것은 조각 하나, 빼는 것은 전부.
		AccountBalance balance = measurement.record(SagaStepMetrics.BALANCE_LOAD,
				() -> balanceShards.load(step.accountId(), step.direction(), shardNo));
		step.mutate(balance);
		measurement.record(SagaStepMetrics.BALANCE_FLUSH, () -> balanceShards.flush(balance));

		measurement.record(SagaStepMetrics.OUTBOX_ENQUEUE, () -> {
			record(transferId, step.nextEvent(balance));
			// 잔액이 움직였으면 반드시 분개장에도 남는다 — 입출금 API와 같은 규칙이다.
			balanceJournal.record(balance, change.reason(), change.direction(), change.amount(), transferId);
		});
		measurement.markWorkFinished();
	}

	/**
	 * 단계가 업무적으로 실패했을 때 그 사실을 이벤트로 남긴다.
	 *
	 * {@link #execute}가 예외로 끝나면 처리 흔적까지 함께 롤백되므로, 실패 사실을 남기는 것도
	 * 별도의 트랜잭션이어야 한다. 여기서 흔적을 남기지 않으면 재전송이 올 때마다
	 * 실패 이벤트가 계속 새로 발행된다 — 잔액이 부족한 송금은 몇 번을 다시 해도 부족하므로,
	 * 실패했다는 사실 자체를 "처리 완료"로 봐야 한다.
	 */
	@Transactional
	public void recordFailure(ConsumedEvent consumed, NextEvent failure) {
		UUID transferId = consumed.transferId();
		processedEventRepository.saveAndFlush(new ProcessedEvent(consumed.type(), transferId));
		record(transferId, failure);
	}

	private void record(UUID transferId, NextEvent event) {
		outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType(AGGREGATE_TYPE)
				// 파티션 키로 쓰이므로 계좌가 아니라 송금 ID다. 같은 송금의 이벤트 순서를 지켜야 한다.
				.aggregateId(transferId)
				.eventType(event.type())
				.payload(objectMapper.writeValueAsString(event.body()))
				.build());
	}
}
