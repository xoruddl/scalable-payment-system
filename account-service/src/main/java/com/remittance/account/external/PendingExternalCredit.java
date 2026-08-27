package com.remittance.account.external;

import com.remittance.account.support.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 상대 은행에 보냈는데 <b>답을 못 받은</b> 입금 한 건.
 *
 * <p>이 행이 있다는 것은 <b>돈이 나갔을 수도 있다</b>는 뜻이다. 우리 고객 계좌는 이미 줄었는데
 * 상대가 받았는지 모른다 — 이 시스템에서 가장 불편한 상태이고, 그래서 <b>반드시 기록으로 남긴다.</b>
 * 메모리에만 두면 재기동 한 번에 사라지고, 그러면 아무도 모르는 채로 끝난다.
 *
 * <p>해소는 <b>조회</b>로만 한다. 다시 보내는 것은 답이 아니다 —
 * 이미 처리됐을 수 있으므로 확인이 먼저다.
 */
@Entity
@Table(name = "pending_external_credits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingExternalCredit {

	/** 송금 ID가 곧 상대 은행에 준 멱등성 키다. 그래서 이게 기본키다. */
	@Id
	@Column(nullable = false, updatable = false)
	private UUID transferId;

	@Column(nullable = false, length = 11, updatable = false)
	private String bankCode;

	@Column(nullable = false, length = 34, updatable = false)
	private String toAccountNumber;

	@Column(nullable = false, updatable = false)
	private UUID fromAccountId;

	@Column(nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(nullable = false, updatable = false)
	private BigDecimal fromBalanceAfter;

	/**
	 * <b>상대에게 보내기는 했나.</b>
	 *
	 * <p>둘은 전혀 다른 상태다.
	 * <ul>
	 *   <li>{@code true} — 보냈는데 답이 없다. <b>돈이 나갔을 수 있다.</b> 조회로 확인한다</li>
	 *   <li>{@code false} — 격벽에 막혀 <b>보내지도 못했다.</b> 돈은 안 나갔다. 그냥 보내면 된다</li>
	 * </ul>
	 *
	 * <p>섞으면 안 된다. 안 보낸 건을 "모른다"고 알리면 <b>없는 사고를 보고하는 것</b>이고,
	 * 보낸 건을 "안 보냈다"고 보면 <b>이중 지급</b>이 된다.
	 */
	@Column(nullable = false)
	private boolean sent;

	/** 몇 번 물어봤나. 오래 안 풀리는 건을 가려내는 데 쓴다. */
	@Column(nullable = false)
	private int inquiries;

	/** 이 시각이 지나야 다시 묻는다. 답 없는 상대를 초당 두드려봐야 소용없다. */
	@Column(nullable = false)
	private Instant nextInquiryAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	public PendingExternalCredit(UUID transferId, String bankCode, String toAccountNumber,
			UUID fromAccountId, BigDecimal amount, String currency, BigDecimal fromBalanceAfter,
			boolean sent) {
		this.transferId = transferId;
		this.bankCode = bankCode;
		this.toAccountNumber = toAccountNumber;
		this.fromAccountId = fromAccountId;
		this.amount = amount;
		this.currency = currency;
		this.fromBalanceAfter = fromBalanceAfter;
		this.sent = sent;
		this.inquiries = 0;
		this.createdAt = Timestamps.now();
		this.nextInquiryAt = this.createdAt;
	}

	/**
	 * 또 답을 못 받았다. 다음에 물어볼 시각을 뒤로 민다.
	 *
	 * <p>간격을 <b>지수적으로</b> 늘리되 상한을 둔다. 상대가 오래 아플 수 있으므로 계속 두드리면
	 * 그쪽 회복을 방해하고, 그렇다고 무한정 미루면 <b>풀렸는데도 한참 모른 채로</b> 남는다.
	 */
	public void backOff(Duration base, Duration max) {
		this.inquiries++;
		long millis = Math.min(base.toMillis() << Math.min(inquiries, 10), max.toMillis());
		this.nextInquiryAt = Timestamps.now().plusMillis(millis);
	}

	/** 보냈다고 표시한다. 이제부터는 조회로만 결론을 낸다. */
	public void markSent() {
		this.sent = true;
	}

	/** 만들어진 뒤 이만큼 지나도 안 풀렸는가. 사람이 봐야 하는 건을 가린다. */
	public boolean isStuckFor(Duration threshold) {
		return createdAt.isBefore(Timestamps.now().minus(threshold));
	}
}
