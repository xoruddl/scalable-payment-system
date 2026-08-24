package com.remittance.account.saga;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.domain.ProcessedEvent;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.outbox.BalanceJournal;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Saga 한 단계를 <b>하나의 트랜잭션</b>으로 실행한다. 셋이 함께 커밋되어야 한다.
 * <ol>
 *   <li>처리 흔적({@link ProcessedEvent}) — 같은 이벤트가 다시 와도 두 번 처리하지 않기 위해</li>
 *   <li>잔액 변경</li>
 *   <li>다음 단계 이벤트를 Outbox에 기록</li>
 * </ol>
 *
 * <p>하나라도 밖으로 나가면 흐름이 끊긴다.
 * 처리 흔적만 남고 잔액이 롤백되면 재전송이 와도 건너뛰어 <b>돈이 움직이지 않은 채 진행</b>되고,
 * 잔액만 바뀌고 이벤트가 유실되면 <b>Saga가 중간에서 멈춘다</b>.
 *
 * <p>트랜잭션 프록시가 걸리려면 호출부가 <b>다른 빈을 통해</b> 이 메서드를 불러야 한다.
 * 같은 클래스 안에서 부르면(self-invocation) {@code @Transactional}이 적용되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SagaStepExecutor {

	private static final String AGGREGATE_TYPE = "Account";

	private final com.remittance.account.service.BalanceShards balanceShards;
	private final ProcessedEventRepository processedEventRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final BalanceJournal balanceJournal;
	private final ObjectMapper objectMapper;

	/**
	 * 이 단계가 잔액을 어떻게 움직였는지 — 분개장에 남길 내용이다.
	 * 금액만으로는 "송금 출금"과 "보상 환불"을 구분할 수 없어 이유를 함께 넘긴다.
	 */
	public record BalanceChange(
			AccountEvents.BalanceChangeReason reason,
			AccountEvents.TransactionDirection direction,
			java.math.BigDecimal amount
	) {
	}

	/**
	 * @param consumedEventType 방금 소비한 이벤트 타입. 중복 판정 키의 일부가 된다.
	 * @param accountId         잔액을 변경할 계좌. 호출부가 이 계좌의 락을 이미 잡고 있어야 한다.
	 * @param nextEventBody     변경 <b>후</b>의 잔액을 받아 다음 이벤트 본문을 만든다.
	 * @param balanceChange     분개장에 남길 내용. 잔액 변경과 같은 트랜잭션에 들어가야 한다.
	 */
	@Transactional
	public void execute(String consumedEventType, UUID transferId, UUID accountId,
			Consumer<AccountBalance> mutation, String nextEventType,
			Function<AccountBalance, Object> nextEventBody, BalanceChange balanceChange) {
		// 이미 처리한 이벤트면 PK 중복으로 여기서 DataIntegrityViolationException이 난다.
		// 조회 후 INSERT가 아니라 INSERT 먼저인 이유는, 두 스레드가 동시에 "없다"를 보는 경합을 막기 위함.
		processedEventRepository.saveAndFlush(new ProcessedEvent(consumedEventType, transferId));

		// 방향이 읽을 조각 수를 정한다 — 넣는 것은 조각 하나, 빼는 것은 전부.
		AccountBalance balance = balanceShards.load(accountId, balanceChange.direction());
		mutation.accept(balance);
		balanceShards.flush(balance);

		record(transferId, nextEventType, nextEventBody.apply(balance));
		// 잔액이 움직였으면 반드시 분개장에도 남는다 — 입출금 API와 같은 규칙이다.
		balanceJournal.record(balance, balanceChange.reason(), balanceChange.direction(),
				balanceChange.amount(), transferId);
	}

	/**
	 * 단계가 <b>업무적으로</b> 실패했을 때 그 사실을 이벤트로 남긴다.
	 *
	 * <p>{@link #execute}가 예외로 끝나면 처리 흔적까지 함께 롤백되므로, 실패 사실을 남기는 것도
	 * 별도의 트랜잭션이어야 한다. 여기서 흔적을 남기지 않으면 재전송이 올 때마다
	 * <b>실패 이벤트가 계속 새로 발행된다</b> — 잔액이 부족한 송금은 몇 번을 다시 해도 부족하므로,
	 * 실패했다는 사실 자체를 "처리 완료"로 봐야 한다.
	 */
	@Transactional
	public void recordFailure(String consumedEventType, UUID transferId,
			String failureEventType, Object failureEventBody) {
		processedEventRepository.saveAndFlush(new ProcessedEvent(consumedEventType, transferId));
		record(transferId, failureEventType, failureEventBody);
	}

	private void record(UUID transferId, String eventType, Object body) {
		outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType(AGGREGATE_TYPE)
				// 파티션 키로 쓰이므로 계좌가 아니라 송금 ID다. 같은 송금의 이벤트 순서를 지켜야 한다.
				.aggregateId(transferId)
				.eventType(eventType)
				.payload(objectMapper.writeValueAsString(body))
				.build());
	}
}
