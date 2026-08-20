package com.remittance.account.saga;

import com.remittance.account.domain.Account;
import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
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

	/** 실패했을 때 대신 남길 이벤트. 전진 단계만 갖는다. */
	private record Fallback(String eventType, Object body) {
	}

	/** 송금 접수 → 출금 계좌에서 뺀다. */
	public void onRequested(TransferEvents.Requested event) {
		runStep(TransferEvents.REQUESTED, event.transferId(), event.fromAccountId(),
				account -> account.debit(event.amount(), event.currency()),
				TransferEvents.DEBITED,
				account -> new TransferEvents.Debited(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						event.amount(), event.currency(), account.getBalance(), Timestamps.now()),
				// 출금이 실패했으면 아직 움직인 돈이 없다. 되돌릴 것 없이 송금만 종결하면 된다.
				reason -> new Fallback(TransferEvents.DEBIT_FAILED, new TransferEvents.DebitFailed(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						event.amount(), event.currency(), reason, Timestamps.now())));
	}

	/** 출금 완료 → 입금 계좌에 넣는다. */
	public void onDebited(TransferEvents.Debited event) {
		runStep(TransferEvents.DEBITED, event.transferId(), event.toAccountId(),
				account -> account.credit(event.amount(), event.currency()),
				TransferEvents.CREDITED,
				account -> new TransferEvents.Credited(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						event.amount(), event.currency(), event.fromBalanceAfter(), account.getBalance(),
						Timestamps.now()),
				// 여기서부터가 진짜 문제다. 출금은 이미 나갔는데 입금이 안 됐으므로 돈이 공중에 뜬다.
				reason -> new Fallback(TransferEvents.CREDIT_FAILED, new TransferEvents.CreditFailed(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
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
				account -> account.credit(event.amount(), event.currency()),
				TransferEvents.DEBIT_REVERSED,
				account -> new TransferEvents.DebitReversed(
						event.transferId(), event.fromAccountId(), event.amount(), event.currency(),
						account.getBalance(), event.failureReason(), Timestamps.now()),
				null);
	}

	/**
	 * @param fallback 업무적 실패 시 대신 남길 이벤트를 만든다. {@code null}이면 <b>보상 단계</b>라는
	 *                 뜻으로, 실패를 삼키지 않고 밖으로 던져 재배달되게 한다.
	 */
	private void runStep(String consumedEventType, UUID transferId, UUID accountId,
			Consumer<Account> mutation, String nextEventType, Function<Account, Object> nextEventBody,
			Function<String, Fallback> fallback) {
		try {
			// 잔액 변경이므로 REST 진입점과 똑같은 동시성 방어(분산 락 + 낙관적 락)를 거친다.
			accountService.guarded(accountId, () -> {
				sagaStepExecutor.execute(consumedEventType, transferId, accountId,
						mutation, nextEventType, nextEventBody);
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
