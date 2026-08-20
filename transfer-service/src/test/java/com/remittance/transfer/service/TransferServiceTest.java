package com.remittance.transfer.service;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.messaging.TransferEvents;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Step 4a 이후의 Transfer Service는 두 가지 일만 한다 — <b>접수</b>와 <b>상태 추적</b>.
 * 출금·입금·원장기록은 이벤트를 받은 다른 서비스가 알아서 한다.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

	@Mock
	private TransferRepository transferRepository;

	@Mock
	private IdempotencyService idempotencyService;

	@Mock
	private TransferOutboxRecorder outboxRecorder;

	private TransferService transferService() {
		return new TransferService(transferRepository, idempotencyService, outboxRecorder);
	}

	private final UUID fromAccountId = UUID.randomUUID();
	private final UUID toAccountId = UUID.randomUUID();
	// Transfer가 금액을 scale 2로 정규화하므로 스텁도 같은 표현이어야 매칭된다
	// (BigDecimal.equals()는 scale까지 비교한다)
	private final BigDecimal amount = new BigDecimal("1000.00");

	private void stubSaveReturnsArgument() {
		given(outboxRecorder.record(any(Transfer.class), any()))
				.willAnswer(invocation -> invocation.getArgument(0));
	}

	private Transfer newTransfer() {
		return Transfer.builder()
				.fromAccountId(fromAccountId).toAccountId(toAccountId)
				.amount(amount).currency("KRW").build();
	}

	private void stubFind(Transfer transfer) {
		given(transferRepository.findByTransferId(transfer.getTransferId())).willReturn(Optional.of(transfer));
	}

	@Test
	void 접수하면_PENDING으로_남기고_transfer_requested만_발행한다() {
		stubSaveReturnsArgument();
		String key = "key-" + UUID.randomUUID();

		Transfer result = transferService().requestTransfer(key,
				new CreateTransferRequest(fromAccountId, toAccountId, amount, "KRW", null));

		assertThat(result.getStatus())
				.as("응답 시점에는 아직 아무 돈도 움직이지 않았다")
				.isEqualTo(TransferStatus.PENDING);
		verify(outboxRecorder).record(any(Transfer.class), eq(TransferEventType.REQUESTED));
		verify(idempotencyService).complete(key, result.getTransferId());
	}

	@Test
	void 출금_입금_계좌가_같으면_멱등성_키를_소모하지_않고_즉시_예외() {
		CreateTransferRequest request =
				new CreateTransferRequest(fromAccountId, fromAccountId, amount, "KRW", null);

		assertThatThrownBy(() -> transferService().requestTransfer("key-1", request))
				.isInstanceOf(InvalidTransferRequestException.class);

		verify(outboxRecorder, never()).record(any(), any());
		verify(idempotencyService, never()).reserve(any(), any());
	}

	@Test
	void 출금_이벤트를_받으면_DEBIT_COMPLETED로_올라간다() {
		Transfer transfer = newTransfer();
		stubFind(transfer);

		transferService().applyDebited(new TransferEvents.Debited(
				transfer.getTransferId(), BigDecimal.valueOf(4_000), Instant.now()));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DEBIT_COMPLETED);
	}

	@Test
	void 원장_기록까지_끝나야_COMPLETED가_된다() {
		Transfer transfer = newTransfer();
		transfer.markDebitCompleted();
		transfer.markCreditCompleted();
		stubFind(transfer);
		stubSaveReturnsArgument();

		transferService().applyLedgerRecorded(
				new TransferEvents.LedgerRecorded(transfer.getTransferId(), Instant.now()));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		verify(outboxRecorder).record(transfer, TransferEventType.COMPLETED);
	}

	/**
	 * 입금까지만 끝난 시점에 완료로 찍으면, 원장 기록이 실패했을 때
	 * "송금은 성공인데 원장에는 없는" 불일치가 남는다. Phase 1이 정확히 그랬다.
	 */
	@Test
	void 입금까지만_끝난_송금은_COMPLETED가_아니다() {
		Transfer transfer = newTransfer();
		transfer.markDebitCompleted();
		stubFind(transfer);

		transferService().applyCredited(new TransferEvents.Credited(
				transfer.getTransferId(), BigDecimal.valueOf(4_000), BigDecimal.valueOf(6_000), Instant.now()));

		assertThat(transfer.getStatus())
				.as("원장 기록 이벤트가 오기 전까지는 완료가 아니다")
				.isEqualTo(TransferStatus.CREDIT_COMPLETED);
	}

	/**
	 * 이벤트는 at-least-once라 같은 이벤트가 다시 올 수 있다.
	 * 이미 지나간 단계면 아무 일도 일어나지 않아야 한다.
	 */
	@Test
	void 같은_이벤트를_다시_받아도_상태가_되돌아가지_않는다() {
		Transfer transfer = newTransfer();
		transfer.markDebitCompleted();
		transfer.markCreditCompleted();
		transfer.markCompleted();
		Instant completedAt = transfer.getCompletedAt();
		stubFind(transfer);

		transferService().applyDebited(new TransferEvents.Debited(
				transfer.getTransferId(), BigDecimal.valueOf(4_000), Instant.now()));
		transferService().applyLedgerRecorded(
				new TransferEvents.LedgerRecorded(transfer.getTransferId(), Instant.now()));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transfer.getCompletedAt()).isEqualTo(completedAt);
		verify(outboxRecorder, never()).record(any(), any());
	}

	// ───────────────────────── Step 4b — 실패·보상 흐름 ─────────────────────────

	private TransferEvents.StepFailed failure(Transfer transfer, String reason) {
		return new TransferEvents.StepFailed(transfer.getTransferId(), reason, Instant.now());
	}

	@Test
	void 출금이_실패하면_되돌릴_것_없이_바로_FAILED가_된다() {
		Transfer transfer = newTransfer();
		stubFind(transfer);
		stubSaveReturnsArgument();

		transferService().applyDebitFailed(failure(transfer, "잔액이 부족합니다"));

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
		Transfer transfer = newTransfer();
		transfer.markDebitCompleted();
		stubFind(transfer);

		transferService().applyCreditFailed(failure(transfer, "통화가 일치하지 않습니다"));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATING);
		verify(outboxRecorder, never()).record(any(), any());
	}

	@Test
	void 환불이_끝나야_FAILED로_종결된다() {
		Transfer transfer = newTransfer();
		transfer.markDebitCompleted();
		transfer.markCompensating();
		stubFind(transfer);
		stubSaveReturnsArgument();

		transferService().applyDebitReversed(failure(transfer, "통화가 일치하지 않습니다"));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
		verify(outboxRecorder).record(transfer, TransferEventType.FAILED);
	}

	/**
	 * {@code transfer.debited}와 {@code transfer.credit-failed}는 서로 다른 토픽이라
	 * 파티션 키가 같아도 도착 순서가 보장되지 않는다. 보상 이벤트가 먼저 와도 받아줘야 한다 —
	 * 안 그러면 그 이벤트를 버리고 송금이 영원히 PENDING에 갇힌다.
	 */
	@Test
	void 출금_이벤트보다_보상_이벤트가_먼저_와도_갇히지_않는다() {
		Transfer transfer = newTransfer();
		stubFind(transfer);
		stubSaveReturnsArgument();

		transferService().applyCreditFailed(failure(transfer, "통화가 일치하지 않습니다"));
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATING);

		transferService().applyDebitReversed(failure(transfer, "통화가 일치하지 않습니다"));
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
	}

	@Test
	void 실패로_닫힌_송금은_보상_이벤트가_다시_와도_열리지_않는다() {
		Transfer transfer = newTransfer();
		transfer.markFailed("잔액이 부족합니다");
		Instant completedAt = transfer.getCompletedAt();
		stubFind(transfer);

		transferService().applyCreditFailed(failure(transfer, "통화가 일치하지 않습니다"));
		transferService().applyDebitReversed(failure(transfer, "통화가 일치하지 않습니다"));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
		assertThat(transfer.getCompletedAt()).isEqualTo(completedAt);
		verify(outboxRecorder, never()).record(any(), any());
	}

	/**
	 * 완료된 송금에 뒤늦은 실패 이벤트가 도착해도 실패로 뒤집히면 안 된다.
	 * 성공한 송금이 나중에 실패로 바뀌는 것만큼 나쁜 건 없다.
	 */
	@Test
	void 완료된_송금은_뒤늦은_실패_이벤트로_뒤집히지_않는다() {
		Transfer transfer = newTransfer();
		transfer.markDebitCompleted();
		transfer.markCreditCompleted();
		transfer.markCompleted();
		stubFind(transfer);

		transferService().applyDebitFailed(failure(transfer, "잔액이 부족합니다"));
		transferService().applyCreditFailed(failure(transfer, "잔액이 부족합니다"));
		transferService().applyDebitReversed(failure(transfer, "잔액이 부족합니다"));

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		verify(outboxRecorder, never()).record(any(), any());
	}
}
