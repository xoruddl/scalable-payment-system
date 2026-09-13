package com.remittance.account.external;

import com.remittance.account.messaging.TransferEvents;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 상대 은행에 보낼 입금 요청 한 건.
 *
 * 처음 보내는 곳(Saga 입금 단계)과 미뤘다 보내거나 다시 보내는 곳(확인 루프)이
 * 같은 다섯 값을 각자 풀어서 넘기고 있었다. 한 덩어리이므로 묶는다.
 */
public record ExternalCreditRequest(
		String bankCode,
		UUID transferId,
		String accountNumber,
		BigDecimal amount,
		String currency
) {

	/** Saga 입금 단계가 처음 보낼 때. */
	public static ExternalCreditRequest of(TransferEvents.Debited event) {
		return new ExternalCreditRequest(event.toBankCode(), event.transferId(), event.toAccountNumber(),
				event.amount(), event.currency());
	}

	/** 확인 루프가 미뤄둔 건을 보내거나, 도달하지 않았다고 확인된 건을 다시 보낼 때. */
	public static ExternalCreditRequest of(PendingExternalCredit credit) {
		return new ExternalCreditRequest(credit.getBankCode(), credit.getTransferId(),
				credit.getToAccountNumber(), credit.getAmount(), credit.getCurrency());
	}
}
