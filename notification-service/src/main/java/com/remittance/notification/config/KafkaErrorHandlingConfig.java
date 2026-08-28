package com.remittance.notification.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
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
 * <p>이 서비스에서 메시지를 잃으면 <b>알림이 안 나가고 아무도 모른다.</b> 송금 자체는 멀쩡해서
 * 대사에도 안 잡힌다 — 돈이 어긋난 게 아니기 때문이다. 유실 대신 DLT에 남겨야 무엇이 빠졌는지 안다.
 *
 * <p>네 서비스가 같은 정책을 각자 정의한다. 공유 모듈을 두지 않기로 했으므로,
 * <b>바꿀 때는 네 곳을 함께 확인</b>해야 한다 (이벤트 계약과 같은 규칙).
 */
@Configuration
public class KafkaErrorHandlingConfig {

	private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

	/** 첫 재시도까지 기다리는 시간. */
	private static final long INITIAL_INTERVAL_MS = 1_000;
	private static final double MULTIPLIER = 2.0;
	/** 아무리 늘어나도 이보다 오래 기다리지는 않는다. */
	private static final long MAX_INTERVAL_MS = 10_000;
	/** 재시도 횟수(최초 시도 제외). 1초 + 2초 + 4초를 쓰고 포기한다. */
	private static final int MAX_RETRIES = 3;

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
		DefaultErrorHandler errorHandler =
				new DefaultErrorHandler(deadLetterRecoverer(kafkaTemplate, meterRegistry), backOff());
		// 본문을 못 읽는 메시지는 몇 번을 다시 읽어도 못 읽는다.
		// 발송 실패는 일시적일 수 있으므로 그대로 재시도한다.
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
	 * DLT로 보내면서 <b>흔적을 남긴다</b> (Phase 5 Step 2).
	 *
	 * <p>2026-08-22 e2e에서 확인한 것: 본문이 깨진 메시지를 넣었더니 설계대로 DLT에 쌓이고
	 * 파티션도 막히지 않았는데, <b>WARN·ERROR 로그가 한 줄도 남지 않았다.</b>
	 * {@code DeadLetterPublishingRecoverer}는 성공적으로 보낸 사실을 알리지 않는다.
	 * 메시지 하나가 죽어서 사람 손을 기다리는 중인데 아무도 모르는 상태가 된다.
	 *
	 * <p>그래서 두 가지를 붙였다.
	 * <ul>
	 *   <li><b>WARN 로그</b> — 어느 토픽·파티션·오프셋의 무엇이 왜 죽었는지. 발행보다 <b>먼저</b>
	 *       남긴다. DLT 발행 자체가 실패해도 최소한 무엇을 잃었는지는 알아야 한다.</li>
	 *   <li><b>{@code remittance.kafka.dlt.published} 카운터</b> — 토픽별. 로그는 사람이 볼 때만
	 *       보이지만 이 값은 그래프에서 튄다. 토픽 수가 고정이라 라벨이 늘어날 걱정도 없다.</li>
	 * </ul>
	 *
	 * <p>파티션을 {@code -1}로 넘기는 게 핵심이다. 기본 동작은 <b>원래 메시지와 같은 번호의 파티션</b>으로
	 * 보내는데, 우리 토픽은 파티션이 3개인 반면 DLT는 자동 생성되며 1개짜리로 만들어진다.
	 * 그러면 2번 파티션으로 가야 할 메시지가 갈 곳이 없어 DLT 발행 자체가 실패한다 —
	 * 마지막 안전망이 조용히 무너지는 셈이다.
	 */
	private static ConsumerRecordRecoverer deadLetterRecoverer(
			KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
		DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafkaTemplate,
				(record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

		return (ConsumerRecord<?, ?> record, Exception exception) -> {
			log.warn("메시지를 DLT로 보낸다 - 사람이 봐야 한다 (topic={}, partition={}, offset={}, key={}, reason={})",
					record.topic(), record.partition(), record.offset(), record.key(),
					exception.getMessage(), exception);
			Counter.builder("remittance.kafka.dlt.published")
					.description("DLT로 보낸 메시지 수")
					.tag("topic", record.topic())
					.register(meterRegistry)
					.increment();
			publisher.accept(record, exception);
		};
	}
}
