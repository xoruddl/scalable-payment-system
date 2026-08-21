package com.remittance.notification.config;

import com.remittance.notification.messaging.TransferEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.stream.Stream;

/**
 * 이 서비스가 쓰는 토픽을 기동 시점에 만든다.
 *
 * <p>이 서비스는 <b>아무것도 발행하지 않는다</b> — 듣기만 한다. 그런데도 소비하는 토픽을 선언하는
 * 이유는 Step 4d에서 데어봤기 때문이다. 컨슈머가 토픽이 생기기 전에 구독하면 브로커가
 * 1파티션으로 자동 생성해버리고, 뒤늦게 3으로 늘려도 이미 붙은 컨슈머는 모른다
 * (기본 {@code metadata.max.age.ms}가 5분).
 *
 * <p>{@code transfer.completed}·{@code transfer.failed}는 Transfer Service도 선언한다.
 * {@link KafkaAdmin}은 이미 있는 토픽을 다시 만들지 않고 파티션이 모자랄 때만 늘리므로 안전하지만,
 * <b>선언이 어긋나면 큰 쪽이 이긴다</b> — 파티션 수를 바꿀 때는 양쪽을 함께 확인해야 한다.
 */
@Configuration
public class KafkaTopicsConfig {

	/** Transfer Service의 선언과 같아야 한다. */
	private static final int PARTITIONS = 3;
	/** 로컬은 단일 브로커라 1. 운영이라면 최소 3이어야 한다. */
	private static final int REPLICAS = 1;

	private static final List<String> CONSUMED = List.of(TransferEvents.COMPLETED, TransferEvents.FAILED);

	/** 처리하지 못한 메시지가 가는 곳({@link KafkaErrorHandlingConfig}). 자동 생성에 맡기지 않는다. */
	private static List<String> deadLetterTopicsOf(List<String> consumed) {
		return consumed.stream().map(topic -> topic + ".DLT").toList();
	}

	@Bean
	KafkaAdmin.NewTopics notificationTopics() {
		NewTopic[] topics = Stream.of(CONSUMED, deadLetterTopicsOf(CONSUMED))
				.flatMap(List::stream)
				.map(KafkaTopicsConfig::topic)
				.toArray(NewTopic[]::new);
		return new KafkaAdmin.NewTopics(topics);
	}

	private static NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
	}
}
