package com.remittance.transfer.config;

import com.remittance.transfer.messaging.TransferEvents;
import com.remittance.transfer.outbox.TransferEventType;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 이 서비스가 쓰는 토픽을 기동 시점에 만든다 — <b>발행하는 것뿐 아니라 소비하는 것도.</b>
 *
 * <p>파티션 수를 브로커 자동 생성에 맡기면 기본 1개로 만들어지고, 나중에 늘려도 이미 쌓인
 * 메시지의 키 분배가 달라져 순서 보장이 깨진다. 그래서 처음부터 명시한다.
 *
 * <p><b>소비만 하는 토픽까지 선언하는 이유는 Step 4d에서 데어봤기 때문이다.</b>
 * 전에는 "만드는 건 발행하는 서비스 몫"이라 보고 발행 토픽만 선언했는데,
 * 컨슈머가 토픽이 생기기 전에 구독하면 <b>브로커가 1파티션으로 자동 생성</b>해버린다.
 * 뒤늦게 발행하는 쪽이 3으로 늘려도, 이미 붙은 컨슈머는 늘어난 파티션을 모른다
 * (기본 {@code metadata.max.age.ms}가 5분). 세 서비스를 함께 띄운 e2e에서
 * 이 서비스가 {@code transfer.debited-0}만 할당받아 Saga 전체가 5분 멈췄다.
 *
 * <p>같은 토픽을 두 서비스가 선언하게 되지만 문제되지 않는다. {@link KafkaAdmin}은
 * 이미 있는 토픽을 다시 만들지 않고, 선언된 파티션 수가 더 크면 늘리기만 한다.
 * 다만 <b>양쪽 선언이 어긋나면 큰 쪽이 이긴다</b>는 뜻이므로, 파티션 수를 바꿀 때는
 * 그 토픽을 쓰는 모든 서비스를 함께 확인해야 한다 (이벤트 계약과 같은 규칙).
 */
@Configuration
public class KafkaTopicsConfig {

	/**
	 * <b>컨슈머 스레드 수의 상한</b>이다 — 스레드는 파티션 수를 넘을 수 없다.
	 * 3 → 6으로 올렸다. 근거와 주의사항은 account-service의 같은 파일에 적어두었다
	 * (네 서비스가 같은 값을 선언해야 한다 — 어긋나면 큰 쪽이 이긴다).
	 *
	 * <p>{@code private}이 아닌 이유: {@code KafkaTopicPartitionTest}가 이 값을 읽는다.
	 * 테스트가 기대값을 따로 적어두면 <b>같은 숫자를 두 곳에 두는 것</b>이라, 바꿀 때
	 * 한쪽만 고치고 red를 보게 된다(실제로 3 → 6에서 그랬다).
	 */
	static final int PARTITIONS = 6;
	/** 로컬은 단일 브로커라 1. 운영이라면 최소 3이어야 한다. */
	private static final int REPLICAS = 1;

	/** 이 서비스가 발행한다. */
	private static final List<String> PUBLISHED = Arrays.stream(TransferEventType.values())
			.map(TransferEventType::topic)
			.toList();

	/** 이 서비스가 소비한다. 만드는 주체는 다른 서비스지만, 잘못 만들어지면 손해는 여기서 본다. */
	private static final List<String> CONSUMED = List.of(
			TransferEvents.DEBITED,
			TransferEvents.CREDITED,
			TransferEvents.LEDGER_RECORDED,
			TransferEvents.DEBIT_FAILED,
			TransferEvents.CREDIT_FAILED,
			TransferEvents.DEBIT_REVERSED);

	/**
	 * 처리하지 못한 메시지가 가는 곳({@link KafkaErrorHandlingConfig}). 이것도 자동 생성에 맡기지 않는다 —
	 * 마지막 안전망이 필요한 순간에 없으면 곤란하다.
	 */
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
