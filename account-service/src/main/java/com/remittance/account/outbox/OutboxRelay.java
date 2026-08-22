package com.remittance.account.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 테이블을 폴링해 Kafka로 발행하고 발행 시각을 기록한다.
 *
 * <p>발행에 실패하면 {@code publishedAt}을 채우지 않으므로 다음 폴링에서 다시 시도된다.
 * 반대로 "발행은 성공했지만 마킹 직전에 죽는" 경우가 있을 수 있어 <b>같은 이벤트가 두 번 발행될 수 있다</b>
 * (at-least-once). 소비하는 쪽이 멱등하게 처리해야 한다.
 *
 * <p>실제 발행은 {@link OutboxBatchPublisher}가 한다 — 배치 하나가 트랜잭션 하나여야 하는데,
 * 같은 빈 안에서 자기 메서드를 부르면 {@code @Transactional} 프록시를 타지 않기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelay {

	/** 한 번에 처리할 최대 건수. 폴링 한 번이 너무 길어지지 않게 제한한다. */
	private static final int BATCH_SIZE = 100;

	private final OutboxBatchPublisher batchPublisher;

	@Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
	public void publishPending() {
		batchPublisher.publishBatch(BATCH_SIZE);
	}
}
