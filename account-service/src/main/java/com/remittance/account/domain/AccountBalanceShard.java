package com.remittance.account.domain;

import com.remittance.account.support.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 한 계좌 잔액의 <b>조각</b>. 계좌의 진짜 잔액은 이 조각들의 합이다.
 *
 * <p>왜 쪼개나 — 잔액이 계좌 행 하나에 있으면 <b>그 계좌의 모든 입금이 그 행 하나에 줄을 선다.</b>
 * 2026-08-23 측정에서 락 보유 p50이 38ms였고, 그건 <b>한 계좌 초당 26건</b>이라는 뜻이다.
 * 서버를 늘려도 안 변하는 숫자다. 조각이 N개면 서로 다른 행을 만지므로 그만큼 나란히 간다.
 *
 * <p><b>{@link Version}이 계좌가 아니라 여기 붙어 있는 것</b>이 쪼개는 의미의 절반이다.
 * 계좌에 붙어 있으면 어느 조각을 만졌든 같은 행의 버전을 올려 결국 다시 충돌한다.
 *
 * <p>대부분의 계좌는 조각이 하나다. 경합이 없는 계좌를 쪼개면 조회할 때마다 합산만 늘어 손해다.
 */
@Entity
@Table(name = "account_balance_shards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountBalanceShard {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private UUID accountId;

	/** 0번부터. {@code accounts.shard_count}가 이 계좌에 몇 개가 있는지를 말한다. */
	@Column(nullable = false, updatable = false)
	private short shardNo;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal balance;

	@Version
	private Long version;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public AccountBalanceShard(UUID accountId, short shardNo, BigDecimal balance) {
		this.accountId = accountId;
		this.shardNo = shardNo;
		this.balance = balance;
		this.createdAt = Timestamps.now();
		this.updatedAt = Timestamps.now();
	}

	void add(BigDecimal amount) {
		this.balance = this.balance.add(amount);
		this.updatedAt = Timestamps.now();
	}

	/**
	 * 이 조각에서 뺀다. <b>조각 하나가 마이너스가 되면 안 된다</b> — 합이 맞아도 개별 조각이
	 * 음수면 "이 조각에서 뽑을 수 있는 돈"이라는 전제가 무너져, 동시에 도는 다른 출금이
	 * 있지도 않은 돈을 본다.
	 */
	void subtract(BigDecimal amount) {
		if (this.balance.compareTo(amount) < 0) {
			throw new IllegalArgumentException(
					"조각에 있는 것보다 많이 뺄 수 없다 (shardNo=%d, balance=%s, amount=%s)"
							.formatted(shardNo, balance, amount));
		}
		this.balance = this.balance.subtract(amount);
		this.updatedAt = Timestamps.now();
	}
}
