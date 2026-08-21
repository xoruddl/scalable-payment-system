package com.remittance.ledger.domain;

/**
 * 잔액이 왜 움직였는가. 금액만으로는 "송금 출금"과 "보상 환불"을 구분할 수 없어 함께 남긴다 —
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
	 * 원장 도입 이전에 쌓여 있던 잔액을 한 줄로 이월한 것. 계좌당 한 번만 들어온다.
	 * 발행하는 쪽에서는 잔액을 움직이지 않지만, 원장 입장에서는 다른 줄과 똑같이 더하고 뺀다.
	 */
	OPENING_BALANCE;

	/** 송금의 정상 흐름을 이루는 두 줄. 이 둘이 다 있어야 원장 기록이 끝난 것이다. */
	public boolean isTransferLeg() {
		return this == TRANSFER_DEBIT || this == TRANSFER_CREDIT;
	}
}
