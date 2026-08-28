package com.remittance.account.external;

import java.util.UUID;

/**
 * 상대 은행에 보냈는데 <b>답이 없다.</b> 들어갔는지 안 들어갔는지 알 수 없다.
 *
 * <h2>5xx와 다르다 ★</h2>
 * 5xx는 상대가 <b>"안 했다"고 말해준</b> 것이라 그대로 다시 보내면 된다.
 * 답이 없는 것은 <b>아무 말도 못 들은</b> 것이다 — 이미 처리됐을 수 있다.
 *
 * <p>그래서 여기서 재시도하면 안 된다. 상대가 멱등하다고 믿고 다시 보낼 수도 있지만,
 * 그건 <b>남의 약속에 한 번 더 기대는 것</b>이고 답이 계속 없으면 영영 알 수 없다.
 * <b>맞는 수단은 조회다</b> — 물어보면 그쪽 장부에 무엇이 적혔는지 알 수 있다.
 */
public class ExternalCreditUnknownException extends RuntimeException {

	private final transient UUID transferId;
	private final transient String bankCode;

	public ExternalCreditUnknownException(String bankCode, UUID transferId, Throwable cause) {
		super("상대 은행이 답하지 않았다 - 처리됐는지 알 수 없다 (bank=%s, transferId=%s)"
				.formatted(bankCode, transferId), cause);
		this.bankCode = bankCode;
		this.transferId = transferId;
	}

	public UUID getTransferId() {
		return transferId;
	}

	public String getBankCode() {
		return bankCode;
	}
}
