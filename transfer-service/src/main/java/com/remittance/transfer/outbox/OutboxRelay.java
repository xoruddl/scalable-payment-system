package com.remittance.transfer.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox 테이블을 한 바퀴 훑어 Kafka로 발행하고 발행 시각을 기록한다. 언제 돌지는
 * {@link OutboxRelayLoop}가 정한다 — 커밋이 깨우거나, 깨우는 게 없으면 주기가 된다 (D-007).
 *
 * 발행에 실패하면 {@code publishedAt}을 채우지 않으므로 다음 바퀴에서 다시 시도된다.
 * 반대로 "발행은 성공했지만 마킹 직전에 죽는" 경우가 있을 수 있어 같은 이벤트가 두 번 발행될 수 있다
 * (at-least-once). 소비하는 쪽이 멱등하게 처리해야 한다.
 *
 * 실제 발행은 {@link OutboxBatchPublisher}가 한다 — 배치 하나가 트랜잭션 하나여야 하는데,
 * 같은 빈 안에서 자기 메서드를 부르면 {@code @Transactional} 프록시를 타지 않기 때문이다.
 *
 * 적체가 있으면 다음 주기를 기다리지 않는다 (Phase 6 Step 2)
 * 예전에는 주기마다 배치를 딱 하나만 비웠다. 그러면 500ms에 100건, 즉 초당 200건이
 * 구조적 상한이 된다. 2026-08-23 측정에서 실측 발행 속도가 173 이벤트/s로 그 상한에
 * 붙어 있었고, account Outbox에 29,746건이 쌓여 있었다.
 *
 * 지금은 배치가 가득 찼으면 이어서 비운다. 가득 찼다는 건 아직 남아 있다는 뜻이고,
 * 남아 있는데 500ms를 노는 건 낭비다. 덜 찼으면 그 자리에서 끝낸다.
 *
 * 배치 크기를 키우지 않은 이유: 100을 500으로 올려도 상한은 5배가 된다. 하지만
 * 트랜잭션 하나가 그만큼 길어지고 UPDATE도 그만큼 커진다. 작은 트랜잭션을 여러 번 도는
 * 편이 락을 짧게 쥐고 메모리도 덜 쓴다. 상한을 없애는 데는 어느 쪽이든 되는데,
 * 대가가 다르다.
 *
 * 왜 한 바퀴가 끝없이 돌지 않는가: 루프는 바퀴 사이에서 "멈춰라"를 확인한다. 한 바퀴가 끝나지 않으면
 * 종료도 그만큼 늦는다. 남은 적체는 다음 바퀴가 마저 비운다.
 * (2026-09-15 전에는 {@code @Scheduled}로 돌았고, 스케줄러 스레드를 무한히 붙들지 않으려는 상한이었다.)
 *
 * 루프를 끄면({@code outbox.relay.enabled=false}) 아무도 이 빈을 부르지 않는다. 빈은 남겨 둔다 —
 * 한 바퀴를 직접 불러 확인하는 테스트가 쓴다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

	/** 한 번에 처리할 최대 건수. 트랜잭션 하나가 너무 커지지 않게 제한한다. */
	private static final int BATCH_SIZE = 100;

	/**
	 * 한 바퀴에 이어서 비울 최대 배치 수. 적체가 이보다 많으면 다음 바퀴에 마저 비운다.
	 * 루프가 멈추라는 말을 못 듣는 일을 막는 상한일 뿐, 처리량을 정하는 값이 아니다.
	 */
	private static final int MAX_BATCHES_PER_TICK = 20;

	private final OutboxBatchPublisher batchPublisher;

	/** 한 바퀴. 배치가 가득 차면 이어서 비우고, 덜 차면 끝낸다. */
	public void publishPending() {
		for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
			// 덜 찼다 = 더 비울 게 없거나 중간에 실패했다. 어느 쪽이든 이번 주기는 여기서 끝.
			if (batchPublisher.publishBatch(BATCH_SIZE) < BATCH_SIZE) {
				return;
			}
		}
	}
}
