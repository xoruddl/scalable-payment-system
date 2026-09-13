package com.remittance.account.saga;

import com.remittance.account.external.ExternalCreditGateway;
import com.remittance.account.external.ExternalCreditRequest;
import com.remittance.account.external.ExternalCreditResult;
import com.remittance.account.external.ExternalCreditUnknownException;
import com.remittance.account.external.PendingExternalCredits;
import com.remittance.account.settlement.SettlementAccounts;
import com.remittance.account.messaging.TransferEvents;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 송금 Saga에서 Account Service가 맡은 단계들.
 *
 *   transfer.requested     ──▶ 출금 ──▶ transfer.debited
 *                            └ 실패 ──▶ transfer.debit-failed
 *   transfer.debited       ──▶ 입금 ──▶ transfer.credited
 *                            └ 실패 ──▶ transfer.credit-failed
 *   transfer.credit-failed ──▶ 환불 ──▶ transfer.debit-reversed   (보상)
 *
 * 오케스트레이터가 지시하는 게 아니라 각 서비스가 이벤트를 보고 스스로 다음을 발행한다
 * (Choreography). 대신 흐름 전체를 한눈에 볼 수 있는 곳이 없어지므로,
 * 어떤 이벤트가 어떤 이벤트를 낳는지는 이 클래스 주석과 {@link TransferEvents}에 남긴다.
 *
 * 이 클래스는 흐름만 정한다 — 어느 이벤트에 어느 계좌를 어떻게 바꾸고 무엇을 내는가.
 * 그 단계를 락·트랜잭션·예외 분류로 감싸는 일은 {@link SagaStepRunner}가,
 * 상대 은행을 부르는 일은 {@link ExternalCreditGateway}가 맡는다.
 * 전진 단계와 보상 단계가 실패했을 때 어떻게 다른지도 {@link SagaStepRunner}에 있다.
 */
@Service
@RequiredArgsConstructor
public class TransferSagaService {

	private static final Logger log = LoggerFactory.getLogger(TransferSagaService.class);

	private final SagaStepRunner sagaStepRunner;
	private final ExternalCreditGateway externalCreditGateway;
	private final SettlementAccounts settlementAccounts;
	private final PendingExternalCredits pendingExternalCredits;

	/** 송금 접수 → 출금 계좌에서 뺀다. */
	public void onRequested(TransferEvents.Requested event) {
		SagaStep debit = new SagaStep(event.fromAccountId(),
				BalanceChange.transferDebit(event.amount(), event.currency()),
				balance -> NextEvent.of(event.debited(balance.total())));
		// 출금이 실패했으면 아직 움직인 돈이 없다. 되돌릴 것 없이 송금만 종결하면 된다.
		sagaStepRunner.run(ConsumedEvent.of(event), debit,
				reason -> NextEvent.of(event.debitFailed(reason)));
	}

	/** 출금 완료 → 입금 계좌에 넣는다. */
	public void onDebited(TransferEvents.Debited event) {
		if (event.isExternal()) {
			creditExternal(event);
			return;
		}
		creditInternal(event, event.toAccountId());
	}

