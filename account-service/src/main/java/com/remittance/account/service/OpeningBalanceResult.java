package com.remittance.account.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 개시 잔액 이월을 시도한 결과.
 *
 * <p>"안 심었다"에 두 가지가 섞이면 곤란해서 결과를 나눠 돌려준다 —
 * <b>이미 이월한 계좌</b>와 <b>애초에 맞아서 심을 게 없던 계좌</b>는 전혀 다른 얘기다.
 * 앞의 것은 이월 이력이 있고, 뒤의 것은 원장이 처음부터 온전했다는 뜻이다.
 *
 * @param amount 이월한 금액. 부호가 있다 — 원장이 잔액보다 모자랐으면 양수다. 안 심었으면 {@code null}.
 */
public record OpeningBalanceResult(UUID accountId, Outcome outcome, BigDecimal amount) {

	public enum Outcome {
		/** 차이만큼 분개를 심었다. */
		SEEDED,
		/** 전에 이미 이월한 계좌다. */
		ALREADY_CARRIED,
		/** 잔액과 원장이 이미 맞아 심을 게 없었다. */
		ALREADY_CONSISTENT
	}

	static OpeningBalanceResult seeded(UUID accountId, BigDecimal amount) {
		return new OpeningBalanceResult(accountId, Outcome.SEEDED, amount);
	}

	static OpeningBalanceResult alreadyCarried(UUID accountId) {
		return new OpeningBalanceResult(accountId, Outcome.ALREADY_CARRIED, null);
	}

	static OpeningBalanceResult alreadyConsistent(UUID accountId) {
		return new OpeningBalanceResult(accountId, Outcome.ALREADY_CONSISTENT, null);
	}
}
