package com.remittance.transfer.outbox;

import com.remittance.transfer.AbstractKafkaIntegrationTest;
import com.remittance.transfer.domain.Transfer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 2 Step 3 — Outbox 릴레이 검증.
 * "DB에 커밋된 이벤트는 결국 Kafka로 발행된다"가 핵심 계약이다.
 */
@SpringBootTest(properties = "outbox.relay.enabled=true")
class OutboxRelayTest extends AbstractKafkaIntegrationTest {

	@Autowired
	private TransferOutboxRecorder outboxRecorder;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	private Transfer newTransfer() {
		return Transfer.builder()
				.fromAccountId(UUID.randomUUID())
				.toAccountId(UUID.randomUUID())
				.amount(BigDecimal.valueOf(1_000))
				.currency("KRW")
				.build();
	}

	@Test
	void 기록된_이벤트는_결국_Kafka로_발행되고_발행시각이_남는다() {
		Transfer transfer = outboxRecorder.record(newTransfer(), TransferEventType.REQUESTED);

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByIdAsc(transfer.getTransferId());
			assertThat(events).hasSize(1);
			assertThat(events.get(0).getPublishedAt())
					.as("발행이 끝나면 publishedAt이 채워져 다음 폴링에서 다시 보내지 않는다")
					.isNotNull();
		});
	}

	@Test
	void 발행된_메시지는_애그리거트ID를_키로_갖고_payload를_담는다() {
		Transfer completed = newTransfer();
		completed.markDebitCompleted();
		completed.markCreditCompleted();
		completed.markCompleted();
		Transfer transfer = outboxRecorder.record(completed, TransferEventType.COMPLETED);

		try (Consumer<String, String> consumer = newConsumer()) {
			consumer.subscribe(List.of(TransferEventType.COMPLETED.topic()));

			ConsumerRecord<String, String> found = await().atMost(Duration.ofSeconds(20))
					.until(() -> pollFor(consumer, transfer.getTransferId()), record -> record != null);

			assertThat(found.key())
					.as("같은 송금의 이벤트가 한 파티션에 모이도록 애그리거트 ID를 키로 쓴다")
					.isEqualTo(transfer.getTransferId().toString());
			assertThat(found.value()).contains(transfer.getTransferId().toString(), "COMPLETED");
		}
	}

	private ConsumerRecord<String, String> pollFor(Consumer<String, String> consumer, UUID transferId) {
		ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
		for (ConsumerRecord<String, String> record : records) {
			if (transferId.toString().equals(record.key())) {
				return record;
			}
		}
		return null;
	}

	private Consumer<String, String> newConsumer() {
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new StringDeserializer())
				.createConsumer();
	}
}
