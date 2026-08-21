package com.remittance.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferTest {

	private Transfer newTransfer() {
		return Transfer.builder()
				.fromAccountId(UUID.randomUUID())
				.toAccountId(UUID.randomUUID())
				.amount(BigDecimal.valueOf(1000))
				.currency("KRW")
				.build();
	}

	@Test
	void 생성시_PENDING() {
		Transfer transfer = newTransfer();

		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
	}

	@Test
	void 정상흐름_상태전이() {
		Transfer transfer = newTransfer();

		transfer.advanceTo(TransferStatus.DEBIT_COMPLETED);
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.DEBIT_COMPLETED);

		transfer.advanceTo(TransferStatus.CREDIT_COMPLETED);
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CREDIT_COMPLETED);

		transfer.advanceTo(TransferStatus.COMPLETED);
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transfer.getCompletedAt())
				.as("완료 시각은 COMPLETED로 갈 때만 찍힌다")
				.isNotNull();
	}

	@Test
	void 보상_실패흐름() {
		Transfer transfer = newTransfer();

		transfer.advanceTo(TransferStatus.DEBIT_COMPLETED);
		transfer.markCompensating();
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATING);

		transfer.markFailed("입금 실패, 출금 보상 완료");
		assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
		assertThat(transfer.getFailureReason()).isEqualTo("입금 실패, 출금 보상 완료");
		assertThat(transfer.getCompletedAt()).isNotNull();
	}
}
