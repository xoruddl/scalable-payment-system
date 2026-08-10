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

import java.math.BigDecimal;
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

	@Builder
	public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency, String memo) {
		this.transferId = UUID.randomUUID();
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.amount = amount;
		this.currency = currency;
		this.memo = memo;
		this.status = TransferStatus.PENDING;
		this.requestedAt = Instant.now();
	}
}
