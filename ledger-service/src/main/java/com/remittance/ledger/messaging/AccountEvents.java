package com.remittance.ledger.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.remittance.ledger.domain.BalanceChangeReason;
import com.remittance.ledger.domain.TransactionDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Account가 발행하는 잔액 변경 이벤트 — <b>이 서비스가 원장에 남기는 유일한 입력</b>이다.
 *
 * <p>Step 5a 전에는 {@code transfer.credited}를 듣고 송금 한 건을 두 줄로 적었다. 그러면
 * 송금이 아닌 잔액 변경(입출금 API, 보상 환불)이 원장에 남지 않아 <b>"원장 합 = 잔액"이 성립하지
 * 않았고</b>, 정합성 대사를 할 수가 없었다. 이제 잔액이 움직이는 모든 경로가 이 이벤트를 낸다.
 *
 * <p>서비스 간 공유 모듈을 두지 않기로 했으므로 account 서비스도 같은 계약을 각자 정의한다.
 * 필드 이름이 곧 계약이니 바꿀 때는 양쪽을 함께 확인해야 한다.
 */
public final class AccountEvents {

	/** Account가 발행: 잔액이 바뀌었다 → 원장에 한 줄 남길 차례 */
	public static final String BALANCE_CHANGED = "account.balance-changed";

	private AccountEvents() {
	}

	/**
	 * 원장 한 줄에 필요한 값이 모두 담겨 있다 — 다른 서비스에 되묻지 않는다.
	 *
	 * @param entryId    발행하는 쪽이 만들어 고정한 ID. 이걸 문서 ID로 삼아 재수신을 멱등하게 흡수한다.
	 * @param transferId 송금 때문에 움직인 경우에만 채워진다. 입출금 API로 움직였으면 {@code null}.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BalanceChanged(
			UUID entryId,
			UUID accountId,
			BalanceChangeReason reason,
			TransactionDirection direction,
			BigDecimal amount,
			BigDecimal balanceAfter,
			String currency,
			UUID transferId,
			Instant occurredAt
	) {
	}
}
