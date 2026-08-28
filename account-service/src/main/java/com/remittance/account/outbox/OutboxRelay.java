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
 *
 * <h2>적체가 있으면 다음 주기를 기다리지 않는다 (Phase 6 Step 2)</h2>
 * 예전에는 주기마다 배치를 <b>딱 하나</b>만 비웠다. 그러면 500ms에 100건, 즉 <b>초당 200건이
 * 구조적 상한</b>이 된다. 2026-08-23 측정에서 실측 발행 속도가 <b>173 이벤트/s</b>로 그 상한에
 * 붙어 있었고, account Outbox에 <b>29,746건</b>이 쌓여 있었다.
 *
 * <p>지금은 <b>배치가 가득 찼으면 이어서 비운다.</b> 가득 찼다는 건 아직 남아 있다는 뜻이고,
 * 남아 있는데 500ms를 노는 건 낭비다. 덜 찼으면 그 자리에서 끝낸다.
 *
 * <p><b>배치 크기를 키우지 않은 이유</b>: 100을 500으로 올려도 상한은 5배가 된다. 하지만
 * 트랜잭션 하나가 그만큼 길어지고 UPDATE도 그만큼 커진다. 작은 트랜잭션을 <b>여러 번</b> 도는
 * 편이 락을 짧게 쥐고 메모리도 덜 쓴다. 상한을 없애는 데는 어느 쪽이든 되는데,
 * 대가가 다르다.
 *
 * <p><b>왜 무한히 돌지 않는가</b>: {@code @Scheduled}는 스케줄러 스레드를 빌려 쓴다.
 * 끝나지 않으면 같은 스케줄러의 다른 일이 굶는다. 지금 이 서비스에 다른 예약 작업이 없지만,
 * <b>없다는 사실에 기대는 코드는 나중에 조용히 깨진다.</b>
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelay {

	/** 한 번에 처리할 최대 건수. 트랜잭션 하나가 너무 커지지 않게 제한한다. */
	private static final int BATCH_SIZE = 100;

	/**
	 * 한 주기에 <b>이어서</b> 비울 최대 배치 수. 적체가 이보다 많으면 다음 주기에 마저 비운다.
	 * 스케줄러 스레드를 무한히 붙들지 않기 위한 상한일 뿐, 처리량을 정하는 값이 아니다.
	 */
	private static final int MAX_BATCHES_PER_TICK = 20;

	private final OutboxBatchPublisher batchPublisher;

	@Scheduled(fixedDelayString = "${outbox.relay.interval-ms:200}")
	public void publishPending() {
		for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
			// 덜 찼다 = 더 비울 게 없거나 중간에 실패했다. 어느 쪽이든 이번 주기는 여기서 끝.
			if (batchPublisher.publishBatch(BATCH_SIZE) < BATCH_SIZE) {
				return;
			}
		}
	}
}
