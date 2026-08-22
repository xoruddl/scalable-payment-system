package com.remittance.ledger.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * 컨슈머가 실패했을 때 무엇을 할지 정한다 — <b>몇 번 다시 해보고, 그래도 안 되면 어디에 둘지.</b>
 *
 * <p>이걸 두지 않으면 spring-kafka 기본값이 적용되는데, 그 기본값은
 * <b>지연 없이 10번 재시도하고 로그만 남긴 뒤 오프셋을 커밋</b>한다. 즉 메시지가 조용히 사라진다.
 *
 * <p>이 서비스에서 메시지를 잃으면 <b>원장에 빠진 거래가 생긴다.</b> 게다가 원장 기록 이벤트가
 * 나가지 않아 송금이 CREDIT_COMPLETED에서 멈춘다. 원장은 나중에 대사(Step 5)로 맞출 대상이므로,
 * 유실 대신 DLT에 남겨 무엇이 빠졌는지 알 수 있게 한다.
 *
 * <p>세 서비스가 같은 정책을 각자 정의한다. 공유 모듈을 두지 않기로 했으므로,
 * <b>바꿀 때는 세 곳을 함께 확인</b>해야 한다 (이벤트 계약과 같은 규칙).
 */
@Configuration
public class KafkaErrorHandlingConfig {

	/** 첫 재시도까지 기다리는 시간. */
	private static final long INITIAL_INTERVAL_MS = 1_000;
	private static final double MULTIPLIER = 2.0;
	/** 아무리 늘어나도 이보다 오래 기다리지는 않는다. */
	private static final long MAX_INTERVAL_MS = 10_000;
	/** 재시도 횟수(최초 시도 제외). 1초 + 2초 + 4초를 쓰고 포기한다. */
	private static final int MAX_RETRIES = 3;

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterRecoverer(kafkaTemplate), backOff());
		// 본문을 못 읽는 메시지는 몇 번을 다시 읽어도 못 읽는다.
		// 원장 기록 자체의 실패(Mongo 장애 등)는 일시적일 수 있으므로 그대로 재시도한다.
		errorHandler.addNotRetryableExceptions(JacksonException.class);
		return errorHandler;
	}

	private static ExponentialBackOff backOff() {
		ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
		backOff.setMaxInterval(MAX_INTERVAL_MS);
		backOff.setMaxAttempts(MAX_RETRIES);
		return backOff;
	}

	/**
	 * 파티션을 {@code -1}로 넘기는 게 핵심이다. 기본 동작은 <b>원래 메시지와 같은 번호의 파티션</b>으로
	 * 보내는데, 우리 토픽은 파티션이 3개인 반면 DLT는 자동 생성되며 1개짜리로 만들어진다.
	 * 그러면 2번 파티션으로 가야 할 메시지가 갈 곳이 없어 DLT 발행 자체가 실패한다 —
	 * 마지막 안전망이 조용히 무너지는 셈이다.
	 */
	private static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate) {
		return new DeadLetterPublishingRecoverer(kafkaTemplate,
				(record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
	}
}
