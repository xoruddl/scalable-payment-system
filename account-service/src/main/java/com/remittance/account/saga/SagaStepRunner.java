package com.remittance.account.saga;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import com.remittance.account.service.BalanceGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Saga 단계 하나를 안전하게 돌리는 장치. 어느 단계든 똑같이 적용된다.
 *
 *   1. 잔액 변경이므로 REST 진입점과 같은 동시성 방어({@link BalanceGuard})를 거친다
 *   2. 한 트랜잭션으로 실행한다 ({@link SagaStepExecutor})
 *   3. 결과를 셋으로 가른다 — 성공, 이미 처리한 이벤트, 업무적 실패
 *
 * 왜 {@link TransferSagaService}에서 떼어냈나 (2026-09-13)
 * 그 클래스가 두 가지를 겸하고 있었다. "이 이벤트가 오면 이 단계를 하고 이 이벤트를 낸다"는
 * 흐름과, 그 단계를 락·트랜잭션·예외 분류로 감싸는 장치다. 흐름은 송금 규칙이 바뀔 때,
 * 장치는 동시성·멱등성 방식이 바뀔 때 바뀐다. 바뀌는 이유가 다르다.
 * 대가는 {@link BalanceGuard} 때와 같다 — 단계 하나를 따라가려면 파일을 하나 더 연다.
 *
 * 전진 단계와 보상 단계는 실패했을 때의 처신이 다르다
 * 전진 단계는 실패하면 실패 이벤트를 남기고 물러난다. 보상 단계는 물러날 곳이 없다 —
 * 보상의 보상은 없으므로 예외를 그대로 밖으로 내보내 재배달에 맡긴다.
 * 그래서 입구가 {@link #run}과 {@link #compensate} 둘이다. 전에는 fallback 자리에
 * {@code null}을 넘겨 보상임을 표시했는데, 부르는 쪽에서는 그 {@code null}의 뜻이 안 보였다.
 */
@Component
@RequiredArgsConstructor
public class SagaStepRunner {

	private static final Logger log = LoggerFactory.getLogger(SagaStepRunner.class);

	private final BalanceGuard balanceGuard;
	private final SagaStepExecutor sagaStepExecutor;

	/**
	 * 전진 단계를 실행한다.
	 *
	 * @param fallback 업무적 실패 시 대신 남길 이벤트를 만든다
	 */
	public void run(ConsumedEvent consumed, SagaStep step, Function<String, Fallback> fallback) {
		execute(consumed, step, fallback);
	}

	/** 보상 단계를 실행한다. 업무적 실패도 삼키지 않고 밖으로 던져 재배달되게 한다. */
	public void compensate(ConsumedEvent consumed, SagaStep step) {
		execute(consumed, step, null);
	}

	/**
	 * 단계를 실행하지 않고 실패 사실만 남긴다.
	 * 상대 은행이 거절했을 때처럼 잔액을 바꾸기 전에 결론이 난 경우에 쓴다.
	 */
	public void recordFailure(ConsumedEvent consumed, Fallback fallback) {
		try {
			sagaStepExecutor.recordFailure(consumed, fallback);
		} catch (DataIntegrityViolationException duplicate) {
			// 같은 이벤트가 동시에 두 번 처리돼 둘 다 실패한 경우. 실패 이벤트는 한 번만 나가면 된다.
			log.info("이미 실패로 기록된 이벤트라 건너뛴다 (event={}, transferId={})",
					consumed.type(), consumed.transferId());
		}
	}

	/**
	 * @param fallback {@code null}이면 보상 단계다. 이 뜻은 이 클래스 밖으로 새지 않는다 —
	 *                 바깥은 {@link #run}과 {@link #compensate} 중 하나를 고른다.
	 */
	private void execute(ConsumedEvent consumed, SagaStep step, Function<String, Fallback> fallback) {
		try {
			balanceGuard.guarded(step.accountId(), step.direction(), shardNo -> {
				sagaStepExecutor.execute(consumed, step, shardNo);
				return null;
			});
		} catch (DataIntegrityViolationException duplicate) {
			// 처리 흔적 INSERT가 PK 중복으로 막혔다 = 이미 처리한 이벤트.
			// 재전송은 at-least-once의 정상 동작이므로 조용히 넘어간다.
			log.info("이미 처리한 이벤트라 건너뛴다 (event={}, transferId={})",
					consumed.type(), consumed.transferId());
		} catch (AccountNotFoundException | InsufficientBalanceException
				| AccountNotActiveException | CurrencyMismatchException businessFailure) {
			// 다시 시도해도 결과가 같은 실패다. 재시도해봐야 소용없으므로 흐름을 여기서 꺾는다.
			if (fallback == null) {
				log.error("보상 단계가 실패했다 - 출금이 되돌아가지 않았다. 재시도 후에도 실패하면 DLT로 간다"
						+ " (event={}, transferId={}, reason={})",
						consumed.type(), consumed.transferId(), businessFailure.getMessage());
				throw businessFailure;
			}
			Fallback next = fallback.apply(businessFailure.getMessage());
			log.warn("Saga 단계 실패 - {}를 발행해 흐름을 꺾는다 (event={}, transferId={}, reason={})",
					next.eventType(), consumed.type(), consumed.transferId(), businessFailure.getMessage());
			recordFailure(consumed, next);
		}
	}
}
