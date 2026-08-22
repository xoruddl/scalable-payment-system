package com.remittance.reconciliation.domain;

/** 대사가 찾아낼 수 있는 어긋남의 종류. */
public enum FindingType {

	/**
	 * 계좌 잔액과 원장 합이 다르다. <b>돈이 실제로 어긋난 것</b>이라 가장 무겁다.
	 * 잔액 변경 이벤트가 원장에 닿지 못했거나(DLT), 원장에 없는 경로로 잔액이 바뀐 경우다.
	 */
	BALANCE_MISMATCH,

	/**
	 * 송금이 종결되지 못한 채 오래 남아 있다. Saga가 어디선가 끊겼다는 뜻이다.
	 * 돈이 뜬 채로 멈춰 있을 수 있다(보상까지 실패한 경우).
	 */
	UNSETTLED_TRANSFER,

	/**
	 * 멱등성 키가 IN_PROGRESS로 남아 재요청이 계속 막힌다.
	 * 돈이 어긋난 건 아니지만 사용자는 송금을 못 한다.
	 */
	STRANDED_IDEMPOTENCY_KEY
}
