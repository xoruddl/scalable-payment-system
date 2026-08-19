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
 * 송금 Saga에서 Account Service가 맡은 두 단계.
 *
 * <pre>
 *   transfer.requested ──▶ 출금 ──▶ transfer.debited
 *   transfer.debited   ──▶ 입금 ──▶ transfer.credited
 * </pre>
 *
 * <p>오케스트레이터가 지시하는 게 아니라 <b>각 서비스가 이벤트를 보고 스스로 다음을 발행</b>한다
 * (Choreography). 대신 흐름 전체를 한눈에 볼 수 있는 곳이 없어지므로,
 * 어떤 이벤트가 어떤 이벤트를 낳는지는 이 클래스 주석과 {@link TransferEvents}에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class TransferSagaService {

	private static final Logger log = LoggerFactory.getLogger(TransferSagaService.class);

	private final AccountService accountService;
	private final SagaStepExecutor sagaStepExecutor;

	/** 송금 접수 → 출금 계좌에서 뺀다. */
	public void onRequested(TransferEvents.Requested event) {
		runStep(TransferEvents.REQUESTED, event.transferId(), event.fromAccountId(),
				account -> account.debit(event.amount(), event.currency()),
				TransferEvents.DEBITED,
				account -> new TransferEvents.Debited(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						event.amount(), event.currency(), account.getBalance(), Timestamps.now()));
	}

	/** 출금 완료 → 입금 계좌에 넣는다. */
	public void onDebited(TransferEvents.Debited event) {
		runStep(TransferEvents.DEBITED, event.transferId(), event.toAccountId(),
				account -> account.credit(event.amount(), event.currency()),
				TransferEvents.CREDITED,
				account -> new TransferEvents.Credited(
						event.transferId(), event.fromAccountId(), event.toAccountId(),
						event.amount(), event.currency(), event.fromBalanceAfter(), account.getBalance(),
						Timestamps.now()));
	}

	private void runStep(String consumedEventType, UUID transferId, UUID accountId,
			Consumer<Account> mutation, String nextEventType, Function<Account, Object> nextEventBody) {
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
			// 다시 시도해도 결과가 같은 실패다. 재시도해봐야 소용없으므로 여기서 멈춘다.
			// TODO(Step 4b): transfer.failed를 발행해 송금을 실패로 종결하고 출금을 보상한다.
			//                지금은 송금이 PENDING인 채로 남는다.
			log.error("Saga 단계 실패 - 보상은 Step 4b에서 처리한다 (event={}, transferId={}, reason={})",
					consumedEventType, transferId, businessFailure.getMessage());
		}
	}
}
