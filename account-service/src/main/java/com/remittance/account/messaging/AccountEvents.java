package com.remittance.account.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 계좌 자체에서 일어난 일을 알리는 이벤트. 송금 Saga의 단계 이벤트({@link TransferEvents})와는
 * 결이 다르다 — 저쪽은 "흐름의 다음 단계"를 부르고, 이쪽은 <b>일어난 사실을 기록으로 남기라고</b> 알린다.
 *
 * <p>파티션 키는 <b>계좌 ID</b>다. 한 계좌의 잔액 변경은 순서대로 소비되어야 잔액 추이가 뒤섞이지 않는다.
 * (Saga 이벤트는 송금 ID를 키로 쓴다 — 목적이 다르면 키도 다르다.)
 */
public final class AccountEvents {

	/**
	 * 잔액이 바뀌었다. <b>원장은 이 이벤트만 보고 기록한다.</b>
	 *
	 * <p>Phase 2 Step 5a 전에는 원장이 {@code transfer.credited}만 듣고 송금 두 줄을 적었다.
	 * 그러면 <b>송금이 아닌 잔액 변경(입출금 API, 보상 환불)이 원장에 남지 않아</b>
	 * "원장 합 = 잔액"이 애초에 성립하지 않았고, 정합성 대사를 할 수가 없었다.
	 * 이제 잔액이 움직이는 모든 경로가 이 이벤트를 낸다.
	 */
	public static final String BALANCE_CHANGED = "account.balance-changed";

	private AccountEvents() {
	}

	/**
	 * {@link #BALANCE_CHANGED} 본문 = 원장에 남을 분개 항목 한 줄.
	 *
	 * @param entryId     이 변경 하나를 가리키는 ID. <b>Outbox에 기록할 때 한 번 만들어 고정</b>되므로,
	 *                    재전송이 와도 같은 값이다. 원장은 이걸 문서 ID로 삼아 멱등하게 기록한다.
	 * @param transferId  송금 때문에 움직인 경우에만 채워진다. 입출금 API로 움직였으면 {@code null}.
	 * @param balanceAfter 변경 <b>후</b> 잔액. 원장만 보고도 잔액 추이를 재구성할 수 있어야 한다.
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

	/** 잔액이 어느 쪽으로 움직였는가. */
	public enum TransactionDirection {
		DEBIT, CREDIT
	}

	/**
	 * 왜 움직였는가. 금액만으로는 "송금 출금"과 "보상 환불"을 구분할 수 없어서 함께 남긴다 —
	 * 대사에서 어긋난 계좌를 찾았을 때 무엇 때문인지 바로 보이게 하려는 것이다.
	 */
	public enum BalanceChangeReason {
		/** 송금 출금 */
		TRANSFER_DEBIT,
		/** 송금 입금 */
		TRANSFER_CREDIT,
		/** 입금 실패로 되돌린 출금 (보상) */
		TRANSFER_REFUND,
		/** 송금과 무관한 입금 */
		DEPOSIT,
		/** 송금과 무관한 출금 */
		WITHDRAWAL,
		/**
		 * 원장을 도입하기 전에 이미 쌓여 있던 잔액을 <b>한 줄로 이월</b>한 것.
		 *
		 * <p>다른 이유들과 성격이 다르다 — 이 항목은 <b>잔액을 움직이지 않는다.</b> 과거에 실제로
		 * 일어났지만 원장에 남지 않은 변경들을 뭉뚱그려 적어, 지금 잔액과 원장 합을 맞추는 것이다.
		 * (Step 5a 이전에 만들어진 계좌들이 영원히 BALANCE_MISMATCH로 잡히던 문제)
		 *
		 * <p>계좌당 <b>한 번만</b> 남는다. 두 번 심으면 그 즉시 원장이 잔액보다 커진다.
		 */
		OPENING_BALANCE
	}
}
