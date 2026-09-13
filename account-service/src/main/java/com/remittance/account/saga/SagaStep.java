package com.remittance.account.saga;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.messaging.AccountEvents;

import java.util.UUID;
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
 * 다섯에서 셋으로 (2026-09-13)
 * 묶고 나서도 부르는 쪽은 다섯을 전부 손으로 적었다. "출금"을 잔액 변경 람다·방향·이유 세 곳에,
 * 다음 이벤트를 종류와 본문 두 곳에 적었고, 어긋나도 컴파일러가 못 잡았다.
 * 잔액 변경은 {@link BalanceChange}가, 이벤트 종류는 {@link NextEvent}가 본문 타입으로 정하게 해
 * 같은 사실을 한 번만 적게 했다.
 *
 * 값을 꺼내 쓰는 대신 시킨다
 * {@code step.balanceChange().applyTo(balance)} 대신 {@link #mutate}를 둔다.
 * 점을 하나로 유지하려는 것만이 아니라, 호출부가 이 record의 속을 몰라도 되게 하려는 것이다.
 */
public record SagaStep(
		UUID accountId,
		BalanceChange balanceChange,
		/** 바뀐 잔액을 받아 다음 이벤트를 만든다. 본문에 변경 후 잔액이 실리므로 값이 아니라 함수다. */
		Function<AccountBalance, NextEvent> next
) {

	/** 잔액을 바꾼다. */
	public void mutate(AccountBalance balance) {
		balanceChange.applyTo(balance);
	}

	/** 바뀐 잔액으로 다음 단계 이벤트를 만든다. */
	public NextEvent nextEvent(AccountBalance balance) {
		return next.apply(balance);
	}

	/** 넣는 단계인가 빼는 단계인가. 읽을 조각 수가 여기서 갈린다. */
	public AccountEvents.TransactionDirection direction() {
		return balanceChange.direction();
	}
}
