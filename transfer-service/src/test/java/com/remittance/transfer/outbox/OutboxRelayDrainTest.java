package com.remittance.transfer.outbox;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 Step 2 — <b>적체가 있으면 한 주기에 이어서 비운다.</b>
 *
 * <p>예전에는 주기마다 배치를 하나만 비웠다. 그러면 500ms에 100건, 즉 <b>초당 200건이
 * 구조적 상한</b>이 된다. 2026-08-23 측정에서 실측 발행 속도가 173 이벤트/s로 그 상한에
 * 붙어 있었고 Outbox에 29,746건이 쌓여 있었다.
 *
 * <p>이 검증은 <b>배치 크기보다 많이 쌓아 두고 한 번만 부른다.</b> 예전 코드라면 100건에서
 * 멈춘다. 처리량 자체는 테스트로 잴 수 없지만, <b>"한 번에 한 배치"라는 제약이 사라졌는지</b>는
 * 여기서 확실히 갈린다.
 *
 * <p>스케줄러가 끼어들면 무엇이 비운 건지 알 수 없으므로 주기를 아주 길게 줘 재우고,
 * 릴레이를 <b>직접 부른다.</b>
 */
@SpringBootTest(properties = {
		"outbox.relay.enabled=true",
		// 기동 직후 한 번은 도는데, 그때는 비울 게 없다. 그 뒤로는 테스트가 끝날 때까지 안 돈다.
		"outbox.relay.interval-ms=600000"
})
class OutboxRelayDrainTest extends AbstractIntegrationTest {

	/** 배치 크기(100)보다 확실히 많고, 한 주기 상한(20배치)보다는 적은 수. */
	private static final int PENDING_COUNT = 250;

	@Autowired
	private OutboxRelay relay;

	@Autowired
	private TransferOutboxRecorder outboxRecorder;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Test
	void 배치보다_많이_쌓여_있으면_한_주기에_다_비운다() {
		for (int i = 0; i < PENDING_COUNT; i++) {
			outboxRecorder.record(newTransfer(), TransferEventType.REQUESTED);
		}
		assertThat(unpublished()).as("준비 상태").isGreaterThanOrEqualTo(PENDING_COUNT);

		relay.publishPending();

		assertThat(unpublished())
				.as("한 배치(100건)만 비우고 다음 주기를 기다리면 여기서 걸린다")
				.isZero();
	}

	private long unpublished() {
		return outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(
				org.springframework.data.domain.Limit.of(PENDING_COUNT * 2)).size();
	}

	private Transfer newTransfer() {
		return Transfer.builder()
				.fromAccountId(UUID.randomUUID())
				.toAccountId(UUID.randomUUID())
				.amount(BigDecimal.valueOf(1_000))
				.currency("KRW")
				.build();
	}
}
