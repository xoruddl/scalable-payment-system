package com.remittance.ledger.config;

import com.remittance.ledger.messaging.TransferEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.stream.Stream;

/**
 * 이 서비스가 쓰는 토픽을 기동 시점에 만든다 — <b>발행하는 것뿐 아니라 소비하는 것도.</b>
 *
 * <p>파티션 수를 브로커 자동 생성에 맡기면 기본 1개로 만들어지고, 나중에 늘려도 이미 쌓인
 * 메시지의 키 분배가 달라져 순서 보장이 깨진다.
 *
 * <p><b>소비만 하는 토픽까지 선언하는 이유는 Step 4d에서 데어봤기 때문이다.</b>
 * 컨슈머가 토픽이 생기기 전에 구독하면 브로커가 1파티션으로 자동 생성해버리고,
 * 뒤늦게 3으로 늘려도 이미 붙은 컨슈머는 모른다(기본 {@code metadata.max.age.ms}가 5분).
 *
 * <p>같은 토픽을 두 서비스가 선언하게 되지만 문제되지 않는다. {@link KafkaAdmin}은
 * 이미 있는 토픽을 다시 만들지 않고, 선언된 파티션 수가 더 크면 늘리기만 한다.
 * 다만 <b>양쪽 선언이 어긋나면 큰 쪽이 이긴다</b>는 뜻이므로, 파티션 수를 바꿀 때는
 * 그 토픽을 쓰는 모든 서비스를 함께 확인해야 한다 (이벤트 계약과 같은 규칙).
 */
@Configuration
public class KafkaTopicsConfig {

	private static final int PARTITIONS = 3;
	/** 로컬은 단일 브로커라 1. 운영이라면 최소 3이어야 한다. */
	private static final int REPLICAS = 1;

	private static final List<String> PUBLISHED = List.of(TransferEvents.LEDGER_RECORDED);

	private static final List<String> CONSUMED = List.of(TransferEvents.CREDITED);

	/** 처리하지 못한 메시지가 가는 곳({@link KafkaErrorHandlingConfig}). 마지막 안전망이라 자동 생성에 맡기지 않는다. */
	private static List<String> deadLetterTopicsOf(List<String> consumed) {
		return consumed.stream().map(topic -> topic + ".DLT").toList();
	}

	@Bean
	KafkaAdmin.NewTopics transferTopics() {
		NewTopic[] topics = Stream.of(PUBLISHED, CONSUMED, deadLetterTopicsOf(CONSUMED))
				.flatMap(List::stream)
				.map(KafkaTopicsConfig::topic)
				.toArray(NewTopic[]::new);
		return new KafkaAdmin.NewTopics(topics);
	}

	private static NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
	}
}
