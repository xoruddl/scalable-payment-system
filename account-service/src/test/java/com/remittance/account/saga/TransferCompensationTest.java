package com.remittance.account.saga;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 2 Step 4b — <b>보상이 실제로 한 바퀴 도는지</b>.
 *
 * <p>Step 0의 재현 테스트 #4가 지적한 문제다. 출금은 성공했는데 입금이 실패하면 돈이 공중에 뜬 채로
 * 멈춘다 — 출금 계좌에서는 빠졌고, 입금 계좌에는 들어가지 않았고, 아무도 되돌리지 않는다.
 *
 * <p>{@link TransferSagaServiceTest}는 각 단계를 하나씩 직접 불러 검증한다. 여기서는
 * <b>진짜 Kafka와 진짜 Outbox 릴레이를 통해</b> 이벤트가 스스로 다음을 부르는지를 본다.
 * 보상 흐름은 세 번의 배달을 거치므로(credit-failed 발행 → 수신 → debit-reversed 발행),
 * 배선이 한 군데만 어긋나도 조용히 멈춘다 — 단계별 테스트로는 그걸 못 잡는다.
 *
 * <p>릴레이를 켜는 유일한 테스트라 컨텍스트가 따로 뜬다. 느린 대신, 이 서비스 안에서
 * 이벤트가 자기 자신을 다시 부르는 고리가 실제로 닫히는지 확인할 수 있다.
 */
@SpringBootTest(properties = "outbox.relay.enabled=true")
class TransferCompensationTest extends AbstractIntegrationTest {

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void 입금이_실패하면_출금이_저절로_되돌아온다() {
		Account from = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(from.getAccountId(), BigDecimal.valueOf(5_000), "KRW");
		// 통화가 다른 계좌라 입금 단계에서 반드시 실패한다
		Account to = accountService.createAccount(UUID.randomUUID(), "USD", AccountType.PERSONAL);
		UUID transferId = UUID.randomUUID();

		// Transfer Service가 접수 후 발행하는 이벤트. 여기서부터는 아무도 지시하지 않는다.
		TransferEvents.Requested requested = new TransferEvents.Requested(
				transferId, from.getAccountId(), to.getAccountId(), new BigDecimal("1000.00"), "KRW");
		kafkaTemplate.send(TransferEvents.REQUESTED, transferId.toString(),
				objectMapper.writeValueAsString(requested)).join();

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
			assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
					.extracting(OutboxEvent::getEventType)
					.as("출금 → 입금실패 → 환불의 세 이벤트가 스스로 이어져야 한다")
					.containsExactly(TransferEvents.DEBITED, TransferEvents.CREDIT_FAILED,
							TransferEvents.DEBIT_REVERSED);
			assertThat(accountRepository.findByAccountId(from.getAccountId()).orElseThrow().getBalance())
					.as("보상이 끝나면 출금 전 잔액으로 돌아와 있어야 한다")
					.isEqualByComparingTo("5000.00");
		});
	}
}
