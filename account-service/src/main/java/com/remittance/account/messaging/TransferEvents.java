package com.remittance.account.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.remittance.account.support.Timestamps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 송금 Saga에서 오가는 이벤트의 토픽명과 본문 스키마.
 *
 * 서비스 간 공유 모듈을 두지 않기로 했으므로, 같은 계약을 transfer/ledger 서비스도
 * 각자 정의한다. 필드 이름이 곧 계약이니 바꿀 때는 세 곳을 함께 확인해야 한다.
 *
 * 이벤트는 다음 단계가 필요로 하는 값을 모두 실어 보낸다(잔액 등).
 * 그래야 소비하는 쪽이 되묻기 위해 동기 호출을 하지 않는다 — 그러면 이벤트로 바꾼 의미가 없다.
 *
 * 모든 본문에 {@code @JsonIgnoreProperties(ignoreUnknown = true)}를 붙였다.
 * 발행하는 쪽이 필드를 추가해도 소비하는 쪽이 깨지지 않아야, 서비스를 따로 배포할 수 있다.
 * (실제로 {@code transfer.requested}에는 이 서비스가 쓰지 않는 필드가 여럿 들어 있다.)
 *
 * 정상 흐름과 보상 흐름은 이렇게 갈린다 (Step 4b).
 *   requested ─▶ 출금 성공 ─▶ debited ─▶ 입금 성공 ─▶ credited          (정상)
 *   requested ─▶ 출금 실패 ─▶ debit-failed                              (움직인 돈 없음, 종결)
 *   debited   ─▶ 입금 실패 ─▶ credit-failed ─▶ 환불 ─▶ debit-reversed   (보상 후 종결)
 * 송금을 최종적으로 FAILED로 찍고 {@code transfer.failed}를 발행하는 건 Transfer Service다.
 * 이 서비스는 계좌에 무슨 일이 있었는지만 알린다 — 송금의 상태는 송금의 주인이 정한다.
 *
 * 다음 이벤트는 받은 이벤트에게 만들게 한다 (2026-09-13)
 * {@code requested.debited(잔액)}처럼 받은 본문이 다음 본문을 만든다. 전에는 Saga 흐름 코드가
 * 생성자에 필드를 하나씩 옮겨 적어서, 무엇을 하는지가 무엇을 복사하는지에 묻혔다.
 * 이 메서드들은 인자가 있어 직렬화되지 않으므로 계약(필드)은 그대로다.
 */
public final class TransferEvents {

	/** Transfer가 발행: 송금이 접수됨 → Account가 출금한다 */
	public static final String REQUESTED = "transfer.requested";
	/** Account가 발행: 출금 완료 → Account가 이어서 입금한다 */
	public static final String DEBITED = "transfer.debited";
	/** Account가 발행: 입금 완료 → Ledger가 원장에 기록한다 */
	public static final String CREDITED = "transfer.credited";

	/** Account가 발행: 출금 자체가 실패 → 되돌릴 게 없으므로 송금만 실패로 종결하면 된다 */
	public static final String DEBIT_FAILED = "transfer.debit-failed";
	/**
	 * Account가 발행하고 Account가 다시 소비: 입금이 실패 → 이미 나간 출금을 되돌려야 한다.
	 *
	 * 같은 서비스 안에서 처리할 수 있는데도 굳이 이벤트로 한 바퀴 도는 이유는 재시도 때문이다.
	 * 요청 스레드에서 곧바로 환불하면 그 환불이 실패했을 때 아무도 다시 해주지 않는다
	 * (Step 0 재현 테스트 #4가 바로 그 문제였다). 브로커에 남겨두면 실패해도 다시 배달된다.
	 */
	public static final String CREDIT_FAILED = "transfer.credit-failed";

	/**
	 * 상대 은행에 보냈는데 답이 없다 — 들어갔는지 모른다 (Phase 6.5).
	 *
	 * 실패가 아니다. 실패로 처리하면 이미 나간 돈을 환불해 이중 지급이 되고,
	 * 성공으로 처리하면 안 간 돈을 갔다고 하는 셈이다. 그래서 제3의 상태가 필요하다.
	 */
	public static final String CREDIT_UNKNOWN = "transfer.credit-unknown";
	/** Account가 발행: 출금을 되돌렸음 → Transfer가 송금을 FAILED로 종결한다 */
	public static final String DEBIT_REVERSED = "transfer.debit-reversed";

	private TransferEvents() {
	}

