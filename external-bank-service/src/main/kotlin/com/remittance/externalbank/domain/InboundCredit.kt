package com.remittance.externalbank.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 이 은행이 받은 입금 한 건. */
@Entity
@Table(name = "inbound_credits")
class InboundCredit(

	/**
	 * **송금 ID가 곧 멱등성 키다.** 보내는 쪽이 정하고, 우리는 그걸 기본키로 쓴다.
	 *
	 * 이게 이 서비스의 계약에서 가장 중요한 부분이다. 보내는 쪽은 타임아웃이 나면
	 * **같은 ID로 다시 보내거나 조회할** 수밖에 없는데, 그때 두 번 들어가지 않는다는 보장을
	 * **우리가** 해야 한다. 보내는 쪽은 우리 DB에 제약을 걸 수 없다.
	 */
	@Id
	@Column(name = "transfer_id", nullable = false, updatable = false)
	val transferId: UUID,

	@Column(nullable = false, precision = 19, scale = 2, updatable = false)
	val amount: BigDecimal,

	@Column(nullable = false, length = 3, updatable = false)
	val currency: String,

	@Column(name = "account_number", nullable = false, length = 34, updatable = false)
	val accountNumber: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	val outcome: CreditOutcome,

	@Column(name = "reject_reason", length = 200, updatable = false)
	val rejectReason: String? = null,

	@Column(name = "received_at", nullable = false, updatable = false)
	val receivedAt: Instant = Instant.now(),
)

/**
 * 이 은행이 내린 **최종 판정**. 한 번 정해지면 바뀌지 않는다 —
 * 그래서 보내는 쪽이 나중에 조회해도 같은 답을 받는다.
 */
enum class CreditOutcome {
	/** 입금됨. */
	ACCEPTED,

	/** 업무적 거절 (없는 계좌 등). **다시 보내도 결과가 같다.** */
	REJECTED,
}
