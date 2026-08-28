package com.remittance.account.web.dto;

import com.remittance.account.external.PendingExternalCredit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 상대 은행에 <b>보냈는데 결과를 모르는</b> 입금 한 건 — 대사가 읽어가는 모양.
 *
 * @param inquiries 몇 번 물어봤나. <b>이 값이 큰데 아직 여기 있다면</b> 상대가 계속 답을 못 주는
 *                  것이라, 사람이 그쪽에 연락해야 하는 건이다.
 */
public record UnknownExternalCreditView(
		UUID transferId,
		String bankCode,
		BigDecimal amount,
		String currency,
		int inquiries,
		Instant createdAt
) {

	public static UnknownExternalCreditView from(PendingExternalCredit credit) {
		return new UnknownExternalCreditView(credit.getTransferId(), credit.getBankCode(),
				credit.getAmount(), credit.getCurrency(), credit.getInquiries(),
				credit.getCreatedAt());
	}
}