	/** {@link #REQUESTED} 본문. Transfer Service가 만든 payload와 필드가 맞아야 한다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Requested(
			UUID transferId,
			UUID fromAccountId,
			/** 우리 은행 계좌일 때만. 상대 은행으로 나가면 {@code null}이다. */
			UUID toAccountId,
			/** 상대 은행 코드. 값이 있으면 입금 단계가 외부 호출로 갈린다 (Phase 6.5). */
			String toBankCode,
			String toAccountNumber,
			BigDecimal amount,
			String currency
	) {
		public boolean isExternal() {
			return toBankCode != null;
		}

		/** 우리 은행 안의 송금. 상대 은행 자리를 매번 {@code null}로 적지 않기 위해 둔다. */
		public static Requested internal(UUID transferId, UUID fromAccountId, UUID toAccountId,
				BigDecimal amount, String currency) {
			return new Requested(transferId, fromAccountId, toAccountId, null, null, amount, currency);
		}

		/**
		 * 출금이 끝났다. 출금 단계는 상대 은행 자리를 쓰지 않지만 그대로 옮겨 싣는다 —
		 * 여기서 빠지면 입금 단계가 어디로 보낼지 모른다 (Phase 6.5).
		 */
		public Debited debited(BigDecimal fromBalanceAfter) {
			return new Debited(transferId, fromAccountId, toAccountId, toBankCode, toAccountNumber,
					amount, currency, fromBalanceAfter, Timestamps.now());
		}

		/** 출금하지 못했다. 움직인 돈이 없으므로 되돌릴 것 없이 송금이 종결된다. */
		public DebitFailed debitFailed(String failureReason) {
			return new DebitFailed(transferId, fromAccountId, toAccountId,
					amount, currency, failureReason, Timestamps.now());
		}
	}

	/** {@link #DEBITED} 본문. 출금 후 잔액을 함께 실어 다음 단계가 그대로 쓸 수 있게 한다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Debited(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			/** 여기까지 실어 날라야 입금 단계가 어디로 보낼지 안다. */
			String toBankCode,
			String toAccountNumber,
			BigDecimal amount,
			String currency,
			BigDecimal fromBalanceAfter,
			Instant occurredAt
	) {
		public boolean isExternal() {
			return toBankCode != null;
		}

		/** 우리 은행 안의 송금. */
		public static Debited internal(UUID transferId, UUID fromAccountId, UUID toAccountId,
				BigDecimal amount, String currency, BigDecimal fromBalanceAfter, Instant occurredAt) {
			return new Debited(transferId, fromAccountId, toAccountId, null, null,
					amount, currency, fromBalanceAfter, occurredAt);
		}

		/**
		 * 입금이 끝났다.
		 *
		 * @param creditAccountId 실제로 입금된 계좌. 외부 송금이면 받는 사람이 아니라 정산 계좌다 —
		 *                        원장이 두 다리를 맞추는 기준이므로 실제로 입금된 계좌여야 한다.
		 */
		public Credited credited(UUID creditAccountId, BigDecimal toBalanceAfter) {
			return new Credited(transferId, fromAccountId, creditAccountId,
					amount, currency, fromBalanceAfter, toBalanceAfter, Timestamps.now());
		}

		/**
		 * 입금하지 못했다. 출금은 이미 나갔으므로 이 이벤트가 환불을 부른다.
		 *
		 * @param creditAccountId 입금하려던 계좌. 상대 은행이 거절했으면 우리 쪽 계좌가 없어
		 *                        이 이벤트의 {@code toAccountId}를 그대로 넘긴다.
		 */
		public CreditFailed creditFailed(UUID creditAccountId, String failureReason) {
			return new CreditFailed(transferId, fromAccountId, creditAccountId,
					amount, currency, failureReason, Timestamps.now());
		}
	}

	/** {@link #CREDITED} 본문. 원장 기록에 필요한 양쪽 잔액이 모두 담긴다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Credited(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			BigDecimal fromBalanceAfter,
			BigDecimal toBalanceAfter,
			Instant occurredAt
	) {
	}

	/**
	 * {@link #CREDIT_UNKNOWN} 본문.
	 *
	 * 금액을 함께 싣는 이유는 이 상태가 사람이 봐야 하는 것이기 때문이다.
	 * "얼마가 어디로 갔는지 모른다"까지 한 줄에 있어야 알림이 쓸모가 있다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreditUnknown(
			UUID transferId,
			UUID fromAccountId,
			String toBankCode,
			String toAccountNumber,
			BigDecimal amount,
			String currency,
			Instant occurredAt
	) {
	}

	/** {@link #DEBIT_FAILED} 본문. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DebitFailed(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			String failureReason,
			Instant occurredAt
	) {
	}

	/**
	 * {@link #CREDIT_FAILED} 본문.
	 *
	 * 되돌릴 금액과 계좌를 본문에 담는다. 보상하는 쪽이 "얼마를 누구에게 돌려줘야 하는지"를
	 * DB에 되물으면, 그 사이 상태가 바뀌었을 때 엉뚱한 금액을 되돌릴 수 있다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreditFailed(
			UUID transferId,
			UUID fromAccountId,
			UUID toAccountId,
			BigDecimal amount,
			String currency,
			String failureReason,
			Instant occurredAt
	) {
		/** 출금을 되돌렸다. 송금을 종결하는 쪽이 왜 실패했는지 알아야 하므로 사유를 이어 싣는다. */
		public DebitReversed debitReversed(BigDecimal fromBalanceAfter) {
			return new DebitReversed(transferId, fromAccountId, amount, currency,
					fromBalanceAfter, failureReason, Timestamps.now());
		}
	}

	/** {@link #DEBIT_REVERSED} 본문. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DebitReversed(
			UUID transferId,
			UUID fromAccountId,
			BigDecimal amount,
			String currency,
			BigDecimal fromBalanceAfter,
			String failureReason,
			Instant occurredAt
	) {
	}
}
