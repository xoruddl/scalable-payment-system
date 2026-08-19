package com.remittance.transfer.messaging;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.outbox.OutboxEvent;
import com.remittance.transfer.outbox.OutboxEventRepository;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 2 Step 4a — 컨슈머 배선 검증.
 *
 * <p>단위 테스트는 상태 전이 규칙만 본다. 여기서는 <b>진짜 Kafka에 올린 JSON이</b>
 * 토픽명·역직렬화·리스너 등록을 모두 거쳐 상태 갱신까지 도달하는지를 확인한다.
 * 이 배선은 셋 중 하나만 어긋나도 조용히 아무 일도 일어나지 않기 때문에,
 * 사람이 서비스를 띄워보기 전에는 알아채기 어렵다.
 */
@SpringBootTest
class TransferSagaConsumerTest extends AbstractIntegrationTest {

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private TransferRepository transferRepository;

	@Autowired
	private TransferOutboxRecorder outboxRecorder;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	private final BigDecimal amount = new BigDecimal("1000.00");

	private Transfer acceptedTransfer() {
		return outboxRecorder.record(
				Transfer.builder()
						.fromAccountId(UUID.randomUUID())
						.toAccountId(UUID.randomUUID())
						.amount(amount)
						.currency("KRW")
						.build(),
				TransferEventType.REQUESTED);
	}

	private void publish(String topic, Object payload, UUID transferId) {
		kafkaTemplate.send(topic, transferId.toString(), objectMapper.writeValueAsString(payload)).join();
	}

	private void awaitStatus(UUID transferId, TransferStatus expected) {
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(transferRepository.findByTransferId(transferId))
						.get()
						.extracting(Transfer::getStatus)
						.isEqualTo(expected));
	}

	@Test
	void 출금_입금_원장기록_순으로_받으면_마지막에야_COMPLETED가_된다() {
		Transfer transfer = acceptedTransfer();
		UUID transferId = transfer.getTransferId();

		publish(TransferEvents.DEBITED,
				new TransferEvents.Debited(transferId, BigDecimal.valueOf(4_000), Instant.now()), transferId);
		awaitStatus(transferId, TransferStatus.DEBIT_COMPLETED);

		publish(TransferEvents.CREDITED, new TransferEvents.Credited(
				transferId, BigDecimal.valueOf(4_000), BigDecimal.valueOf(6_000), Instant.now()), transferId);
		awaitStatus(transferId, TransferStatus.CREDIT_COMPLETED);

		publish(TransferEvents.LEDGER_RECORDED,
				new TransferEvents.LedgerRecorded(transferId, Instant.now()), transferId);
		awaitStatus(transferId, TransferStatus.COMPLETED);

		List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId);
		assertThat(events).extracting(OutboxEvent::getEventType)
				.as("접수 시점의 requested와 완료 시점의 completed가 남는다")
				.containsExactly(TransferEventType.REQUESTED.topic(), TransferEventType.COMPLETED.topic());
	}

	/**
	 * Outbox 릴레이는 at-least-once라 같은 이벤트가 다시 올 수 있다.
	 * 완료된 송금에 뒤늦은 출금 이벤트가 도착해도 상태가 되돌아가면 안 된다.
	 */
	@Test
	void 완료된_송금에_지난_이벤트가_다시_와도_되돌아가지_않는다() {
		Transfer transfer = acceptedTransfer();
		UUID transferId = transfer.getTransferId();

		publish(TransferEvents.DEBITED,
				new TransferEvents.Debited(transferId, BigDecimal.valueOf(4_000), Instant.now()), transferId);
		awaitStatus(transferId, TransferStatus.DEBIT_COMPLETED);
		publish(TransferEvents.CREDITED, new TransferEvents.Credited(
				transferId, BigDecimal.valueOf(4_000), BigDecimal.valueOf(6_000), Instant.now()), transferId);
		awaitStatus(transferId, TransferStatus.CREDIT_COMPLETED);
		publish(TransferEvents.LEDGER_RECORDED,
				new TransferEvents.LedgerRecorded(transferId, Instant.now()), transferId);
		awaitStatus(transferId, TransferStatus.COMPLETED);

		Instant completedAt = transferRepository.findByTransferId(transferId).orElseThrow().getCompletedAt();

		// 재전송
		publish(TransferEvents.DEBITED,
				new TransferEvents.Debited(transferId, BigDecimal.valueOf(4_000), Instant.now()), transferId);
		publish(TransferEvents.LEDGER_RECORDED,
				new TransferEvents.LedgerRecorded(transferId, Instant.now()), transferId);

		await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			Transfer reloaded = transferRepository.findByTransferId(transferId).orElseThrow();
			assertThat(reloaded.getStatus()).isEqualTo(TransferStatus.COMPLETED);
			assertThat(reloaded.getCompletedAt())
					.as("완료 시각이 재전송 때마다 갱신되면 멱등하지 않다는 뜻이다")
					.isEqualTo(completedAt);
			assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
					.as("완료 이벤트가 두 번 발행되면 알림도 두 번 간다")
					.hasSize(2);
		});
	}
}
