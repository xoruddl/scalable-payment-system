package com.remittance.account.messaging;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다음 이벤트를 만드는 팩토리가 필드를 빠짐없이, 제자리에 옮기는가.
 *
 * Saga 흐름 코드가 생성자를 직접 부르던 때는 필드 복사가 눈에 보였다. 팩토리로 옮기면
 * 흐름 코드에서 보이지 않으므로 여기서 고정한다. 계좌 ID는 전부 UUID라 자리가 바뀌어도
 * 컴파일러가 못 잡는다. 발생 시각은 만들 때마다 달라서 비교에서 뺀다.
 */
class TransferEventsTest {

	private final UUID transferId = UUID.randomUUID();
	private final UUID from = UUID.randomUUID();
	private final UUID to = UUID.randomUUID();
	private final BigDecimal amount = new BigDecimal("1000.00");
	private final BigDecimal fromBalanceAfter = new BigDecimal("4000.00");

	private TransferEvents.Requested internalRequest() {
		return TransferEvents.Requested.internal(transferId, from, to, amount, "KRW");
	}

	private TransferEvents.Debited internalDebited() {
		return internalRequest().debited(fromBalanceAfter);
	}

	/**
	 * 출금 단계는 상대 은행 자리를 쓰지 않지만 반드시 실어 보내야 한다.
	 * 빠지면 입금 단계가 외부 송금을 내부 송금으로 착각한다 (Phase 6.5).
	 */
	@Test
	void 출금_완료는_상대_은행_자리까지_그대로_실어_나른다() {
		TransferEvents.Requested external = new TransferEvents.Requested(
				transferId, from, null, "088", "110-123-456789", amount, "KRW");

		assertThat(external.debited(fromBalanceAfter))
				.usingRecursiveComparison().ignoringFields("occurredAt")
				.isEqualTo(new TransferEvents.Debited(transferId, from, null, "088", "110-123-456789",
						amount, "KRW", fromBalanceAfter, null));
	}

	@Test
	void 출금_실패는_송금_정보와_사유를_싣는다() {
		assertThat(internalRequest().debitFailed("잔액이 부족합니다"))
				.usingRecursiveComparison().ignoringFields("occurredAt")
				.isEqualTo(new TransferEvents.DebitFailed(transferId, from, to,
						amount, "KRW", "잔액이 부족합니다", null));
	}

	/** 외부 송금이면 입금된 계좌는 받는 사람이 아니라 정산 계좌다. 이벤트의 toAccountId를 쓰면 안 된다. */
	@Test
	void 입금_완료에는_실제로_입금된_계좌와_양쪽_잔액이_실린다() {
		UUID settlement = UUID.randomUUID();
		BigDecimal toBalanceAfter = new BigDecimal("2000.00");

		assertThat(internalDebited().credited(settlement, toBalanceAfter))
				.usingRecursiveComparison().ignoringFields("occurredAt")
				.isEqualTo(new TransferEvents.Credited(transferId, from, settlement,
						amount, "KRW", fromBalanceAfter, toBalanceAfter, null));
	}

	/** 되돌릴 계좌와 금액이 본문에 있어야 보상하는 쪽이 DB에 되묻지 않는다. */
	@Test
	void 입금_실패는_되돌릴_계좌와_금액을_싣는다() {
		assertThat(internalDebited().creditFailed(to, "통화가 일치하지 않습니다"))
				.usingRecursiveComparison().ignoringFields("occurredAt")
				.isEqualTo(new TransferEvents.CreditFailed(transferId, from, to,
						amount, "KRW", "통화가 일치하지 않습니다", null));
	}

	/** 송금을 종결하는 쪽이 왜 실패했는지 알아야 하므로 입금 실패 사유를 이어 싣는다. */
	@Test
	void 환불_완료는_입금_실패_사유를_이어_싣는다() {
		TransferEvents.CreditFailed creditFailed = internalDebited().creditFailed(to, "통화가 일치하지 않습니다");
		BigDecimal refunded = new BigDecimal("5000.00");

		assertThat(creditFailed.debitReversed(refunded))
				.usingRecursiveComparison().ignoringFields("occurredAt")
				.isEqualTo(new TransferEvents.DebitReversed(transferId, from, amount, "KRW",
						refunded, "통화가 일치하지 않습니다", null));
	}
}
