package com.remittance.transfer.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 받는 쪽을 적는 방법이 <b>둘</b>이고, 정확히 하나만 써야 한다 (Phase 6.5).
 *
 * <pre>
 *   우리 은행 계좌   toAccountId
 *   상대 은행 계좌   toBankCode + toAccountNumber
 * </pre>
 *
 * <p>상대 은행 계좌에는 UUID가 없다. <b>우리가 발급한 적이 없기 때문이다.</b>
 * 그쪽 계좌번호는 그쪽 규칙을 따르는 문자열이라 우리는 그대로 전달만 한다.
 */
public record CreateTransferRequest(
		@NotNull UUID fromAccountId,
		UUID toAccountId,
		@Size(max = 11) String toBankCode,
		@Size(max = 34) String toAccountNumber,
		@NotNull @DecimalMin(value = "0.01") BigDecimal amount,
		@NotNull String currency,
		@Size(max = 100) String memo
) {

	/** 우리 은행 안의 송금. 상대 은행 자리를 매번 {@code null}로 적지 않기 위해 둔다. */
	public static CreateTransferRequest internal(UUID fromAccountId, UUID toAccountId,
			BigDecimal amount, String currency, String memo) {
		return new CreateTransferRequest(fromAccountId, toAccountId, null, null, amount, currency, memo);
	}

	/** 상대 은행으로 나가는 송금. */
	public static CreateTransferRequest external(UUID fromAccountId, String toBankCode,
			String toAccountNumber, BigDecimal amount, String currency, String memo) {
		return new CreateTransferRequest(fromAccountId, null, toBankCode, toAccountNumber,
				amount, currency, memo);
	}

	/** 상대 은행으로 나가는 송금인가. */
	public boolean isExternal() {
		return toBankCode != null && !toBankCode.isBlank();
	}

	/**
	 * 받는 쪽이 <b>정확히 한 가지 방법으로</b> 적혔는가.
	 *
	 * <p>둘 다 적히면 어느 쪽이 진짜인지 알 수 없고, 둘 다 없으면 보낼 곳이 없다.
	 * 어느 쪽이든 <b>돈이 엉뚱한 데로 갈 수 있는</b> 상태라 받아주면 안 된다.
	 */
	public boolean hasExactlyOneDestination() {
		if (isExternal()) {
			return toAccountId == null && toAccountNumber != null && !toAccountNumber.isBlank();
		}
		return toAccountId != null && toAccountNumber == null;
	}
}
