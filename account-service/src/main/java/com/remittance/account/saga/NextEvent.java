package com.remittance.account.saga;

import com.remittance.account.messaging.TransferEvents;

/**
 * Saga 단계가 끝나고 Outbox에 남길 다음 이벤트 — 종류와 본문이 한 쌍이다.
 *
 * 성공하면 흐름을 잇는 이벤트, 업무적으로 실패하면 흐름을 꺾는 이벤트다.
 * 보상 단계는 실패 쪽을 갖지 않는다. 보상의 보상은 없으므로 예외를 그대로 밖으로 내보내
 * 재배달에 맡긴다 ({@link SagaStepRunner#compensate}).
 *
 * 종류는 본문의 타입이 정한다
 * 만들 때는 {@code of}를 쓴다. 종류 문자열과 본문을 따로 넘기면 {@code DEBITED}에
 * {@code Credited}를 실어도 컴파일러가 못 잡는다. 오버로드로 두면 본문 타입이 곧 종류다.
 *
 * {@code Fallback}이던 것을 넓혔다 (2026-09-13). 실패 이벤트만 한 쌍으로 묶여 있고
 * 성공 이벤트는 {@link SagaStep}에 종류와 본문으로 흩어져 있었는데, 둘은 같은 모양이다.
 */
public record NextEvent(String type, Object body) {

	public static NextEvent of(TransferEvents.Debited body) {
		return new NextEvent(TransferEvents.DEBITED, body);
	}

	public static NextEvent of(TransferEvents.Credited body) {
		return new NextEvent(TransferEvents.CREDITED, body);
	}

	public static NextEvent of(TransferEvents.DebitFailed body) {
		return new NextEvent(TransferEvents.DEBIT_FAILED, body);
	}

	public static NextEvent of(TransferEvents.CreditFailed body) {
		return new NextEvent(TransferEvents.CREDIT_FAILED, body);
	}

	public static NextEvent of(TransferEvents.DebitReversed body) {
		return new NextEvent(TransferEvents.DEBIT_REVERSED, body);
	}
}
