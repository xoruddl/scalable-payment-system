package com.remittance.account.saga;

import com.remittance.account.domain.AccountBalance;
import com.remittance.account.messaging.AccountEvents;

import java.math.BigDecimal;

/**
 * 이 단계가 잔액을 어떻게 움직였는지 — 분개장에 남길 내용이다.
 * 금액만으로는 "송금 출금"과 "보상 환불"을 구분할 수 없어 이유를 함께 넘긴다.
 *
 * {@code SagaStepExecutor} 안에 있던 것을 꺼냈다 (2026-09-11). {@link SagaStep}이
 * 이걸 품게 되면서 실행기의 중첩 타입으로 두면 이름이 거꾸로 읽힌다 —
 * 한 단계를 서술하는 말들끼리 같은 자리에 있어야 한다.
 *
 * 잔액을 바꾸는 일도 여기서 한다 (2026-09-13)
 * 전에는 {@link SagaStep}이 잔액 변경 람다({@code balance.debit(...)})를 따로 들고 있었다.
 * 그러면 "출금"을 람다·방향·이유 세 곳에 적어야 했고, {@code debit}인데 {@code CREDIT}을
 * 적어도 컴파일러가 못 잡았다. 방향이 무엇을 부를지 정하므로 둘을 한 곳에 둔다.
 * 만들 때는 아래 팩토리를 쓴다 — 이유와 방향의 짝이 거기서 고정된다.
 *
 * 정식 생성자는 인자가 넷이다. record라 숨길 수 없어 두고, 밖에서는 팩토리만 쓴다.
 */
public record BalanceChange(
		AccountEvents.BalanceChangeReason reason,
		AccountEvents.TransactionDirection direction,
		BigDecimal amount,
		String currency
) {

	/** 송금 출금. */
	public static BalanceChange transferDebit(BigDecimal amount, String currency) {
		return new BalanceChange(AccountEvents.BalanceChangeReason.TRANSFER_DEBIT,
				AccountEvents.TransactionDirection.DEBIT, amount, currency);
	}

	/** 송금 입금. */
	public static BalanceChange transferCredit(BigDecimal amount, String currency) {
		return new BalanceChange(AccountEvents.BalanceChangeReason.TRANSFER_CREDIT,
				AccountEvents.TransactionDirection.CREDIT, amount, currency);
	}

	/** 입금 실패로 되돌리는 출금 (보상). 돈이 돌아오므로 방향은 입금이다. */
	public static BalanceChange transferRefund(BigDecimal amount, String currency) {
		return new BalanceChange(AccountEvents.BalanceChangeReason.TRANSFER_REFUND,
				AccountEvents.TransactionDirection.CREDIT, amount, currency);
	}

	/** 잔액에 적용한다. 방향이 뺄지 넣을지를 정한다. */
	public void applyTo(AccountBalance balance) {
		switch (direction) {
			case DEBIT -> balance.debit(amount, currency);
			case CREDIT -> balance.credit(amount, currency);
		}
	}
}
