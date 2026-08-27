package com.remittance.account.saga;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import com.remittance.account.external.ExternalBankClient;
import com.remittance.account.external.ExternalCreditResult;
import com.remittance.account.settlement.SettlementAccounts;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.service.AccountService;
import com.remittance.account.support.Timestamps;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 송금 Saga에서 Account Service가 맡은 단계들.
 *
 * <pre>
 *   transfer.requested     ──▶ 출금 ──▶ transfer.debited
 *                            └ 실패 ──▶ transfer.debit-failed
 *   transfer.debited       ──▶ 입금 ──▶ transfer.credited
 *                            └ 실패 ──▶ transfer.credit-failed
 *   transfer.credit-failed ──▶ 환불 ──▶ transfer.debit-reversed   (보상)
 * </pre>
 *
 * <p>오케스트레이터가 지시하는 게 아니라 <b>각 서비스가 이벤트를 보고 스스로 다음을 발행</b>한다
 * (Choreography). 대신 흐름 전체를 한눈에 볼 수 있는 곳이 없어지므로,
 * 어떤 이벤트가 어떤 이벤트를 낳는지는 이 클래스 주석과 {@link TransferEvents}에 남긴다.
 *
 * <p><b>전진 단계와 보상 단계는 실패했을 때의 처신이 다르다.</b>
 * 전진 단계는 실패하면 실패 이벤트를 남기고 물러난다. 보상 단계는 물러날 곳이 없다 —
 * 보상의 보상은 없으므로 예외를 그대로 밖으로 내보내 재배달에 맡긴다.
 */
@Service
@RequiredArgsConstructor
public class TransferSagaService {

	private static final Logger log = LoggerFactory.getLogger(TransferSagaService.class);

	private final AccountService accountService;
	private final SagaStepExecutor sagaStepExecutor;
	private final ExternalBankClient externalBankClient;
	private final SettlementAccounts settlementAccounts;

	/** 실패했을 때 대신 남길 이벤트. 전진 단계만 갖는다. */
	private record Fallback(String eventType, Object body) {
	}

