package com.remittance.account.external;

/** 상대 은행의 답. {@code reason}은 거절일 때만 채워진다. */
public record ExternalCreditResult(ExternalCreditStatus status, String reason) {

	public boolean isAccepted() {
		return status == ExternalCreditStatus.ACCEPTED;
	}
}
