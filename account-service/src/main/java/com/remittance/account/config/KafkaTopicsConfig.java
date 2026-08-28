package com.remittance.account.config;

import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.messaging.TransferEvents;
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
 * 메시지의 키 분배가 달라져 순서 보장이 깨진다. 그래서 처음부터 명시한다.
 *
 * <p><b>소비만 하는 토픽까지 선언하는 이유는 Step 4d에서 데어봤기 때문이다.</b>
 * 컨슈머가 토픽이 생기기 전에 구독하면 브로커가 1파티션으로 자동 생성해버리고,
 * 뒤늦게 3으로 늘려도 이미 붙은 컨슈머는 모른다(기본 {@code metadata.max.age.ms}가 5분).
 *
 * <p>같은 토픽을 두 서비스가 선언하게 되지만 문제되지 않는다. {@link KafkaAdmin}은
 * 이미 있는 토픽을 다시 만들지 않고, 선언된 파티션 수가 더 크면 늘리기만 한다.
 * 다만 <b>양쪽 선언이 어긋나면 큰 쪽이 이긴다</b>는 뜻이므로, 파티션 수를 바꿀 때는
 * 그 토픽을 쓰는 모든 서비스를 함께 확인해야 한다 (이벤트 계약과 같은 규칙).
 *
 * <p>파티션 키는 송금 ID다. 같은 송금의 이벤트는 항상 같은 파티션에 들어가므로,
 * 파티션이 여러 개여도 <b>한 토픽 안에서는 순서가 지켜진다</b>.
 * (토픽이 다르면 순서 보장이 없다 — 그건 상태 전이 규칙 쪽에서 감당한다.)
 */
@Configuration
public class KafkaTopicsConfig {

	/**
	 * <b>컨슈머 스레드 수의 상한</b>이다 — 스레드를 아무리 늘려도 파티션 수를 넘을 수 없다.
	 *
	 * <p>3 → 6으로 올린 이유 (Phase 6, 2026-08-24 실측). 60 TPS에서 account 컨슈머만
	 * lag가 계속 자랐고(75 → 633), 상한이 그대로 계산됐다:
	 * <b>입금 리스너 한 건 52.5ms × 스레드 3개 = 초당 57건.</b> 필요한 건 60건/s였다.
	 *
	 * <p>그때 JVM CPU는 3~4%, HikariCP 대기는 0이었다. <b>자원이 남는데 스레드가 3개라
	 * 못 쓰고 있었다</b>는 뜻이고, 그 3은 파티션 수가 정한 값이었다.
	 *
	 * <p>⚠️ <b>늘리면 키 → 파티션 배정이 바뀐다.</b> 같은 송금의 이벤트가 예전 파티션과
	 * 새 파티션으로 갈릴 수 있으므로 <b>토픽이 빈 상태에서</b> 올려야 한다.
	 * 줄이는 것은 아예 안 된다 — Kafka가 막는다.
	 */
	private static final int PARTITIONS = 6;
	/** 로컬은 단일 브로커라 1. 운영이라면 최소 3이어야 한다. */
	private static final int REPLICAS = 1;

	private static final List<String> PUBLISHED = List.of(
			AccountEvents.BALANCE_CHANGED,
			TransferEvents.DEBITED,
			TransferEvents.CREDITED,
			TransferEvents.DEBIT_FAILED,
			TransferEvents.CREDIT_FAILED,
			TransferEvents.CREDIT_UNKNOWN,
			TransferEvents.DEBIT_REVERSED);

	/** {@code credit-failed}는 이 서비스가 발행하고 다시 소비한다 — 보상을 재시도 가능하게 만들기 위해서다. */
	private static final List<String> CONSUMED = List.of(
			TransferEvents.REQUESTED,
			TransferEvents.DEBITED,
			TransferEvents.CREDIT_FAILED);

	/** 처리하지 못한 메시지가 가는 곳({@link KafkaErrorHandlingConfig}). 마지막 안전망이라 자동 생성에 맡기지 않는다. */
	private static List<String> deadLetterTopicsOf(List<String> consumed) {
		return consumed.stream().map(topic -> topic + ".DLT").toList();
	}

	@Bean
	KafkaAdmin.NewTopics transferTopics() {
		NewTopic[] topics = Stream.of(PUBLISHED, CONSUMED, deadLetterTopicsOf(CONSUMED))
				.flatMap(List::stream)
				.distinct()
				.map(KafkaTopicsConfig::topic)
				.toArray(NewTopic[]::new);
		return new KafkaAdmin.NewTopics(topics);
	}

	private static NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
	}
}