	/** 송금 접수 → 출금 계좌에서 뺀다. */
	public void onRequested(TransferEvents.Requested event) {
		runStep(TransferEvents.REQUESTED, event.transferId(), event.fromAccountId(),
				balance -> balance.debit(event.amount(), event.currency()),
				TransferEvents.DEBITED,
				balance -> new TransferEvents.Debited(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						// 여기까지 실어 날라야 입금 단계가 어디로 보낼지 안다 (Phase 6.5).
						event.toBankCode(), event.toAccountNumber(),
						event.amount(), event.currency(), balance.total(), Timestamps.now()),
				new SagaStepExecutor.BalanceChange(AccountEvents.BalanceChangeReason.TRANSFER_DEBIT,
						AccountEvents.TransactionDirection.DEBIT, event.amount()),
				// 출금이 실패했으면 아직 움직인 돈이 없다. 되돌릴 것 없이 송금만 종결하면 된다.
				reason -> new Fallback(TransferEvents.DEBIT_FAILED, new TransferEvents.DebitFailed(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						event.amount(), event.currency(), reason, Timestamps.now())));
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
	 * <h2>HTTP 호출이 트랜잭션 밖에 있다 ★</h2>
	 * <b>느린 상대가 DB 트랜잭션을 붙들면 안 된다.</b> 상대 은행이 3초를 끌면 커넥션도 3초
	 * 묶이고, 그 커넥션은 우리 <b>내부</b> 송금이 쓸 것이었다. 남의 사정으로 우리 일이 멈춘다.
	 *
	 * <p>그래도 <b>스레드는 묶인다</b> — 컨슈머 스레드가 응답을 기다린다. 이건 아직 안 고쳤고,
	 * 고치려면 격벽(bulkhead)이 필요하다. Phase 6 Step 4가 여기서 진짜 근거를 얻는다.
	 *
	 * <h2>재시도가 안전한 이유</h2>
	 * 호출이 멱등성 흔적({@code processed_events})보다 <b>앞에</b> 있어서, 재배달되면
	 * 상대 은행을 다시 부른다. 그게 안전한 이유는 오직 <b>상대가 송금 ID로 멱등하기 때문</b>이다.
	 * 우리 DB의 제약이 아니라 <b>남의 약속</b>에 기대고 있다 — 그게 서비스 경계를 넘는 멱등성이다.
	 *
	 * <h2>정산 계좌로 적는다</h2>
	 * 상대가 받았다고 하면 <b>그 은행의 정산 계좌</b>에 입금한다. 상대 계좌를 우리 원장에
	 * 적을 수는 없지만, "그 은행에 지급할 채무"는 우리 장부의 것이다.
	 * 그래서 원장은 두 다리를 그대로 보고, <b>원장·대사 로직을 하나도 안 고쳐도 된다.</b>
	 */
	private void creditExternal(TransferEvents.Debited event) {
		ExternalCreditResult result = externalBankClient.credit(
				event.toBankCode(), event.transferId(), event.toAccountNumber(),
				event.amount(), event.currency());

		if (!result.isAccepted()) {
			// 상대가 거절했다. 다시 보내도 결과가 같으므로 보상으로 넘어간다 —
			// 출금은 이미 나갔으니 돌려놔야 한다.
			log.warn("상대 은행이 거절했다 (bank={}, transferId={}, reason={})",
					event.toBankCode(), event.transferId(), result.reason());
			recordFailure(TransferEvents.DEBITED, event.transferId(),
					new Fallback(TransferEvents.CREDIT_FAILED, new TransferEvents.CreditFailed(
							event.transferId(), event.fromAccountId(), event.toAccountId(),
							event.amount(), event.currency(),
							"상대 은행 거절: " + result.reason(), Timestamps.now())));
			return;
		}

		UUID settlementAccountId = settlementAccounts.of(event.toBankCode(), event.currency());
		creditInternal(event, settlementAccountId);
	}

	/** 우리 계좌(고객 계좌 또는 정산 계좌)에 입금한다. 여기부터는 내부·외부가 같다. */
	private void creditInternal(TransferEvents.Debited event, UUID creditAccountId) {
		runStep(TransferEvents.DEBITED, event.transferId(), creditAccountId,
				balance -> balance.credit(event.amount(), event.currency()),
				TransferEvents.CREDITED,
				balance -> new TransferEvents.Credited(
						// 외부 송금이면 여기 담기는 것은 <b>정산 계좌</b>다.
						// 원장이 두 다리를 맞추는 기준이 되므로 실제로 입금된 계좌여야 한다.
						event.transferId(), event.fromAccountId(), creditAccountId,
						event.amount(), event.currency(), event.fromBalanceAfter(), balance.total(),
						Timestamps.now()),
				new SagaStepExecutor.BalanceChange(AccountEvents.BalanceChangeReason.TRANSFER_CREDIT,
						AccountEvents.TransactionDirection.CREDIT, event.amount()),
				// 여기서부터가 진짜 문제다. 출금은 이미 나갔는데 입금이 안 됐으므로 돈이 공중에 뜬다.
				reason -> new Fallback(TransferEvents.CREDIT_FAILED, new TransferEvents.CreditFailed(
						event.transferId(), event.fromAccountId(), creditAccountId,
						event.amount(), event.currency(), reason, Timestamps.now())));
	}

	/**
	 * 입금 실패 → <b>보상</b>: 출금 계좌에 돈을 돌려놓는다.
	 *
	 * <p>보상도 결국 잔액 변경이라 전진 단계와 똑같은 장치(분산 락 + 처리 흔적 + Outbox)를 쓴다.
	 * 다른 점은 실패했을 때다. 전진 단계는 실패 이벤트를 남기고 끝내지만,
	 * 환불이 실패하면 남길 곳이 없다 — 그대로 두면 고객 돈이 사라진 채로 끝난다.
	 * 그래서 예외를 밖으로 던져 컨슈머 재시도에 맡기고, 끝내 안 되면 DLT로 보낸다(사람이 봐야 한다).
	 */
	public void onCreditFailed(TransferEvents.CreditFailed event) {
		runStep(TransferEvents.CREDIT_FAILED, event.transferId(), event.fromAccountId(),
				balance -> balance.credit(event.amount(), event.currency()),
				TransferEvents.DEBIT_REVERSED,
				balance -> new TransferEvents.DebitReversed(
						event.transferId(), event.fromAccountId(), event.amount(), event.currency(),
						balance.total(), event.failureReason(), Timestamps.now()),
				new SagaStepExecutor.BalanceChange(AccountEvents.BalanceChangeReason.TRANSFER_REFUND,
						AccountEvents.TransactionDirection.CREDIT, event.amount()),
				null);
	}

	/**
	 * @param fallback 업무적 실패 시 대신 남길 이벤트를 만든다. {@code null}이면 <b>보상 단계</b>라는
	 *                 뜻으로, 실패를 삼키지 않고 밖으로 던져 재배달되게 한다.
	 */
	private void runStep(String consumedEventType, UUID transferId, UUID accountId,
			Consumer<AccountBalance> mutation, String nextEventType,
			Function<AccountBalance, Object> nextEventBody,
			SagaStepExecutor.BalanceChange balanceChange, Function<String, Fallback> fallback) {
		try {
			// 잔액 변경이므로 REST 진입점과 똑같은 동시성 방어(분산 락 + 낙관적 락)를 거친다.
			accountService.guarded(accountId, balanceChange.direction(), shardNo -> {
				sagaStepExecutor.execute(consumedEventType, transferId, accountId, shardNo,
						mutation, nextEventType, nextEventBody, balanceChange);
				return null;
			});
		} catch (DataIntegrityViolationException duplicate) {
			// 처리 흔적 INSERT가 PK 중복으로 막혔다 = 이미 처리한 이벤트.
			// 재전송은 at-least-once의 정상 동작이므로 조용히 넘어간다.
			log.info("이미 처리한 이벤트라 건너뛴다 (event={}, transferId={})", consumedEventType, transferId);
		} catch (AccountNotFoundException | InsufficientBalanceException
				| AccountNotActiveException | CurrencyMismatchException businessFailure) {
			// 다시 시도해도 결과가 같은 실패다. 재시도해봐야 소용없으므로 흐름을 여기서 꺾는다.
			if (fallback == null) {
				log.error("보상 단계가 실패했다 - 출금이 되돌아가지 않았다. 재시도 후에도 실패하면 DLT로 간다"
						+ " (event={}, transferId={}, reason={})",
						consumedEventType, transferId, businessFailure.getMessage());
				throw businessFailure;
			}
			Fallback next = fallback.apply(businessFailure.getMessage());
			log.warn("Saga 단계 실패 - {}를 발행해 흐름을 꺾는다 (event={}, transferId={}, reason={})",
					next.eventType(), consumedEventType, transferId, businessFailure.getMessage());
			recordFailure(consumedEventType, transferId, next);
		}
	}

	private void recordFailure(String consumedEventType, UUID transferId, Fallback fallback) {
		try {
			sagaStepExecutor.recordFailure(consumedEventType, transferId, fallback.eventType(), fallback.body());
		} catch (DataIntegrityViolationException duplicate) {
			// 같은 이벤트가 동시에 두 번 처리돼 둘 다 실패한 경우. 실패 이벤트는 한 번만 나가면 된다.
			log.info("이미 실패로 기록된 이벤트라 건너뛴다 (event={}, transferId={})",
					consumedEventType, transferId);
		}
	}
}
