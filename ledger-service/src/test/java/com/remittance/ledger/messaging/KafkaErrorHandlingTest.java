package com.remittance.ledger.messaging;

import com.remittance.ledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 2 Step 4c — 처리할 수 없는 메시지가 <b>사라지지 않는지</b>.
 *
 * <p>여기서 메시지를 잃으면 원장에 거래 한 건이 통째로 빠지고, 원장 기록 이벤트가 나가지 않아
 * 송금도 CREDIT_COMPLETED에서 멈춘다. 무엇이 빠졌는지 알 수 있도록 DLT에 남겨야 한다.
 */
@SpringBootTest
@Import(KafkaErrorHandlingTest.DeadLetterProbe.class)
class KafkaErrorHandlingTest extends AbstractIntegrationTest {

	private static final String BALANCE_CHANGED_DLT = AccountEvents.BALANCE_CHANGED + ".DLT";

	@TestConfiguration
	static class DeadLetterProbe {

		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@KafkaListener(topics = BALANCE_CHANGED_DLT, groupId = "dead-letter-probe")
		void onDeadLetter(String payload) {
			received.add(payload);
		}
	}

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private DeadLetterProbe deadLetterProbe;

	@Test
	void 읽을_수_없는_메시지는_버려지지_않고_DLT로_간다() {
		UUID transferId = UUID.randomUUID();
		String broken = "{\"transferId\":\"" + transferId + "\", 이건 JSON이 아니다";

		kafkaTemplate.send(AccountEvents.BALANCE_CHANGED, transferId.toString(), broken).join();

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
				assertThat(deadLetterProbe.received)
						.anySatisfy(payload -> assertThat(payload).contains(transferId.toString())));
	}
}
