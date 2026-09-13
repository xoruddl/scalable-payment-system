package com.remittance.account.saga;

import com.remittance.account.messaging.TransferEvents;

import java.util.UUID;

/**
 * 방금 소비한 이벤트. 멱등성 판정 키이기도 하다 —
 * 이 두 값이 {@code processed_events}의 PK이고, 같은 쌍이 두 번 들어오면 INSERT가 막힌다.
 *
 * 따로 들고 다니면 인자 목록 곳곳에 같은 쌍이 늘어선다. 붙여두면
 * "이 단계가 무엇에 대한 응답인가"가 인자 하나로 읽힌다.
 *
 * 만들 때는 {@code of}를 쓴다. {@link NextEvent}와 같은 이유로 종류를 본문 타입이 정한다.
 */
public record ConsumedEvent(String type, UUID transferId) {

	public static ConsumedEvent of(TransferEvents.Requested event) {
		return new ConsumedEvent(TransferEvents.REQUESTED, event.transferId());
	}

	public static ConsumedEvent of(TransferEvents.Debited event) {
		return new ConsumedEvent(TransferEvents.DEBITED, event.transferId());
	}

	public static ConsumedEvent of(TransferEvents.CreditFailed event) {
		return new ConsumedEvent(TransferEvents.CREDIT_FAILED, event.transferId());
	}
}
