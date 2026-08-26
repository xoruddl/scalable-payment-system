package com.remittance.account.domain;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.CurrencyMismatchException;
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

	/**
	 * 이 계좌의 잔액을 몇 조각으로 쪼갰나 (Phase 6 Step 1). 1이면 안 쪼갠 것이다.
	 *
	 * <p><b>잔액 자체는 여기 없다.</b> {@link AccountBalanceShard}에 있고, 계좌의 잔액은
	 * 그 조각들의 합이다. 한 행에 두면 그 계좌의 입금이 전부 그 행에 줄을 서기 때문이다.
	 *
	 * <p>기본이 1인 이유 — 계좌 대부분은 경합이 없다. 경합 없는 계좌를 쪼개면
	 * 조회할 때마다 합산만 늘어 손해다. <b>쪼개는 것은 붐비는 계좌에만 하는 처방</b>이다.
	 */
	@Column(nullable = false)
	private short shardCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountStatus status;

	@Version
	private Long version;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	/**
	 * 개시 잔액을 원장에 이월한 시각. {@code null}이면 아직 이월하지 않았다는 뜻이다.
	 *
	 * <p>이월은 <b>계좌당 한 번뿐</b>이어야 한다 — 두 번 심으면 그만큼 원장이 잔액보다 커져,
	 * 맞추려던 대사를 오히려 어긋나게 만든다. 그 한 번을 표시하는 자리다.
	 */
	@Column
	private Instant openingBalanceCarriedAt;

	@Builder
	public Account(UUID ownerId, String currency, AccountType accountType) {
		this.accountId = UUID.randomUUID();
		this.ownerId = ownerId;
		this.currency = currency;
		this.accountType = accountType;
		this.shardCount = 1;
		this.status = AccountStatus.ACTIVE;
		this.createdAt = Timestamps.now();
		this.updatedAt = Timestamps.now();
	}

	/**
	 * 이 계좌로 돈을 움직여도 되는가. 잔액이 조각으로 나가면서 <b>계좌에 남은 규칙은 이것뿐</b>이다.
	 * 잔액이 모자란지는 조각들의 합을 봐야 알 수 있어 {@link AccountBalance}가 판단한다.
	 */
	public void assertUsable(String currency) {
		validateActiveAndCurrency(currency);
	}

	/** 쪼갤 조각 수를 바꾼다. 줄이는 것은 남는 조각의 돈을 옮겨야 해서 아직 지원하지 않는다. */
	public void widenShards(short shardCount) {
		if (shardCount < this.shardCount) {
			throw new IllegalArgumentException(
					"조각은 줄일 수 없다 (지금 %d → %d). 남는 조각의 돈을 옮기는 절차가 없다."
							.formatted(this.shardCount, shardCount));
		}
		this.shardCount = shardCount;
		this.updatedAt = Timestamps.now();
	}

	/**
	 * 개시 잔액을 이월했다고 표시한다. <b>잔액은 건드리지 않는다</b> —
	 * 이월은 없던 돈을 만드는 게 아니라, 이미 있던 잔액을 원장에도 적어두는 일이다.
	 */
	public void markOpeningBalanceCarried() {
		this.openingBalanceCarriedAt = Timestamps.now();
		this.updatedAt = Timestamps.now();
	}

	public boolean isOpeningBalanceCarried() {
		return this.openingBalanceCarriedAt != null;
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
