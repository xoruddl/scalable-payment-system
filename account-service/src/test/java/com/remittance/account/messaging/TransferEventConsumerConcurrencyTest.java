package com.remittance.account.messaging;

import com.remittance.account.AbstractIntegrationTest;
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
 * Phase 6 Step 2 — 리스너가 <b>정말로</b> 파티션 수만큼 스레드를 쓰는지 본다.
 *
 * <p>이 검증을 굳이 두는 이유는 두 가지다.
 *
 * <p>첫째, <b>틀려도 아무 일이 안 일어난다.</b> 스레드가 1개로 돌아가도 기능은 전부 정상이고
 * 예외도 로그도 없다. 느려질 뿐이라, 부하를 걸어 보기 전에는 알 방법이 없다.
 * 실제로 2026-08-22 측정에서 {@code transfer.requested}에 lag 13,800이 쌓인 뒤에야
 * 스레드가 1개라는 걸 알았다.
 *
 * <p>둘째, <b>설정이 조용히 무력화되기 쉽다.</b> {@code spring.kafka.listener.concurrency}로 줬다면
 * 테스트용 {@code application.yml}이 운영 설정을 가려 테스트는 스레드 1개로 돌았을 것이다
 * ({@code MetricsDistributionConfig}에서 이미 한 번 밟은 함정이다).
 *
 * <p>{@code getConcurrency()}가 아니라 <b>실제로 만들어진 자식 컨테이너 수</b>를 센다.
 * 설정값을 되읽는 건 자기 자신을 확인하는 것에 가깝고, 우리가 알고 싶은 건 "스레드가 정말 3개 떴나"다.
 */
@SpringBootTest
class TransferEventConsumerConcurrencyTest extends AbstractIntegrationTest {

	/** 이 서비스가 소비하는 토픽들. 셋 다 같은 수의 스레드를 써야 한다. */
	private static final List<String> CONSUMED_TOPICS =
			List.of(TransferEvents.REQUESTED, TransferEvents.DEBITED, TransferEvents.CREDIT_FAILED);

	@Autowired
	private KafkaListenerEndpointRegistry registry;

	@Autowired
	private KafkaAdmin kafkaAdmin;

	/**
	 * 기대값을 <b>브로커에 물어본 파티션 수에서 끌어온다.</b> 상수로 박아두면 파티션을 늘릴 때마다
	 * 테스트를 함께 고쳐야 하는데, 그건 검증이 아니라 <b>숫자를 두 곳에 적어두는 것</b>이다.
	 * 실제로 3 → 6으로 올릴 때 여기서 red가 났고, 고칠 곳은 코드가 아니라 이 상수였다.
	 *
	 * <p>이렇게 두면 검증하는 명제가 "지금 3이 맞나"에서
	 * <b>"스레드가 파티션을 남김없이 쓰고 있나"</b>로 바뀐다 — 그게 원래 알고 싶던 것이다.
	 */
	@Test
	void 리스너마다_스레드가_파티션_수만큼_뜬다() {
		Map<String, TopicDescription> described =
				kafkaAdmin.describeTopics(CONSUMED_TOPICS.toArray(String[]::new));

		for (String topic : CONSUMED_TOPICS) {
			int partitions = described.get(topic).partitions().size();
			ConcurrentMessageListenerContainer<?, ?> container = containerFor(topic);

			// 자식 컨테이너는 기동하면서 만들어지므로 잠깐 기다린다.
			await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
					assertThat(container.getContainers())
							.as("%s 리스너의 실제 스레드 수 (파티션 %d개)", topic, partitions)
							.hasSize(partitions));
		}
	}

	/**
	 * 스레드는 파티션을 하나씩 맡는다. 파티션보다 많이 띄우면 <b>남는 스레드는 할당받을 게 없어
	 * 놀기만 한다</b> — 처리량은 그대로인데 컨슈머만 늘어 리밸런싱 비용만 커진다.
	 *
	 * <p>그래서 이건 "지금 3이 맞나"가 아니라 <b>더 올리려면 파티션부터 늘려야 한다</b>는
	 * 제약을 못 박아 두는 검증이다. 파티션을 안 늘린 채 숫자만 키우면 여기서 걸린다.
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
