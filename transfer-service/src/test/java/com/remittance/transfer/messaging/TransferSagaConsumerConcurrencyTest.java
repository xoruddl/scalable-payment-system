package com.remittance.transfer.messaging;

import com.remittance.transfer.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6 Step 2 — 이 서비스의 리스너가 파티션 수만큼 스레드를 쓰는지 본다.
 *
 * <p>2026-08-23 측정에서 앞단(account·ledger)이 lag 0으로 놀고 있는데 이 서비스만
 * 세 토픽을 혼자 비우고 있었다. 리스너마다 <b>스레드가 1개</b>였기 때문이다.
 *
 * <p>틀려도 아무 일이 안 일어난다는 게 이 설정의 성질이다 — 기능은 전부 정상이고 예외도
 * 로그도 없다. 느려질 뿐이라 <b>부하를 걸어 보기 전에는 알 방법이 없다.</b>
 * 그래서 {@code getConcurrency()}(설정값 되읽기)가 아니라 <b>실제로 만들어진 자식 컨테이너 수</b>를 센다.
 */
@SpringBootTest
class TransferSagaConsumerConcurrencyTest extends AbstractIntegrationTest {

	/** 이 서비스가 소비하는 토픽 전부. 전진 세 개와 보상 세 개. */
	private static final List<String> CONSUMED_TOPICS = List.of(
			TransferEvents.DEBITED, TransferEvents.CREDITED, TransferEvents.LEDGER_RECORDED,
			TransferEvents.DEBIT_FAILED, TransferEvents.CREDIT_FAILED, TransferEvents.DEBIT_REVERSED);

	private static final int EXPECTED_CONCURRENCY = 3;

	@Autowired
	private KafkaListenerEndpointRegistry registry;

	@Autowired
	private KafkaAdmin kafkaAdmin;

	@Test
	void 리스너마다_스레드가_파티션_수만큼_뜬다() {
		for (String topic : CONSUMED_TOPICS) {
			ConcurrentMessageListenerContainer<?, ?> container = containerFor(topic);

			await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
					assertThat(container.getContainers())
							.as("%s 리스너의 실제 스레드 수", topic)
							.hasSize(EXPECTED_CONCURRENCY));
		}
	}

	/**
	 * 스레드는 파티션을 하나씩 맡는다. 파티션보다 많이 띄우면 남는 스레드는 할 일이 없어
	 * <b>처리량은 그대로인데 리밸런싱 비용만 커진다.</b> 더 올리려면 파티션부터 늘려야 한다는
	 * 제약을 여기서 못 박는다.
	 */
	@Test
	void 스레드_수가_파티션_수를_넘지_않는다() {
		Map<String, TopicDescription> described =
				kafkaAdmin.describeTopics(CONSUMED_TOPICS.toArray(String[]::new));

		for (String topic : CONSUMED_TOPICS) {
			int partitions = described.get(topic).partitions().size();
			assertThat(containerFor(topic).getConcurrency())
					.as("%s 스레드 수는 파티션 %d개를 넘을 수 없다", topic, partitions)
					.isLessThanOrEqualTo(partitions);
		}
	}

	private ConcurrentMessageListenerContainer<?, ?> containerFor(String listenerId) {
		MessageListenerContainer container = registry.getListenerContainer(listenerId);
		assertThat(container).as("%s 리스너가 등록되지 않았다", listenerId).isNotNull();
		return (ConcurrentMessageListenerContainer<?, ?>) container;
	}
}
