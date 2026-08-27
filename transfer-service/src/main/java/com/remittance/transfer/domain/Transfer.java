package com.remittance.transfer.domain;

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

import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.support.Timestamps;

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

	/** 우리 은행 계좌일 때만 채워진다. 상대 은행으로 나가면 {@code null}이다. */
	@Column(updatable = false)
	private UUID toAccountId;

	/**
	 * 상대 은행 코드. {@code null}이면 우리 은행 안의 송금이다 (Phase 6.5).
	 * 값이 있으면 {@link #toAccountId}는 비어 있고 {@link #toAccountNumber}가 받는 쪽이다.
	 */
	@Column(length = 11, updatable = false)
	private String toBankCode;

	/** 상대 은행의 계좌번호. <b>우리가 발급한 적이 없어 UUID가 아니다.</b> */
	@Column(length = 34, updatable = false)
	private String toAccountNumber;

	@Column(nullable = false, precision = 19, scale = 2, updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(length = 100, updatable = false)
	private String memo;

	/**
	 * 이 송금을 만들어낸 Idempotency-Key.
	 *
	 * <p>Step 6b 전에는 <b>키에서 송금으로 가는 길만</b> 있었고, 그마저 접수가 다 끝난 뒤에야
	 * 채워졌다. 그래서 접수 도중 죽어 {@code IN_PROGRESS}로 남은 키를 보면
	 * <b>"접수가 커밋되기 전에 죽은 것"과 "커밋됐는데 키에 적기 직전에 죽은 것"을 구분할 수 없었다.</b>
	 * 앞의 것은 풀어줘도 되고 뒤의 것은 풀면 두 번째 송금이 생긴다 — 구분이 안 되니 아무것도 못 했다.
	 *
	 * <p>반대 방향을 여기에 남겨 그 구분이 가능해진다. 송금 저장과 같은 트랜잭션에 들어가므로,
	 * <b>송금이 있으면 키도 반드시 여기 적혀 있다.</b>
	 *
	 * <p>unique 제약은 그 위의 안전망이다. 키 판정이 어떤 이유로든 뚫려도
	 * <b>같은 키로 두 번째 송금이 저장되는 것 자체가 DB에서 막힌다.</b>
	 *
	 * <p>Step 6b 이전에 만들어진 송금에는 비어 있다. MySQL의 unique 인덱스는 NULL을 여럿 허용한다.
	 */
	@Column(length = 36, unique = true, updatable = false)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TransferStatus status;

	/**
	 * Saga 단계마다 토픽이 다르고, 토픽마다 리스너 스레드가 다르다. 여러 리스너가 같은 송금 행을
	 * 동시에 읽고 쓸 수 있으므로 <b>마지막에 커밋한 쪽이 이기는 것을 막아야 한다.</b>
	 *
	 * <p>이게 없던 Step 4c까지는 실제로 덮였다 — {@code markFailed()}가 실행되어
	 * {@code transfer.failed}까지 발행된 송금이, 뒤늦게 커밋된 출금 이벤트에 밀려
	 * {@code DEBIT_COMPLETED}로 남았다. 바깥에는 실패라고 알려놓고 자기 기록은 진행 중인 상태다.
	 *
	 * <p>충돌하면 {@code TransferService}가 다시 읽어 전이 조건을 <b>처음부터 다시 판단</b>한다.
	 * 그래야 그 사이 바뀐 상태를 반영한 결정을 내린다.
	 */
	@Version
	private long version;

	private String failureReason;

	@Column(nullable = false, updatable = false)
	private Instant requestedAt;

	private Instant completedAt;

	/** 금액 컬럼의 scale. 저장 전에 이 표현으로 통일한다. */
	private static final int AMOUNT_SCALE = 2;

	@Builder
	public Transfer(UUID fromAccountId, UUID toAccountId, String toBankCode, String toAccountNumber,
			BigDecimal amount, String currency, String memo, String idempotencyKey) {
		this.transferId = UUID.randomUUID();
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.toBankCode = toBankCode;
		this.toAccountNumber = toAccountNumber;
		this.amount = normalizeAmount(amount);
		this.currency = currency;
		this.memo = memo;
		this.idempotencyKey = idempotencyKey;
		this.status = TransferStatus.PENDING;
		this.requestedAt = Timestamps.now();
	}

	/** 상대 은행으로 나가는 송금인가. 받는 쪽을 어떻게 처리할지가 여기서 갈린다. */
	public boolean isExternal() {
		return toBankCode != null;
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

	/**
	 * 정상 흐름을 한 단계 이상 진행시킨다.
	 *
	 * <p>단계를 하나씩 올리는 메서드를 따로 두지 않는 이유는, 이벤트가 단계마다 다른 토픽으로 와서
	 * <b>순서가 뒤바뀔 수 있기</b> 때문이다. 뒤 단계가 먼저 도착하면 건너뛰어서라도 그 단계로 간다.
	 * 진행해도 되는지는 {@code TransferStateUpdater}가 판단한다.
	 */
	public void advanceTo(TransferStatus target) {
		this.status = target;
		if (target == TransferStatus.COMPLETED) {
			this.completedAt = Timestamps.now();
		}
	}

	public void markCompensating() {
		this.status = TransferStatus.COMPENSATING;
	}

	public void markFailed(String reason) {
		this.status = TransferStatus.FAILED;
		this.failureReason = reason;
		this.completedAt = Timestamps.now();
	}
}
