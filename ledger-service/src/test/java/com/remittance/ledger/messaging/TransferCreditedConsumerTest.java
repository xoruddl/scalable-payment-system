package com.remittance.ledger.messaging;

import com.remittance.ledger.AbstractIntegrationTest;
import com.remittance.ledger.domain.Transaction;
import com.remittance.ledger.domain.TransactionDirection;
import com.remittance.ledger.repository.TransactionRepository;
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
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 2 Step 4a — 원장 컨슈머 검증.
 *
 * <p>확인하는 계약은 셋이다.
 * <ol>
 *   <li>{@code transfer.credited}를 받으면 출금/입금 두 줄이 원장에 남는다</li>
 *   <li>같은 이벤트를 다시 받아도 줄이 늘지 않는다 (at-least-once 대비)</li>
 *   <li>기록이 끝나면 {@code transfer.ledger-recorded}를 발행한다 — 이게 송금을 완료로 만든다</li>
 * </ol>
 */
@SpringBootTest
class TransferCreditedConsumerTest extends AbstractIntegrationTest {

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Value("${spring.kafka.bootstrap-servers}")
	private String bootstrapServers;

	private TransferEvents.Credited creditedEvent(UUID transferId, UUID from, UUID to) {
		return new TransferEvents.Credited(transferId, from, to,
				new BigDecimal("1000.00"), "KRW",
				new BigDecimal("4000.00"), new BigDecimal("6000.00"),
				// MongoDB는 밀리초까지만 담으므로 저장 전후 값을 같게 하려면 미리 잘라야 한다
				Instant.now().truncatedTo(ChronoUnit.MILLIS));
	}

	private void publishCredited(TransferEvents.Credited event) {
		kafkaTemplate.send(TransferEvents.CREDITED, event.transferId().toString(),
				objectMapper.writeValueAsString(event)).join();
	}

	private List<Transaction> recorded(UUID transferId) {
		return transactionRepository.findAll()
				.filter(transaction -> transaction.getTransferId().equals(transferId))
				.collectList()
				.block();
	}

	@Test
	void 입금_완료_이벤트를_받으면_출금과_입금_두_줄이_원장에_남는다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();

		publishCredited(creditedEvent(transferId, from, to));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(recorded(transferId))
						.extracting(Transaction::getAccountId, Transaction::getDirection)
						.containsExactlyInAnyOrder(
								org.assertj.core.groups.Tuple.tuple(from, TransactionDirection.DEBIT),
								org.assertj.core.groups.Tuple.tuple(to, TransactionDirection.CREDIT)));
	}

	@Test
	void 같은_이벤트를_두_번_받아도_원장은_두_줄뿐이다() {
		UUID transferId = UUID.randomUUID();
		TransferEvents.Credited event = creditedEvent(transferId, UUID.randomUUID(), UUID.randomUUID());

		publishCredited(event);
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recorded(transferId)).hasSize(2));

		List<UUID> firstIds = recorded(transferId).stream().map(Transaction::getTransactionId).sorted().toList();

		publishCredited(event);

		await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			List<Transaction> after = recorded(transferId);
			assertThat(after)
					.as("중복 기록은 잔액 합계와 원장 합계를 어긋나게 만든다")
					.hasSize(2);
			assertThat(after.stream().map(Transaction::getTransactionId).sorted().toList())
					.as("재수신 때 거래 ID가 바뀌면 조회 API가 돌려주던 값이 슬쩍 달라진다")
					.isEqualTo(firstIds);
		});
	}

	@Test
	void 원장_기록이_끝나면_ledger_recorded를_발행한다() {
		UUID transferId = UUID.randomUUID();

		try (Consumer<String, String> consumer = newConsumer()) {
			consumer.subscribe(List.of(TransferEvents.LEDGER_RECORDED));
			// 구독이 자리를 잡기 전에 발행하면 놓칠 수 있어, 먼저 한 번 poll 해 파티션을 할당받는다
			consumer.poll(Duration.ofSeconds(5));

			publishCredited(creditedEvent(transferId, UUID.randomUUID(), UUID.randomUUID()));

			ConsumerRecord<String, String> found = await().atMost(Duration.ofSeconds(30))
					.until(() -> pollFor(consumer, transferId), record -> record != null);

			assertThat(found.key())
					.as("같은 송금의 이벤트가 한 파티션에 모이도록 송금 ID를 키로 쓴다")
					.isEqualTo(transferId.toString());
			assertThat(found.value()).contains(transferId.toString());
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
				ConsumerConfig.GROUP_ID_CONFIG, "ledger-recorded-test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), new StringDeserializer())
				.createConsumer();
	}
}
