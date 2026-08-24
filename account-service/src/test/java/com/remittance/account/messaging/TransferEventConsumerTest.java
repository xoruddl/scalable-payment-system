package com.remittance.account.messaging;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
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
 * Phase 2 Step 4a — 컨슈머 배선 검증.
 *
 * <p>{@code TransferSagaServiceTest}는 도메인 처리를 직접 호출해 검증한다.
 * 여기서는 <b>Transfer Service가 실제로 발행하는 모양의 JSON</b>이 토픽명·역직렬화·리스너 등록을
 * 모두 거쳐 출금까지 도달하는지 본다. 셋 중 하나만 어긋나도 아무 일도 일어나지 않는데,
 * 예외가 나지 않으니 서비스를 띄워보기 전에는 알아채기 어렵다.
 */
@SpringBootTest
class TransferEventConsumerTest extends AbstractIntegrationTest {

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
	void transfer_requested를_받으면_출금하고_transfer_debited를_남긴다() {
		Account from = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(from.getAccountId(), BigDecimal.valueOf(5_000), "KRW");
		UUID transferId = UUID.randomUUID();

		// Transfer Service의 TransferEventPayload와 같은 모양. 이 서비스가 쓰지 않는 필드(status,
		// requestedAt 등)가 섞여 있어도 무시하고 읽을 수 있어야 한다.
		String payload = objectMapper.writeValueAsString(java.util.Map.of(
				"transferId", transferId,
				"status", "PENDING",
				"fromAccountId", from.getAccountId(),
				"toAccountId", UUID.randomUUID(),
				"amount", new BigDecimal("1000.00"),
				"currency", "KRW",
				"occurredAt", "2026-08-19T00:00:00Z"));

		kafkaTemplate.send(TransferEvents.REQUESTED, transferId.toString(), payload).join();

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(accountService.getBalance(from.getAccountId()).total())
					.isEqualByComparingTo("4000.00");
			assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
					.singleElement()
					.extracting(OutboxEvent::getEventType)
					.isEqualTo(TransferEvents.DEBITED);
		});
	}
}
