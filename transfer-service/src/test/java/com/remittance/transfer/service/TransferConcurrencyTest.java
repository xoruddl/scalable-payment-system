package com.remittance.transfer.service;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.messaging.TransferEvents;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * Phase 2 Step 4d — e2e에서 드러난 결함에 대한 회귀 테스트.
 *
 * <p>세 서비스를 실제로 띄운 e2e에서 드러난 문제다. Saga 단계마다 토픽이 다르고
 * <b>토픽마다 리스너 스레드가 다르다.</b> 여러 리스너가 같은 송금 행을 동시에
 * read-modify-write 할 수 있는데, {@code Transfer}에는 {@code @Version}이 없어
 * <b>마지막에 커밋한 쪽이 이긴다.</b>
 *
 * <p>e2e에서 실제로 이렇게 됐다 — {@code markFailed()}가 실행되어 {@code transfer.failed}까지
 * 발행된 송금의 행이, 뒤늦게 커밋된 출금 이벤트에 덮여 {@code DEBIT_COMPLETED}로 남았다.
 * <b>바깥에는 실패라고 알려놓고 자기 기록은 진행 중</b>인 상태다.
 *
 * <p>경합을 운에 맡기면 테스트가 흔들리므로 순서를 강제한다. 출금 이벤트를 처리하는 스레드를
 * <b>읽은 뒤 · 커밋하기 전</b>에 붙잡아두고, 그 사이 다른 이벤트가 읽고 쓰고 커밋하게 한다.
 *
 * <p>붙잡는 지점으로 {@code save}를 고른 이유가 있다. 조회를 가로채려면 Mockito의
 * {@code callRealMethod()}가 필요한데 Spring Data 리포지토리는 인터페이스라 쓸 수 없다
 * ("Cannot call abstract real method"). 반면 {@code save}는 <b>실제로 부르지 않아도 된다</b> —
 * 이미 영속 상태인 엔티티라 커밋 시점의 변경 감지로 어차피 UPDATE가 나간다.
 */
@SpringBootTest
class TransferConcurrencyTest extends AbstractIntegrationTest {

	@Autowired
	private TransferService transferService;

	@MockitoSpyBean
	private TransferRepository transferRepository;

	@Autowired
	private TransferOutboxRecorder outboxRecorder;

	private Transfer acceptedTransfer() {
		return outboxRecorder.record(
				Transfer.builder()
						.fromAccountId(UUID.randomUUID())
						.toAccountId(UUID.randomUUID())
						.amount(new BigDecimal("1000.00"))
						.currency("KRW")
						.build(),
				TransferEventType.REQUESTED);
	}

	@Test
	void 동시에_처리된_이벤트가_종결_상태를_덮어쓰지_않는다() throws Exception {
		UUID transferId = acceptedTransfer().getTransferId();

		AtomicBoolean debitedIsHolding = new AtomicBoolean(false);
		CountDownLatch reversalCommitted = new CountDownLatch(1);
		willAnswer(invocation -> {
			if (debitedIsHolding.compareAndSet(false, true)) {
				reversalCommitted.await(30, TimeUnit.SECONDS);
			}
			return invocation.getArgument(0);
		}).given(transferRepository).save(any());

		Thread debited = new Thread(() -> transferService.applyDebited(
				new TransferEvents.Debited(transferId, BigDecimal.valueOf(4_000), Instant.now())));
		debited.start();

		// 출금 이벤트가 PENDING을 읽고 붙잡힌 뒤에 환불 완료를 처리한다
		await().atMost(Duration.ofSeconds(10)).untilTrue(debitedIsHolding);
		transferService.applyDebitReversed(new TransferEvents.StepFailed(
				transferId, "통화가 일치하지 않습니다", Instant.now()));

		reversalCommitted.countDown();
		debited.join(30_000);

		assertThat(transferRepository.findByTransferId(transferId).orElseThrow().getStatus())
				.as("종결된 송금이 뒤늦게 커밋된 이벤트에 덮이면, 발행한 transfer.failed가 거짓말이 된다")
				.isEqualTo(TransferStatus.FAILED);
	}
}
