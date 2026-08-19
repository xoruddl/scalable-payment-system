package com.remittance.ledger.config;

import com.remittance.ledger.messaging.TransferEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 이 서비스가 <b>발행하는</b> 토픽을 기동 시점에 만든다.
 * 브로커의 자동 생성에 맡기면 파티션이 1개로 고정되고, 나중에 늘리면 키 분배가 달라져 순서가 깨진다.
 */
@Configuration
public class KafkaTopicsConfig {

	private static final int PARTITIONS = 3;
	/** 로컬은 단일 브로커라 1. 운영이라면 최소 3이어야 한다. */
	private static final int REPLICAS = 1;

	@Bean
	NewTopic transferLedgerRecordedTopic() {
		return TopicBuilder.name(TransferEvents.LEDGER_RECORDED)
				.partitions(PARTITIONS).replicas(REPLICAS).build();
	}
}
