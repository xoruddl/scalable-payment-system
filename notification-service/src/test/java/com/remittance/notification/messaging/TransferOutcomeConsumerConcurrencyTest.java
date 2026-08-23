package com.remittance.notification.messaging;

import com.remittance.notification.AbstractIntegrationTest;
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
 * Phase 6 Step 2 — 알림 리스너가 파티션 수만큼 스레드를 쓰는지 본다.
 *
 * <p>2026-08-23 측정에서 <b>종결 경로가 전부 비워진 뒤에도 여기만 밀렸다.</b>
 * 자원이 모자란 게 아니라(MySQL 8.7%, 이 서비스 3.3%) 스레드가 하나라 커밋 네 번을
 * 순차로 기다린 결과였다.
 *
 * <p>틀려도 기능은 정상이고 예외도 로그도 없다 — <b>알림이 늦게 갈 뿐이다.</b>
 * 그래서 설정값을 되읽지 않고 <b>실제로 만들어진 자식 컨테이너 수</b>를 센다.
 */
@SpringBootTest
class TransferOutcomeConsumerConcurrencyTest extends AbstractIntegrationTest {

	private static final List<String> CONSUMED_TOPICS =
			List.of(TransferEvents.COMPLETED, TransferEvents.FAILED);

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
	 * 스레드는 파티션을 하나씩 맡는다. 파티션보다 많이 띄우면 남는 스레드는 할 일이 없다.
	 * 더 올리려면 파티션부터 늘려야 한다는 제약을 여기서 못 박는다.
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
