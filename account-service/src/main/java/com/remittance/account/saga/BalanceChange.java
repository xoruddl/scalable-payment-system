package com.remittance.account.saga;

import com.remittance.account.messaging.AccountEvents;

import java.math.BigDecimal;

/**
 * 이 단계가 잔액을 어떻게 움직였는지 — 분개장에 남길 내용이다.
 * 금액만으로는 "송금 출금"과 "보상 환불"을 구분할 수 없어 이유를 함께 넘긴다.
 *
 * {@code SagaStepExecutor} 안에 있던 것을 꺼냈다 (2026-09-11). {@link SagaStep}이
 * 이걸 품게 되면서 실행기의 중첩 타입으로 두면 이름이 거꾸로 읽힌다 —
 * 한 단계를 서술하는 말들끼리 같은 자리에 있어야 한다.
 */
public record BalanceChange(
		AccountEvents.BalanceChangeReason reason,
		AccountEvents.TransactionDirection direction,
		BigDecimal amount
) {
}
