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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Transfer Service가 하는 일은 <b>접수</b>와 <b>상태 추적</b> 두 가지다.
 * 출금·입금·원장기록은 이벤트를 받은 다른 서비스가 알아서 한다.
 *
 * <p>상태 전이 규칙 자체는 {@link TransferStateUpdaterTest}에 있다. 여기서는 그 전이를
 * <b>경합이 있어도 반영되게 만드는</b> 재시도만 본다.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

	@Mock
	private TransferRepository transferRepository;

	@Mock
	private IdempotencyService idempotencyService;

	@Mock
	private TransferOutboxRecorder outboxRecorder;

	@Mock
	private TransferStateUpdater stateUpdater;

	private TransferService transferService() {
		return new TransferService(transferRepository, idempotencyService, outboxRecorder, stateUpdater);
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

	/**
	 * 낙관적 락 충돌은 다른 리스너가 같은 송금을 먼저 바꿨다는 뜻이다. 여기서 포기하면
	 * 그 이벤트가 반영되지 않은 채 끝난다 — 다시 읽고 판단해야 한다.
	 */
	@Test
	void 상태_전이가_경합하면_다시_읽고_판단한다() {
		UUID transferId = UUID.randomUUID();
		willThrow(new ObjectOptimisticLockingFailureException(Transfer.class, transferId))
				.willDoNothing()
				.given(stateUpdater).advanceTo(transferId, TransferStatus.DEBIT_COMPLETED);

		transferService().applyDebited(new TransferEvents.Debited(
				transferId, BigDecimal.valueOf(4_000), Instant.now()));

		verify(stateUpdater, times(2)).advanceTo(transferId, TransferStatus.DEBIT_COMPLETED);
	}

	/** 끝내 못 잡으면 삼키지 않는다. 컨슈머가 재시도하고, 그래도 안 되면 DLT로 가야 한다. */
	@Test
	void 경합이_계속되면_예외를_삼키지_않는다() {
		UUID transferId = UUID.randomUUID();
		willThrow(new ObjectOptimisticLockingFailureException(Transfer.class, transferId))
				.given(stateUpdater).advanceTo(transferId, TransferStatus.DEBIT_COMPLETED);

		assertThatThrownBy(() -> transferService().applyDebited(new TransferEvents.Debited(
				transferId, BigDecimal.valueOf(4_000), Instant.now())))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
	}
}
