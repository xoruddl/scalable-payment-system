package com.remittance.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.remittance.transfer.exception.InvalidTransferRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false)
	private UUID transferId;

	@Column(nullable = false, updatable = false)
	private UUID fromAccountId;

	@Column(nullable = false, updatable = false)
	private UUID toAccountId;

	@Column(nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(length = 100, updatable = false)
	private String memo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TransferStatus status;

	private String failureReason;

	@Column(nullable = false, updatable = false)
	private Instant requestedAt;

	private Instant completedAt;

	/** 금액 컬럼의 scale. 저장 전에 이 표현으로 통일한다. */
	private static final int AMOUNT_SCALE = 2;

	@Builder
	public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency, String memo) {
		this.transferId = UUID.randomUUID();
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.amount = normalizeAmount(amount);
		this.currency = currency;
		this.memo = memo;
		this.status = TransferStatus.PENDING;
		this.requestedAt = Instant.now();
	}

	/**
	 * 3000과 3000.00은 같은 금액이지만 BigDecimal로는 표현이 다르다. 정규화해두지 않으면
	 * 최초 응답(요청받은 표현)과 멱등 재요청 응답(DB를 거친 표현)이 달라져 멱등성 계약이 깨진다.
	 * 컬럼이 담을 수 없는 정밀도는 DB가 조용히 잘라내기 전에 여기서 거절한다.
	 */
	private static BigDecimal normalizeAmount(BigDecimal amount) {
		try {
			return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException e) {
			throw new InvalidTransferRequestException(
					"금액은 소수점 " + AMOUNT_SCALE + "자리까지만 지원합니다: " + amount.toPlainString());
		}
	}

	public void markDebitCompleted() {
		this.status = TransferStatus.DEBIT_COMPLETED;
	}

	public void markCreditCompleted() {
		this.status = TransferStatus.CREDIT_COMPLETED;
	}

	public void markCompensating() {
		this.status = TransferStatus.COMPENSATING;
	}

	public void markCompleted() {
		this.status = TransferStatus.COMPLETED;
		this.completedAt = Instant.now();
	}

	public void markFailed(String reason) {
		this.status = TransferStatus.FAILED;
		this.failureReason = reason;
		this.completedAt = Instant.now();
	}
}
