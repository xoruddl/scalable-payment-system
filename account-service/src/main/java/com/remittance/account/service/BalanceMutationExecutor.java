package com.remittance.account.service;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.outbox.BalanceJournal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * 입출금 API로 들어온 잔액 변경을 <b>하나의 트랜잭션</b>으로 처리한다 —
 * 잔액 변경과 분개 기록이 함께 커밋되어야 한다.
 *
 * <p>Saga 단계에는 {@code SagaStepExecutor}라는 짝이 따로 있다. 그쪽은 처리 흔적과 다음 단계
 * 이벤트까지 같은 트랜잭션에 묶어야 해서 하는 일이 더 많다. 공통은 {@link BalanceJournal}이다.
 *
 * <p>{@code @Transactional} 프록시가 걸리려면 호출부가 <b>다른 빈을 통해</b> 불러야 한다.
 */
@Component
@RequiredArgsConstructor
public class BalanceMutationExecutor {

	private final BalanceShards balanceShards;
	private final BalanceJournal balanceJournal;

	@Transactional
	public AccountBalance execute(UUID accountId, Consumer<AccountBalance> mutation,
			AccountEvents.BalanceChangeReason reason, AccountEvents.TransactionDirection direction,
			BigDecimal amount) {
		AccountBalance balance = balanceShards.load(accountId, direction);
		mutation.accept(balance);
		balanceShards.flush(balance);
		// 송금과 무관한 변경이라 transferId가 없다.
		balanceJournal.record(balance, reason, direction, amount, null);
		return balance;
	}
}
