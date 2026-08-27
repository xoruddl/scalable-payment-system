package com.remittance.account.exception;

/**
 * 주소를 모르는 은행으로 보내려 했다.
 *
 * <p>조용히 넘어가면 안 된다. 돈이 어디로 갔는지 아무도 모르게 되므로,
 * <b>보내기 전에</b> 멈춰야 한다. 다시 시도해도 설정이 바뀌기 전에는 결과가 같다.
 */
public class UnknownBankException extends RuntimeException {

	public UnknownBankException(String bankCode, String knownBanks) {
		super("주소를 모르는 은행이다: %s (아는 은행: %s)".formatted(bankCode, knownBanks));
	}
}
