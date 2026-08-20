package com.remittance.transfer.service;

import com.remittance.transfer.domain.IdempotencyKey;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.IdempotencyConflictException;
import com.remittance.transfer.exception.IdempotencyInProgressException;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.exception.TransferNotFoundException;
import com.remittance.transfer.messaging.TransferEvents;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 송금의 접수와 상태 추적을 담당한다.
 *
 * <p><b>Phase 2 Step 4a에서 흐름이 근본적으로 바뀌었다.</b> 예전에는 이 클래스가 요청 스레드 안에서
 * 출금 → 입금 → 원장기록을 차례로 <b>호출</b>했다(오케스트레이션). 이제는 {@code transfer.requested}
 * 이벤트 하나만 남기고 즉시 202로 응답한다. 그 뒤의 단계는 각 서비스가 이벤트를 보고 스스로 진행한다
 * (Choreography Saga).
 *
 * <pre>
 *   Transfer  transfer.requested       ─▶ Account  출금 ─▶ transfer.debited
 *   Account   transfer.debited         ─▶ Account  입금 ─▶ transfer.credited
 *   Ledger    transfer.credited        ─▶ 원장 기록     ─▶ transfer.ledger-recorded
 *   Transfer  transfer.debited/credited/ledger-recorded ─▶ 상태 갱신
 * </pre>
 *
 * <p>얻은 것: 요청 스레드가 다른 서비스의 응답 시간에 묶이지 않고, 중간에 한 서비스가 죽어도
 * 이벤트가 브로커에 남아 재개된다.
 * <br>잃은 것: 응답을 받은 시점에 송금이 <b>끝난 게 아니다</b>. 클라이언트는 조회로 확인해야 한다.
 *
 * <p><b>Step 4b에서 실패 흐름이 붙었다.</b> 이 서비스는 실패를 <b>판정</b>하지 않는다 —
 * 계좌에 무슨 일이 있었는지는 Account가 알려주고, 그걸 받아 송금의 최종 상태를 찍을 뿐이다.
 * <pre>
 *   PENDING          ── transfer.debit-failed   ─▶ FAILED        (움직인 돈 없음)
 *   DEBIT_COMPLETED  ── transfer.credit-failed  ─▶ COMPENSATING  (환불 진행 중)
 *   COMPENSATING     ── transfer.debit-reversed ─▶ FAILED        (환불 완료)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class TransferService {

	private static final Logger log = LoggerFactory.getLogger(TransferService.class);

	/** Account 쪽 재시도 횟수와 맞춘다. 이보다 오래 경합하면 재시도로 풀 문제가 아니다. */
	private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

	private final TransferRepository transferRepository;
	private final IdempotencyService idempotencyService;
	private final TransferOutboxRecorder outboxRecorder;
	private final TransferStateUpdater stateUpdater;

	/**
	 * 송금 요청의 공개 진입점. 송금을 <b>접수</b>하고 바로 돌아온다.
	 * 같은 Idempotency-Key로 다시 들어온 요청은 새 송금을 만들지 않고 최초 송금을 그대로 돌려준다.
	 */
	public Transfer requestTransfer(String idempotencyKey, CreateTransferRequest request) {
		// 요청 자체가 잘못된 경우는 키를 소모하지 않는다 (처리를 시작한 적이 없으므로).
		validate(request);

		String requestHash = idempotencyService.hash(request);
		try {
			idempotencyService.reserve(idempotencyKey, requestHash);
		} catch (DataIntegrityViolationException alreadyReserved) {
			// 같은 키가 이미 존재한다 = 재요청이거나 동시에 들어온 중복 요청
			return replay(idempotencyKey, requestHash);
		}

		// 송금 저장과 transfer.requested 기록이 한 트랜잭션이다.
		// 둘 중 하나만 성공하는 경우가 없으므로 "접수됐는데 아무도 모르는 송금"이 생기지 않는다.
		Transfer transfer = createTransfer(request);

		// 여기서 COMPLETED는 "송금이 끝났다"가 아니라 "접수가 끝났다"는 뜻이다.
		// 재요청은 이 시점 이후로 항상 같은 transferId를 돌려받는다.
		idempotencyService.complete(idempotencyKey, transfer.getTransferId());
		return transfer;
	}

	private void validate(CreateTransferRequest request) {
		if (request.fromAccountId().equals(request.toAccountId())) {
			throw new InvalidTransferRequestException("출금 계좌와 입금 계좌가 동일할 수 없습니다.");
		}
	}

	/**
	 * 이미 사용된 키로 들어온 요청을 처리한다.
	 * payload가 다르면 충돌, 아직 접수 중이면 409, 접수가 끝났으면 그 송금을 돌려준다.
	 *
	 * <p>접수 도중 서버가 죽으면 키가 IN_PROGRESS로 남아 재요청이 계속 409를 받는다.
	 * 일부러 그렇게 둔다 — 접수가 실제로 커밋됐는지 우리도 모르는 상태에서 키를 놓아주면,
	 * 재요청이 <b>두 번째 송금</b>을 만들 수 있기 때문이다. 남은 키 정리는 Step 5의 배치에서 다룬다.
	 */
	private Transfer replay(String idempotencyKey, String requestHash) {
		IdempotencyKey existing = idempotencyService.find(idempotencyKey)
				.orElseThrow(() -> new IdempotencyInProgressException(idempotencyKey));

		if (!existing.matches(requestHash)) {
			throw new IdempotencyConflictException(idempotencyKey);
		}
		if (!existing.isTerminal() || existing.getTransferId() == null) {
			throw new IdempotencyInProgressException(idempotencyKey);
		}

		log.info("멱등 재요청 - 저장된 결과를 반환한다 (key={}, transferId={})",
				idempotencyKey, existing.getTransferId());
		return transferRepository.findByTransferId(existing.getTransferId())
				.orElseThrow(() -> new TransferNotFoundException(existing.getTransferId()));
	}

	private Transfer createTransfer(CreateTransferRequest request) {
		return outboxRecorder.record(
				Transfer.builder()
						.fromAccountId(request.fromAccountId())
						.toAccountId(request.toAccountId())
						.amount(request.amount())
						.currency(request.currency())
						.memo(request.memo())
						.build(),
				TransferEventType.REQUESTED);
	}

	public void applyDebited(TransferEvents.Debited event) {
		withOptimisticRetry(event.transferId(),
				() -> stateUpdater.advanceTo(event.transferId(), TransferStatus.DEBIT_COMPLETED));
	}

	public void applyCredited(TransferEvents.Credited event) {
		withOptimisticRetry(event.transferId(),
				() -> stateUpdater.advanceTo(event.transferId(), TransferStatus.CREDIT_COMPLETED));
	}

	/**
	 * 원장 기록까지 끝나야 COMPLETED다.
	 *
	 * <p>입금 시점에 완료로 찍으면, 원장 기록이 실패했을 때 "송금은 성공인데 원장에는 없는" 상태가 된다.
	 * 그 불일치를 나중에 찾아내는 것보다, 완료 판정을 원장까지 미루는 편이 낫다.
	 * (Phase 1의 정합성 재현 테스트가 잡아낸 바로 그 문제다.)
	 */
	public void applyLedgerRecorded(TransferEvents.LedgerRecorded event) {
		withOptimisticRetry(event.transferId(),
				() -> stateUpdater.advanceTo(event.transferId(), TransferStatus.COMPLETED));
	}

	/** 출금 자체가 실패 — 아직 움직인 돈이 없으므로 되돌릴 것 없이 바로 종결한다. */
	public void applyDebitFailed(TransferEvents.StepFailed event) {
		withOptimisticRetry(event.transferId(),
				() -> stateUpdater.markFailed(event.transferId(), event.failureReason()));
	}

	/**
	 * 입금이 실패 — Account가 환불하는 중이다. 아직 종결이 아니라는 걸 상태로 드러낸다.
	 *
	 * <p>이 중간 상태가 없으면 "출금은 됐는데 왜 멈춰 있지?"로 보인다. COMPENSATING은
	 * <b>되돌리는 중</b>이라는 뜻이고, 되돌리기가 끝나면 {@link #applyDebitReversed}가 FAILED로 닫는다.
	 */
	public void applyCreditFailed(TransferEvents.StepFailed event) {
		withOptimisticRetry(event.transferId(), () -> stateUpdater.markCompensating(event.transferId()));
	}

	/** 출금이 되돌아왔다 — 이제 송금을 실패로 닫는다. */
	public void applyDebitReversed(TransferEvents.StepFailed event) {
		withOptimisticRetry(event.transferId(),
				() -> stateUpdater.markFailed(event.transferId(), event.failureReason()));
	}

	/**
	 * 낙관적 락 충돌은 <b>다른 리스너가 같은 송금을 먼저 바꿨다</b>는 뜻이다.
	 * 다시 읽어 전이 조건을 처음부터 판단하면 그 변화를 반영한 결정이 나온다 —
	 * 이미 종결됐으면 건너뛰고, 아직이면 이어서 진행한다.
	 *
	 * <p>끝내 못 잡으면 예외를 그대로 내보낸다. 컨슈머 에러 핸들러가 재시도하고,
	 * 그래도 안 되면 DLT로 간다 (Step 4c).
	 */
	private void withOptimisticRetry(UUID transferId, Runnable transition) {
		for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
			try {
				transition.run();
				return;
			} catch (ObjectOptimisticLockingFailureException conflict) {
				if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
					log.warn("상태 전이 경합이 계속된다 - 재시도를 포기한다 (transferId={})", transferId);
					throw conflict;
				}
				log.info("상태 전이 경합 - 다시 읽고 판단한다 (transferId={}, 시도={})", transferId, attempt);
			}
		}
	}

	@Transactional(readOnly = true)
	public Transfer getTransfer(UUID transferId) {
		return transferRepository.findByTransferId(transferId)
				.orElseThrow(() -> new TransferNotFoundException(transferId));
	}
}
