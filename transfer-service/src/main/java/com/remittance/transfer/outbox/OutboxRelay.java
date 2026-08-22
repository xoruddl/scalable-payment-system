package com.remittance.transfer.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 테이블을 폴링해 Kafka로 발행하고 발행 시각을 기록한다.
 *
 * <p>발행에 실패하면 {@code publishedAt}을 채우지 않으므로 다음 폴링에서 다시 시도된다.
 * 반대로 "발행은 성공했지만 마킹 직전에 죽는" 경우가 있을 수 있어 <b>같은 이벤트가 두 번 발행될 수 있다</b>
 * (at-least-once). 소비하는 쪽이 멱등하게 처리해야 한다 — Step 4에서 컨슈머를 만들 때 다룬다.
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	/** 한 번에 처리할 최대 건수. 폴링 한 번이 너무 길어지지 않게 제한한다. */
	private static final int BATCH_SIZE = 100;

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;

	@Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
	@Transactional
	public void publishPending() {
		List<OutboxEvent> pending =
				outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(Limit.of(BATCH_SIZE));
		if (pending.isEmpty()) {
			return;
		}

		for (OutboxEvent event : pending) {
			try {
				// 애그리거트 ID를 키로 써야 같은 송금의 이벤트가 한 파티션에 모여 순서가 보장된다
				kafkaTemplate.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload())
						.join();
				event.markPublished();
			} catch (Exception e) {
				// 마킹하지 않고 남겨두면 다음 폴링에서 재시도된다.
				// 순서를 지키기 위해 이번 배치는 여기서 중단한다.
				log.warn("Outbox 이벤트 발행 실패 - 다음 폴링에서 재시도 (id={}, type={})",
						event.getId(), event.getEventType(), e);
				break;
			}
		}
	}
}
