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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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

	/**
	 * 실패 계열 이벤트는 <b>여러 상태에서 받아줄 수 있어야 한다.</b>
	 *
	 * <p>정상 흐름은 "직전 단계일 때만"이라는 한 점만 허용해도 됐다. 실패 흐름은 다르다.
	 * {@code transfer.debited}와 {@code transfer.credit-failed}는 <b>서로 다른 토픽</b>이라
	 * 파티션 키가 같아도 순서가 보장되지 않는다. credit-failed가 debited보다 먼저 도착하면
	 * 송금은 아직 PENDING인데 보상 이벤트가 오는 셈인데, 이때 한 점만 허용하면
	 * 그 이벤트를 조용히 버리고 <b>송금이 영원히 PENDING에 갇힌다</b>.
	 *
	 * <p>대신 종결 상태(COMPLETED/FAILED)는 어디에도 넣지 않았다. 한 번 닫힌 송금은
	 * 뒤늦은 이벤트로 다시 열리지 않는다 — 그게 재전송에 대한 멱등성이 된다.
	 */
	private static final Set<TransferStatus> COMPENSATION_STARTABLE =
			EnumSet.of(TransferStatus.PENDING, TransferStatus.DEBIT_COMPLETED);

	/** 환불 완료 이벤트는 보상 시작을 못 봤더라도 받아준다 (위와 같은 이유). */
	private static final Set<TransferStatus> COMPENSATION_CLOSABLE = EnumSet.of(
			TransferStatus.PENDING, TransferStatus.DEBIT_COMPLETED, TransferStatus.COMPENSATING);

	private final TransferRepository transferRepository;
	private final IdempotencyService idempotencyService;
	private final TransferOutboxRecorder outboxRecorder;

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

	@Transactional
	public void applyDebited(TransferEvents.Debited event) {
		advance(event.transferId(), TransferStatus.PENDING, Transfer::markDebitCompleted);
	}

	@Transactional
	public void applyCredited(TransferEvents.Credited event) {
		advance(event.transferId(), TransferStatus.DEBIT_COMPLETED, Transfer::markCreditCompleted);
	}

	/**
	 * 원장 기록까지 끝나야 COMPLETED다.
	 *
	 * <p>입금 시점에 완료로 찍으면, 원장 기록이 실패했을 때 "송금은 성공인데 원장에는 없는" 상태가 된다.
	 * 그 불일치를 나중에 찾아내는 것보다, 완료 판정을 원장까지 미루는 편이 낫다.
	 * (Phase 1의 정합성 재현 테스트가 잡아낸 바로 그 문제다.)
	 */
	@Transactional
	public void applyLedgerRecorded(TransferEvents.LedgerRecorded event) {
		Transfer transfer = findOrThrow(event.transferId());
		if (transfer.getStatus() != TransferStatus.CREDIT_COMPLETED) {
			logSkip(event.transferId(), transfer.getStatus(), TransferStatus.CREDIT_COMPLETED);
			return;
		}
		transfer.markCompleted();
		// 완료 사실도 이벤트로 남긴다. 알림 같은 후속 처리가 이걸 구독한다 (Phase 3).
		outboxRecorder.record(transfer, TransferEventType.COMPLETED);
	}

	/**
	 * 출금 자체가 실패 — 아직 움직인 돈이 없으므로 되돌릴 것 없이 바로 종결한다.
	 */
	@Transactional
	public void applyDebitFailed(TransferEvents.StepFailed event) {
		terminate(event, EnumSet.of(TransferStatus.PENDING));
	}

	/**
	 * 입금이 실패 — Account가 환불하는 중이다. 아직 종결이 아니라는 걸 상태로 드러낸다.
	 *
	 * <p>이 중간 상태가 없으면 "출금은 됐는데 왜 멈춰 있지?"로 보인다. COMPENSATING은
	 * <b>되돌리는 중</b>이라는 뜻이고, 되돌리기가 끝나면 {@link #applyDebitReversed}가 FAILED로 닫는다.
	 */
	@Transactional
	public void applyCreditFailed(TransferEvents.StepFailed event) {
		Transfer transfer = findOrThrow(event.transferId());
		if (!COMPENSATION_STARTABLE.contains(transfer.getStatus())) {
			logSkip(event.transferId(), transfer.getStatus(), COMPENSATION_STARTABLE);
			return;
		}
		transfer.markCompensating();
		transferRepository.save(transfer);
	}

	/** 출금이 되돌아왔다 — 이제 송금을 실패로 닫는다. */
	@Transactional
	public void applyDebitReversed(TransferEvents.StepFailed event) {
		terminate(event, COMPENSATION_CLOSABLE);
	}

	/**
	 * 실패로 종결하고 그 사실을 이벤트로 남긴다. 알림 같은 후속 처리가 이걸 구독한다(Phase 3).
	 */
	private void terminate(TransferEvents.StepFailed event, Set<TransferStatus> allowed) {
		Transfer transfer = findOrThrow(event.transferId());
		if (!allowed.contains(transfer.getStatus())) {
			logSkip(event.transferId(), transfer.getStatus(), allowed);
			return;
		}
		transfer.markFailed(event.failureReason());
		outboxRecorder.record(transfer, TransferEventType.FAILED);
	}

	/**
	 * 기대한 이전 단계일 때만 상태를 올린다.
	 *
	 * <p>이벤트는 at-least-once라 같은 이벤트가 다시 올 수 있다. 상태 전이에 조건을 걸어두면
	 * 재전송이 와도 이미 지나간 단계라 무시되므로, 별도의 중복 처리 테이블이 필요 없다.
	 * (잔액 변경처럼 되돌릴 수 없는 작업은 이렇게 못 하므로 Account Service는 처리 흔적을 따로 남긴다.)
	 */
	private void advance(UUID transferId, TransferStatus expected, Consumer<Transfer> transition) {
		Transfer transfer = findOrThrow(transferId);
		if (transfer.getStatus() != expected) {
			logSkip(transferId, transfer.getStatus(), expected);
			return;
		}
		transition.accept(transfer);
		transferRepository.save(transfer);
	}

	private void logSkip(UUID transferId, TransferStatus current, Object expected) {
		log.info("이미 지나간 단계라 건너뛴다 (transferId={}, 현재={}, 기대={})", transferId, current, expected);
	}

	private Transfer findOrThrow(UUID transferId) {
		return transferRepository.findByTransferId(transferId)
				.orElseThrow(() -> new TransferNotFoundException(transferId));
	}

	@Transactional(readOnly = true)
	public Transfer getTransfer(UUID transferId) {
		return findOrThrow(transferId);
	}
}
