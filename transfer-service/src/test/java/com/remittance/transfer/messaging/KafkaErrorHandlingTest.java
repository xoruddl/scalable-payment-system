package com.remittance.transfer.messaging;

import com.remittance.transfer.AbstractIntegrationTest;
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
 * <p>spring-kafka 기본 동작은 지연 없이 10번 시도한 뒤 로그만 남기고 오프셋을 커밋한다.
 * 즉 메시지가 조용히 없어진다. 이 서비스가 이벤트를 잃으면 송금 상태가 실제와 어긋난 채 남으므로,
 * 끝내 처리 못 한 건 DLT에 남겨야 한다.
 */
@SpringBootTest
@Import(KafkaErrorHandlingTest.DeadLetterProbe.class)
class KafkaErrorHandlingTest extends AbstractIntegrationTest {

	private static final String DEBITED_DLT = TransferEvents.DEBITED + ".DLT";

	@TestConfiguration
	static class DeadLetterProbe {

		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@KafkaListener(topics = DEBITED_DLT, groupId = "dead-letter-probe")
		void onDeadLetter(String payload) {
			received.add(payload);
		}
	}

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private DeadLetterProbe deadLetterProbe;

	/** 본문을 읽을 수 없는 메시지는 몇 번을 다시 읽어도 못 읽는다. 재시도 대신 바로 DLT로 가야 한다. */
	@Test
	void 읽을_수_없는_메시지는_버려지지_않고_DLT로_간다() {
		UUID transferId = UUID.randomUUID();
		String broken = "{\"transferId\":\"" + transferId + "\", 이건 JSON이 아니다";

		kafkaTemplate.send(TransferEvents.DEBITED, transferId.toString(), broken).join();

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
				assertThat(deadLetterProbe.received)
						.anySatisfy(payload -> assertThat(payload).contains(transferId.toString())));
	}
}
