package com.remittance.account.config;

import com.remittance.account.messaging.TransferEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 이 서비스가 <b>발행하는</b> 토픽을 기동 시점에 만든다.
 *
 * <p>브로커의 자동 생성에 맡기면 파티션 수를 통제할 수 없고(기본 1개),
 * 나중에 늘리려 해도 이미 쌓인 메시지의 키 분배가 달라져 순서 보장이 깨진다.
 * 처음부터 명시해두는 편이 안전하다.
 *
 * <p>파티션 키는 송금 ID다. 같은 송금의 이벤트는 항상 같은 파티션에 들어가므로,
 * 파티션이 여러 개여도 <b>한 송금 안에서는 순서가 지켜진다</b>.
 */
@Configuration
public class KafkaTopicsConfig {

	/** 컨슈머 인스턴스를 3개까지 늘려 병렬 처리할 수 있다는 뜻. */
	private static final int PARTITIONS = 3;
	/** 로컬은 단일 브로커라 1. 운영이라면 최소 3이어야 한다. */
	private static final int REPLICAS = 1;

	@Bean
	NewTopic transferDebitedTopic() {
		return topic(TransferEvents.DEBITED);
	}

	@Bean
	NewTopic transferCreditedTopic() {
		return topic(TransferEvents.CREDITED);
	}

	@Bean
	NewTopic transferDebitFailedTopic() {
		return topic(TransferEvents.DEBIT_FAILED);
	}

	@Bean
	NewTopic transferCreditFailedTopic() {
		return topic(TransferEvents.CREDIT_FAILED);
	}

	@Bean
	NewTopic transferDebitReversedTopic() {
		return topic(TransferEvents.DEBIT_REVERSED);
	}

	private NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
	}
}
