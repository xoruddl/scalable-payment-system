package com.remittance.transfer.messaging;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 Step 4d — e2e에서 드러난 결함에 대한 회귀 테스트.
 *
 * <p>세 서비스를 실제로 띄운 e2e에서 드러난 문제다. Saga 단계마다 <b>토픽이 다르므로</b>
 * 파티션 키가 같아도 도착 순서가 보장되지 않는데, 상태 전이가 "기대한 직전 단계일 때만"이라
 * 앞선 단계보다 먼저 온 이벤트를 <b>버린다</b>. 그리고 그 이벤트는 다시 오지 않는다.
 *
 * <p>Step 4b에서 이걸 "중간 상태 하나를 건너뛸 수 있다" 정도로 적어뒀는데 과소평가였다.
 * 건너뛰는 게 아니라 <b>영구 정지</b>다 — e2e에서 정상 송금이 DEBIT_COMPLETED에 멈춘 채 끝나지 않았다.
 */
@SpringBootTest
class SagaOrderingTest extends AbstractIntegrationTest {

	@Autowired
	private TransferService transferService;

	@Autowired
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

	private TransferStatus statusOf(UUID transferId) {
		return transferRepository.findByTransferId(transferId).orElseThrow().getStatus();
	}

	/**
	 * e2e에서 실제로 벌어진 순서다. 컨슈머 여섯 개가 밀린 이벤트를 한꺼번에 처리하면서
	 * 뒤 단계가 먼저 도착했다.
	 */
	@Test
	void 뒤_단계가_먼저_도착해도_결국_COMPLETED가_된다() {
		UUID transferId = acceptedTransfer().getTransferId();

		transferService.applyCredited(new TransferEvents.Credited(
				transferId, BigDecimal.valueOf(4_000), BigDecimal.valueOf(6_000), Instant.now()));
		transferService.applyLedgerRecorded(new TransferEvents.LedgerRecorded(transferId, Instant.now()));
		transferService.applyDebited(new TransferEvents.Debited(
				transferId, BigDecimal.valueOf(4_000), Instant.now()));

		assertThat(statusOf(transferId))
				.as("먼저 온 이벤트를 버리면 그 단계는 다시 오지 않는다 — 송금이 영원히 끝나지 않는다")
				.isEqualTo(TransferStatus.COMPLETED);
	}

}
