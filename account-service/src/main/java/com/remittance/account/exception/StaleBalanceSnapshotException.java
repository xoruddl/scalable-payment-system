package com.remittance.account.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 개시 잔액 이월을 요청하면서 들고 온 잔액 스냅샷이 이미 낡았다.
 *
 * <p>이월 금액은 <b>"그때 본 잔액 − 그때 본 원장 합"</b>이다. 그 사이 잔액이 움직였다면 두 값이
 * 서로 다른 시점을 가리키게 되어, 계산한 차이가 더는 맞지 않는다. 그대로 심으면 맞추려던 원장이
 * 오히려 어긋난다. 그래서 <b>거절하고 다시 읽어오게</b> 한다 (compare-and-set과 같은 얘기다).
 */
public class StaleBalanceSnapshotException extends RuntimeException {

	public StaleBalanceSnapshotException(UUID accountId, BigDecimal observed, BigDecimal actual) {
		super("계좌 %s의 잔액 스냅샷이 낡았습니다 (봤을 때 %s, 지금 %s). 다시 읽고 이월하세요."
				.formatted(accountId, observed.toPlainString(), actual.toPlainString()));
	}
}
