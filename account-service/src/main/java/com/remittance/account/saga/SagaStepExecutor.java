package com.remittance.account.saga;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.ProcessedEvent;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
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

	private final AccountRepository accountRepository;
	private final ProcessedEventRepository processedEventRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	/**
	 * @param consumedEventType 방금 소비한 이벤트 타입. 중복 판정 키의 일부가 된다.
	 * @param accountId         잔액을 변경할 계좌. 호출부가 이 계좌의 락을 이미 잡고 있어야 한다.
	 * @param nextEventBody     변경 <b>후</b>의 계좌를 받아 다음 이벤트 본문을 만든다.
	 */
	@Transactional
	public void execute(String consumedEventType, UUID transferId, UUID accountId,
			Consumer<Account> mutation, String nextEventType, Function<Account, Object> nextEventBody) {
		// 이미 처리한 이벤트면 PK 중복으로 여기서 DataIntegrityViolationException이 난다.
		// 조회 후 INSERT가 아니라 INSERT 먼저인 이유는, 두 스레드가 동시에 "없다"를 보는 경합을 막기 위함.
		processedEventRepository.saveAndFlush(new ProcessedEvent(consumedEventType, transferId));

		Account account = accountRepository.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		mutation.accept(account);
		accountRepository.saveAndFlush(account);

		outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType(AGGREGATE_TYPE)
				// 파티션 키로 쓰이므로 계좌가 아니라 송금 ID다. 같은 송금의 이벤트 순서를 지켜야 한다.
				.aggregateId(transferId)
				.eventType(nextEventType)
				.payload(objectMapper.writeValueAsString(nextEventBody.apply(account)))
				.build());
	}
}
