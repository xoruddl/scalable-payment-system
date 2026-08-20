package com.remittance.ledger.messaging;

import com.remittance.ledger.AbstractIntegrationTest;
import com.remittance.ledger.domain.BalanceChangeReason;
import com.remittance.ledger.domain.Transaction;
import com.remittance.ledger.domain.TransactionDirection;
import com.remittance.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
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
 * Phase 2 Step 5a — 원장이 <b>모든 잔액 변경</b>을 받아 적는다.
 *
 * <p>전에는 {@code transfer.credited}를 듣고 송금 한 건을 두 줄로 적었다. 그러면 송금이 아닌
 * 잔액 변경(입출금, 보상 환불)이 원장에 남지 않아 <b>"원장 합 = 잔액"이 성립하지 않았다.</b>
 * 이제 잔액이 움직인 사실 하나가 원장 한 줄이 된다.
 */
@SpringBootTest
@Import(BalanceChangedConsumerTest.LedgerRecordedProbe.class)
class BalanceChangedConsumerTest extends AbstractIntegrationTest {

	@Autowired
	private LedgerRecordedProbe recordedProbe;

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private ObjectMapper objectMapper;

	private final BigDecimal amount = new BigDecimal("1000.00");

	private AccountEvents.BalanceChanged entry(UUID accountId, UUID transferId,
			BalanceChangeReason reason, TransactionDirection direction, String balanceAfter) {
		return new AccountEvents.BalanceChanged(UUID.randomUUID(), accountId, reason, direction,
				amount, new BigDecimal(balanceAfter), "KRW", transferId, Instant.now());
	}

	private void publish(AccountEvents.BalanceChanged event) {
		kafkaTemplate.send(AccountEvents.BALANCE_CHANGED, event.accountId().toString(),
				objectMapper.writeValueAsString(event)).join();
	}

	private List<Transaction> entriesOf(UUID accountId) {
		return transactionRepository.findAll()
				.filter(t -> t.getAccountId().equals(accountId))
				.collectList().block();
	}

	@Test
	void 송금과_무관한_입금도_원장에_남는다() {
		UUID accountId = UUID.randomUUID();

		publish(entry(accountId, null, BalanceChangeReason.DEPOSIT, TransactionDirection.CREDIT, "1000.00"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(entriesOf(accountId))
						.as("이게 빠지면 원장 합과 잔액이 어긋나 대사가 무의미해진다")
						.singleElement()
						.satisfies(t -> {
							assertThat(t.getReason()).isEqualTo(BalanceChangeReason.DEPOSIT);
							assertThat(t.getTransferId()).isNull();
							assertThat(t.getBalanceAfter()).isEqualByComparingTo("1000.00");
						}));
	}

	/** 보상까지 간 송금은 출금과 환불이 각각 한 줄씩 남아, 합이 0이 되어 잔액과 맞는다. */
	@Test
	void 보상_환불도_원장에_남아_합이_0이_된다() {
		UUID accountId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();

		publish(entry(accountId, transferId, BalanceChangeReason.TRANSFER_DEBIT,
				TransactionDirection.DEBIT, "4000.00"));
		publish(entry(accountId, transferId, BalanceChangeReason.TRANSFER_REFUND,
				TransactionDirection.CREDIT, "5000.00"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(entriesOf(accountId))
						.hasSize(2)
						.extracting(Transaction::getReason)
						.containsExactlyInAnyOrder(
								BalanceChangeReason.TRANSFER_DEBIT, BalanceChangeReason.TRANSFER_REFUND));
	}

	/** 이벤트는 at-least-once다. 같은 줄이 두 번 생기면 그 즉시 잔액과 어긋난다. */
	@Test
	void 같은_변경을_두_번_받아도_원장은_한_줄이다() {
		UUID accountId = UUID.randomUUID();
		AccountEvents.BalanceChanged event =
				entry(accountId, null, BalanceChangeReason.DEPOSIT, TransactionDirection.CREDIT, "1000.00");

		publish(event);
		publish(event);

		await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(entriesOf(accountId)).hasSize(1));
	}

	/**
	 * 출금 줄과 입금 줄은 계좌가 달라 서로 다른 파티션으로 온다 — 도착 순서가 보장되지 않는다.
	 * 그래서 "입금을 적었으니 끝"이 아니라, <b>둘 다 모였을 때</b> 알려야 한다.
	 */
	@Test
	void 출금과_입금이_모두_기록되어야_원장_기록_완료를_알린다() {
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();

		// 입금 줄이 먼저 도착한 상황
		publish(entry(to, transferId, BalanceChangeReason.TRANSFER_CREDIT,
				TransactionDirection.CREDIT, "1000.00"));
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(entriesOf(to)).hasSize(1));
		assertThat(recordedAnnouncements(transferId))
				.as("한쪽만 적힌 상태에서 완료를 알리면 송금이 원장 없이 COMPLETED가 된다")
				.isZero();

		publish(entry(from, transferId, BalanceChangeReason.TRANSFER_DEBIT,
				TransactionDirection.DEBIT, "4000.00"));
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(recordedAnnouncements(transferId)).isPositive());
	}

	/** {@code transfer.ledger-recorded}에 이 송금 건이 몇 번 실렸는지 센다. */
	private long recordedAnnouncements(UUID transferId) {
		return recordedProbe.received.stream().filter(p -> p.contains(transferId.toString())).count();
	}

	/** 발행 여부를 보려면 누군가는 그 토픽을 듣고 있어야 한다. */
	@TestConfiguration
	static class LedgerRecordedProbe {

		final java.util.concurrent.BlockingQueue<String> received =
				new java.util.concurrent.LinkedBlockingQueue<>();

		@KafkaListener(topics = TransferEvents.LEDGER_RECORDED, groupId = "ledger-recorded-probe")
		void onRecorded(String payload) {
			received.add(payload);
		}
	}
}
