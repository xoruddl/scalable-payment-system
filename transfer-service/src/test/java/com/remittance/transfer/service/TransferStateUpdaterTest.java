package com.remittance.transfer.service;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 송금 상태 전이 규칙.
 *
 * <p>규칙의 핵심은 <b>"앞으로만 간다"</b>이다. Saga 이벤트는 단계마다 다른 토픽으로 오므로
 * 도착 순서가 보장되지 않는다. 그래서
 * <ul>
 *   <li>뒤 단계가 먼저 와도 <b>건너뛰어서라도 적용</b>한다 — 버리면 다시 오지 않아 송금이 멈춘다.</li>
 *   <li>지나간 단계가 다시 와도 <b>되돌아가지 않는다</b> — 재전송에 대한 멱등성이다.</li>
 *   <li>되돌리는 중이거나 이미 닫혔으면 <b>정상 흐름을 진행시키지 않는다</b>.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TransferStateUpdaterTest {

	@Mock
	private TransferRepository transferRepository;

	@Mock
	private TransferOutboxRecorder outboxRecorder;

	private TransferStateUpdater updater() {
		return new TransferStateUpdater(transferRepository, outboxRecorder);
	}

	private Transfer transferAt(TransferStatus... path) {
		Transfer transfer = Transfer.builder()
				.fromAccountId(UUID.randomUUID()).toAccountId(UUID.randomUUID())
				.amount(new BigDecimal("1000.00")).currency("KRW").build();
		for (TransferStatus status : path) {
			if (status == TransferStatus.COMPENSATING) {
				transfer.markCompensating();
			} else if (status == TransferStatus.FAILED) {
				transfer.markFailed("잔액이 부족합니다");
			} else {
				transfer.advanceTo(status);
			}
		}
		given(transferRepository.findByTransferId(transfer.getTransferId()))
				.willReturn(Optional.of(transfer));
		return transfer;
	}

	@Test
	void 출금_이벤트를_받으면_DEBIT_COMPLETED로_올라간다() {
		Transfer transfer = transferAt();

		updater().advanceTo(transfer.getTransferId(), TransferStatus.DEBIT_COMPLETED);

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DEBIT_COMPLETED);
	}

	/**
	 * 입금까지만 끝난 시점에 완료로 찍으면, 원장 기록이 실패했을 때
	 * "송금은 성공인데 원장에는 없는" 불일치가 남는다. Phase 1이 정확히 그랬다.
	 */
	@Test
	void 입금까지만_끝난_송금은_COMPLETED가_아니다() {
		Transfer transfer = transferAt(TransferStatus.DEBIT_COMPLETED);

		updater().advanceTo(transfer.getTransferId(), TransferStatus.CREDIT_COMPLETED);

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CREDIT_COMPLETED);
		verify(outboxRecorder, never()).record(any(), any());
	}

	@Test
	void 원장_기록까지_끝나야_COMPLETED가_되고_완료_이벤트가_나간다() {
		Transfer transfer = transferAt(TransferStatus.DEBIT_COMPLETED, TransferStatus.CREDIT_COMPLETED);

		updater().advanceTo(transfer.getTransferId(), TransferStatus.COMPLETED);

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transfer.getCompletedAt()).isNotNull();
		verify(outboxRecorder).record(transfer, TransferEventType.COMPLETED);
	}

	/**
	 * 토픽이 다르면 도착 순서가 보장되지 않는다. 여기서 버리면 그 단계는 <b>다시 오지 않아</b>
	 * 송금이 영원히 멈춘다 — Step 4d의 e2e에서 실제로 겪은 일이다.
	 */
	@Test
	void 뒤_단계가_먼저_도착하면_건너뛰어서라도_적용한다() {
		Transfer transfer = transferAt();

		updater().advanceTo(transfer.getTransferId(), TransferStatus.CREDIT_COMPLETED);

		assertThat(transfer.getStatus())
				.as("입금 이벤트가 먼저 왔다는 건 출금이 이미 끝났다는 뜻이다")
				.isEqualTo(TransferStatus.CREDIT_COMPLETED);
	}

	@Test
	void 지나간_단계가_다시_와도_되돌아가지_않는다() {
		Transfer transfer = transferAt(
				TransferStatus.DEBIT_COMPLETED, TransferStatus.CREDIT_COMPLETED, TransferStatus.COMPLETED);
		Instant completedAt = transfer.getCompletedAt();

		updater().advanceTo(transfer.getTransferId(), TransferStatus.DEBIT_COMPLETED);
		updater().advanceTo(transfer.getTransferId(), TransferStatus.COMPLETED);

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transfer.getCompletedAt())
				.as("완료 시각이 재전송 때마다 갱신되면 멱등하지 않다는 뜻이다")
				.isEqualTo(completedAt);
		verify(outboxRecorder, never()).record(any(), any());
	}

	@Test
	void 되돌리는_중에는_정상_흐름을_진행시키지_않는다() {
		Transfer transfer = transferAt(TransferStatus.DEBIT_COMPLETED, TransferStatus.COMPENSATING);

		updater().advanceTo(transfer.getTransferId(), TransferStatus.CREDIT_COMPLETED);

		assertThat(transfer.getStatus())
				.as("환불 중인 송금이 완료로 나아가면 안 된다")
				.isEqualTo(TransferStatus.COMPENSATING);
	}

	@Test
	void 출금이_실패하면_되돌릴_것_없이_바로_FAILED가_된다() {
		Transfer transfer = transferAt();

		updater().markFailed(transfer.getTransferId(), "잔액이 부족합니다");

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
		assertThat(transfer.getFailureReason())
				.as("왜 실패했는지가 남지 않으면 조회하는 쪽이 이유를 알 수 없다")
				.isEqualTo("잔액이 부족합니다");
		verify(outboxRecorder).record(transfer, TransferEventType.FAILED);
	}

	/**
	 * 입금 실패는 종결이 아니다. 출금은 이미 나갔으므로 되돌아올 때까지 기다려야 한다.
	 * 여기서 곧바로 FAILED로 닫으면 <b>돈은 사라졌는데 실패로 끝난 송금</b>이 된다.
	 */
	@Test
	void 입금이_실패하면_아직_종결하지_않고_COMPENSATING으로_간다() {
		Transfer transfer = transferAt(TransferStatus.DEBIT_COMPLETED);

		updater().markCompensating(transfer.getTransferId());

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATING);
		verify(outboxRecorder, never()).record(any(), any());
	}

	@Test
	void 환불이_끝나야_FAILED로_종결된다() {
		Transfer transfer = transferAt(TransferStatus.DEBIT_COMPLETED, TransferStatus.COMPENSATING);

		updater().markFailed(transfer.getTransferId(), "통화가 일치하지 않습니다");

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
		verify(outboxRecorder).record(transfer, TransferEventType.FAILED);
	}

	/**
	 * 성공한 송금이 나중에 실패로 바뀌는 것만큼 나쁜 건 없다.
	 */
	@Test
	void 완료된_송금은_뒤늦은_실패_이벤트로_뒤집히지_않는다() {
		Transfer transfer = transferAt(
				TransferStatus.DEBIT_COMPLETED, TransferStatus.CREDIT_COMPLETED, TransferStatus.COMPLETED);

		updater().markCompensating(transfer.getTransferId());
		updater().markFailed(transfer.getTransferId(), "잔액이 부족합니다");

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		verify(outboxRecorder, never()).record(any(), any());
	}
}
