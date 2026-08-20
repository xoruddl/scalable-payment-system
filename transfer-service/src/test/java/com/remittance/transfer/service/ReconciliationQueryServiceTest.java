package com.remittance.transfer.service;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.web.dto.UnsettledTransferView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사가 "흐름이 끊겼다"를 판단하는 근거를 만드는 쪽.
 * <b>정상 건이 섞여 들어오면 신호가 묻히고</b>, 진짜 멈춘 건을 빠뜨리면 아무 소용이 없다.
 */
@SpringBootTest
class ReconciliationQueryServiceTest extends AbstractIntegrationTest {

	@Autowired
	private ReconciliationQueryService reconciliationQueryService;

	@Autowired
	private TransferStateUpdater stateUpdater;

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

	/** 방금 접수된 송금은 아직 진행 중일 뿐이다. 이걸 잡으면 정상 트래픽이 전부 경고가 된다. */
	@Test
	void 방금_접수된_송금은_잡히지_않는다() {
		UUID transferId = acceptedTransfer().getTransferId();

		assertThat(reconciliationQueryService.unsettledTransfers(Duration.ofMinutes(2), 500))
				.extracting(UnsettledTransferView::transferId)
				.doesNotContain(transferId);
	}

	@Test
	void 종결되지_않은_채_오래된_송금은_잡힌다() {
		UUID transferId = acceptedTransfer().getTransferId();

		// 기준 시간을 0으로 두면 "접수 시각보다 이전"이 곧 지금이 되어, 오래된 것과 같은 취급이 된다
		assertThat(reconciliationQueryService.unsettledTransfers(Duration.ZERO, 500))
				.extracting(UnsettledTransferView::transferId)
				.contains(transferId);
	}

	/** 끝난 송금은 오래됐어도 문제가 아니다. */
	@Test
	void 종결된_송금은_아무리_오래돼도_잡히지_않는다() {
		Transfer transfer = acceptedTransfer();
		stateUpdater.markFailed(transfer.getTransferId(), "잔액이 부족합니다");

		assertThat(reconciliationQueryService.unsettledTransfers(Duration.ZERO, 500))
				.extracting(UnsettledTransferView::transferId)
				.doesNotContain(transfer.getTransferId());
	}

	@Test
	void 보상_중인_송금도_종결되지_않은_것으로_본다() {
		Transfer transfer = acceptedTransfer();
		stateUpdater.markCompensating(transfer.getTransferId());

		assertThat(reconciliationQueryService.unsettledTransfers(Duration.ZERO, 500))
				.extracting(UnsettledTransferView::transferId)
				.as("환불이 끝나지 않으면 돈이 뜬 채로 남는다 — 가장 봐야 하는 상태다")
				.contains(transfer.getTransferId());
	}
}
