package com.remittance.account.domain;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import com.remittance.account.support.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false)
	private UUID accountId;

	@Column(nullable = false, updatable = false)
	private UUID ownerId;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private AccountType accountType;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal balance;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountStatus status;

	@Version
	private Long version;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Builder
	public Account(UUID ownerId, String currency, AccountType accountType) {
		this.accountId = UUID.randomUUID();
		this.ownerId = ownerId;
		this.currency = currency;
		this.accountType = accountType;
		this.balance = BigDecimal.ZERO;
		this.status = AccountStatus.ACTIVE;
		this.createdAt = Timestamps.now();
		this.updatedAt = Timestamps.now();
	}

	public void debit(BigDecimal amount, String currency) {
		validateActiveAndCurrency(currency);
		if (this.balance.compareTo(amount) < 0) {
			throw new InsufficientBalanceException(this.accountId);
		}
		this.balance = this.balance.subtract(amount);
		this.updatedAt = Timestamps.now();
	}

	public void credit(BigDecimal amount, String currency) {
		validateActiveAndCurrency(currency);
		this.balance = this.balance.add(amount);
		this.updatedAt = Timestamps.now();
	}

	public void freeze() {
		this.status = AccountStatus.FROZEN;
		this.updatedAt = Timestamps.now();
	}

	public void close() {
		this.status = AccountStatus.CLOSED;
		this.updatedAt = Timestamps.now();
	}

	private void validateActiveAndCurrency(String currency) {
		if (this.status != AccountStatus.ACTIVE) {
			throw new AccountNotActiveException(this.accountId, this.status);
		}
		if (!this.currency.equals(currency)) {
			throw new CurrencyMismatchException(this.accountId, this.currency, currency);
		}
	}
}
