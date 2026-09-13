package com.remittance.account.saga;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.messaging.AccountEvents;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Saga 한 단계의 정의 — 어느 계좌를 어떻게 바꾸고, 그 결과로 무엇을 발행하는가.
 *
 * 왜 묶었나 (2026-09-11)
 * 이 다섯이 {@code runStep}과 {@code execute}에 인자로 나란히 늘어서 있었다.
 * 둘 다 인자가 여덟 개였고 {@code CLEAN_CODE.md}의 "4개 이상은 허용하지 않는다"를
 * 어긴 채였다. 그런데 다섯은 원래 한 덩어리다 — 출금이냐 입금이냐 환불이냐가
 * 이 다섯을 한꺼번에 정한다. 흩어놓을 이유가 없었다.
 *
 * 값을 꺼내 쓰는 대신 시킨다
 * {@code step.mutation().accept(balance)} 대신 {@link #mutate}를 둔다.
 * 점을 하나로 유지하려는 것만이 아니라, 호출부가 이 record의 속을 몰라도 되게 하려는 것이다.
 */
public record SagaStep(
		UUID accountId,
		Consumer<AccountBalance> mutation,
		String nextEventType,
		Function<AccountBalance, Object> nextEventBody,
		BalanceChange balanceChange
) {

	/** 잔액을 바꾼다. */
	public void mutate(AccountBalance balance) {
		mutation.accept(balance);
	}

	/** 바뀐 잔액으로 다음 단계 이벤트 본문을 만든다. */
	public Object nextEventPayload(AccountBalance balance) {
		return nextEventBody.apply(balance);
	}

	/** 넣는 단계인가 빼는 단계인가. 읽을 조각 수가 여기서 갈린다. */
	public AccountEvents.TransactionDirection direction() {
		return balanceChange.direction();
	}
}