	/**
	 * 상대 은행으로 나가는 입금 (Phase 6.5).
	 *
	 * HTTP 호출이 트랜잭션 밖에 있다 ★
	 * 느린 상대가 DB 트랜잭션을 붙들면 안 된다. 상대 은행이 3초를 끌면 커넥션도 3초
	 * 묶이고, 그 커넥션은 우리 내부 송금이 쓸 것이었다. 남의 사정으로 우리 일이 멈춘다.
	 *
	 * 그래도 응답을 기다리는 동안 스레드는 묶인다. 외부 전용 리스너로 내부 송금과 분리하고,
	 * 격벽으로 동시 호출 수를 제한하며, 회로 차단기로 계속 느린 은행을 잠시 부르지 않는다.
	 *
	 * 재시도가 안전한 이유
	 * 호출이 멱등성 흔적({@code processed_events})보다 앞에 있어서, 재배달되면
	 * 상대 은행을 다시 부른다. 그게 안전한 이유는 오직 상대가 송금 ID로 멱등하기 때문이다.
	 * 우리 DB의 제약이 아니라 남의 약속에 기대고 있다 — 그게 서비스 경계를 넘는 멱등성이다.
	 *
	 * 정산 계좌로 적는다
	 * 상대가 받았다고 하면 그 은행의 정산 계좌에 입금한다. 상대 계좌를 우리 원장에
	 * 적을 수는 없지만, "그 은행에 지급할 채무"는 우리 장부의 것이다.
	 * 그래서 원장은 두 다리를 그대로 보고, 원장·대사 로직을 하나도 안 고쳐도 된다.
	 */
	private void creditExternal(TransferEvents.Debited event) {
		ExternalCreditResult result;
		try {
			result = externalCreditGateway.credit(ExternalCreditRequest.of(event));
		} catch (BulkheadFullException | CallNotPermittedException noRoom) {
			// 보내지도 못했다. 돈은 안 나갔다 — 사고가 아니라 미룬 것이다.
			// 내부 송금이 쓸 스레드를 지키려고 일부러 여기서 멈춘다.
			pendingExternalCredits.rememberUnsent(event);
			return;
		} catch (ExternalCreditUnknownException noAnswer) {
			// ★ 답이 없다. 여기서 재시도하면 안 된다.
			// 다시 보내는 것은 "안 갔다"를 전제로 하는데 우리는 그걸 모른다.
			// 맞는 수단은 조회다 — 기록으로 남기고 확인 루프에 넘긴다.
			pendingExternalCredits.rememberUnknown(event);
			return;
		}

		if (!result.isAccepted()) {
			// 상대가 거절했다. 다시 보내도 결과가 같으므로 보상으로 넘어간다 —
			// 출금은 이미 나갔으니 돌려놔야 한다.
			log.warn("상대 은행이 거절했다 (bank={}, transferId={}, reason={})",
					event.toBankCode(), event.transferId(), result.reason());
			onExternalCreditRejected(event, result.reason());
			return;
		}

		UUID settlementAccountId = settlementAccounts.of(event.toBankCode(), event.currency());
		creditInternal(event, settlementAccountId);
	}

	/**
	 * 조회로 상대가 받았음이 확인된 건을 흐름에 되돌려 놓는다 (Step 2b).
	 * 타임아웃 직후의 경로와 같은 코드로 끝난다 — 확인만 늦게 됐을 뿐 결과는 같기 때문이다.
	 */
	public void onExternalCreditAccepted(TransferEvents.Debited event) {
		UUID settlementAccountId = settlementAccounts.of(event.toBankCode(), event.currency());
		creditInternal(event, settlementAccountId);
	}

	/**
	 * 상대 은행이 거절한 건. 출금은 이미 나갔으니 보상으로 넘긴다.
	 * 호출 응답으로 바로 거절됐든 조회로 뒤늦게 확인됐든 같은 코드로 끝난다.
	 */
	public void onExternalCreditRejected(TransferEvents.Debited event, String reason) {
		sagaStepRunner.recordFailure(ConsumedEvent.of(event),
				NextEvent.of(event.creditFailed(event.toAccountId(), "상대 은행 거절: " + reason)));
	}

	/** 우리 계좌(고객 계좌 또는 정산 계좌)에 입금한다. 여기부터는 내부·외부가 같다. */
	private void creditInternal(TransferEvents.Debited event, UUID creditAccountId) {
		SagaStep credit = new SagaStep(creditAccountId,
				BalanceChange.transferCredit(event.amount(), event.currency()),
				balance -> NextEvent.of(event.credited(creditAccountId, balance.total())));
		// 여기서부터가 진짜 문제다. 출금은 이미 나갔는데 입금이 안 됐으므로 돈이 공중에 뜬다.
		sagaStepRunner.run(ConsumedEvent.of(event), credit,
				reason -> NextEvent.of(event.creditFailed(creditAccountId, reason)));
	}

	/**
	 * 입금 실패 → 보상: 출금 계좌에 돈을 돌려놓는다.
	 *
	 * 보상도 결국 잔액 변경이라 전진 단계와 똑같은 장치(분산 락 + 처리 흔적 + Outbox)를 쓴다.
	 * 다른 점은 실패했을 때다. 전진 단계는 실패 이벤트를 남기고 끝내지만,
	 * 환불이 실패하면 남길 곳이 없다 — 그대로 두면 고객 돈이 사라진 채로 끝난다.
	 * 그래서 예외를 밖으로 던져 컨슈머 재시도에 맡기고, 끝내 안 되면 DLT로 보낸다(사람이 봐야 한다).
	 */
	public void onCreditFailed(TransferEvents.CreditFailed event) {
		SagaStep refund = new SagaStep(event.fromAccountId(),
				BalanceChange.transferRefund(event.amount(), event.currency()),
				balance -> NextEvent.of(event.debitReversed(balance.total())));
		sagaStepRunner.compensate(ConsumedEvent.of(event), refund);
	}
}
