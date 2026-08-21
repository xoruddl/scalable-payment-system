package com.remittance.account.exception;

import java.util.UUID;

/**
 * 아직 발행되지 않은 분개 항목이 남아 있는 계좌라 개시 잔액을 이월할 수 없다.
 *
 * <p>Outbox에 미발행 항목이 있다는 건 <b>잔액에는 이미 반영됐지만 원장은 아직 모르는 변경</b>이
 * 있다는 뜻이다. 이 상태에서 "잔액 − 원장 합"을 이월하면 그 변경을 두 번 세게 된다 —
 * 이월분에 한 번, 뒤늦게 도착한 분개에 또 한 번.
 *
 * <p>잔액 스냅샷 검사({@link StaleBalanceSnapshotException})만으로는 이걸 못 잡는다.
 * 잔액은 그대로인데 원장 쪽만 뒤처져 있는 상황이기 때문이다.
 */
public class UnpublishedJournalException extends RuntimeException {

	public UnpublishedJournalException(UUID accountId) {
		super("계좌 %s에 아직 발행되지 않은 분개가 남아 있습니다. 원장에 반영된 뒤 다시 시도하세요."
				.formatted(accountId));
	}
}
