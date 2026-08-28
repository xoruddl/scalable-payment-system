package com.remittance.transfer.service;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.messaging.TransferEvents;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.ArgumentMatchers.anyString;
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
	private TransferAcceptExecutor acceptExecutor;

	@Mock
	private TransferStateUpdater stateUpdater;

	/**
	 * 메트릭은 목이 아니라 진짜 레지스트리를 쓴다. 목으로 두면 "increment()가 불렸다"까지만
	 * 확인하게 되는데, 정작 알고 싶은 건 <b>어떤 태그로 몇이 찍혔나</b>이다.
	 */
	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private TransferService transferService() {
		return new TransferService(transferRepository, idempotencyService, acceptExecutor, stateUpdater, meterRegistry);
	}

	private double conflictCount(String outcome) {
		return meterRegistry.find("remittance.optimistic.lock.conflict")
				.tag("entity", "transfer").tag("outcome", outcome)
				.counters().stream().mapToDouble(counter -> counter.count()).sum();
	}

	private final UUID fromAccountId = UUID.randomUUID();
	private final UUID toAccountId = UUID.randomUUID();
	// Transfer가 금액을 scale 2로 정규화하므로 스텁도 같은 표현이어야 매칭된다
	// (BigDecimal.equals()는 scale까지 비교한다)
	private final BigDecimal amount = new BigDecimal("1000.00");

	/**
	 * 접수 실행은 {@link TransferAcceptExecutor}로 옮겼다(한 트랜잭션으로 묶기 위해).
	 * 여기서는 <b>거기까지 도달하는가</b>만 보고, 무엇이 저장되는지는
	 * {@code TransferAcceptExecutorTest}가 진짜 DB로 확인한다.
	 */
	private void stubAcceptReturnsNewTransfer() {
		given(acceptExecutor.accept(anyString(), any(CreateTransferRequest.class)))
				.willAnswer(invocation -> newTransfer());
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
		stubAcceptReturnsNewTransfer();
		String key = "key-" + UUID.randomUUID();
		CreateTransferRequest request =
				CreateTransferRequest.internal(fromAccountId, toAccountId, amount, "KRW", null);

		Transfer result = transferService().requestTransfer(key, request);

		assertThat(result.getStatus())
				.as("응답 시점에는 아직 아무 돈도 움직이지 않았다")
				.isEqualTo(TransferStatus.PENDING);
		// 키를 선점한 뒤 접수를 한 트랜잭션으로 실행한다.
		// hash()가 목이라 두 번째 인자는 null이다 — 여기서 볼 것은 "그 키로 선점했나"다.
		verify(idempotencyService).reserve(eq(key), any());
		verify(acceptExecutor).accept(key, request);
	}

	@Test
	void 출금_입금_계좌가_같으면_멱등성_키를_소모하지_않고_즉시_예외() {
		CreateTransferRequest request =
				CreateTransferRequest.internal(fromAccountId, fromAccountId, amount, "KRW", null);

		assertThatThrownBy(() -> transferService().requestTransfer("key-1", request))
				.isInstanceOf(InvalidTransferRequestException.class);

		verify(acceptExecutor, never()).accept(any(), any());
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
		// Step 4d에서 이 경합이 실제로 터져 "바깥에는 실패라고 알려놓고 자기 기록은 진행 중"인
		// 상태를 만들었다. 로그로만 남기면 그때처럼 사고가 난 뒤에야 찾아보게 된다.
		assertThat(conflictCount("retried")).isEqualTo(1);
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
		// 포기한 것과 넘긴 것은 성격이 다르다 — 이건 메시지가 DLT로 가는 길이다.
		assertThat(conflictCount("exhausted")).isEqualTo(1);
	}
}
