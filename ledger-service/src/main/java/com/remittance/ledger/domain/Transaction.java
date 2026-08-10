package com.remittance.ledger.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 원장(ledger)에 기록되는 개별 거래 항목. 하나의 Transfer는 출금(DEBIT)/입금(CREDIT)
 * 두 건의 Transaction으로 원장에 남는다.
 */
@Document(collection = "transactions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

	@Id
	private String id;

	@Indexed(unique = true)
	private UUID transactionId;

	@Indexed
	private UUID transferId;

	@Indexed
	private UUID accountId;

	private TransactionDirection direction;

	private BigDecimal amount;

	private BigDecimal balanceAfter;

	private Instant recordedAt;
}
