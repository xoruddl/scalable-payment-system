package com.remittance.transfer.domain;

/**
 * 송금의 상태.
 *
 * <p>정상 흐름은 한 줄로 진행한다. 각 상태에 <b>진행도</b>를 매겨두는 이유는,
 * Saga 이벤트가 <b>단계마다 다른 토픽</b>으로 오기 때문이다. 토픽이 다르면 파티션 키가 같아도
 * 도착 순서가 보장되지 않는다.
 *
 * <pre>
 *   PENDING(0) ─▶ DEBIT_COMPLETED(1) ─▶ CREDIT_COMPLETED(2) ─▶ COMPLETED(3)
 * </pre>
 *
 * <p>그래서 상태 전이를 "기대한 직전 단계일 때만"으로 좁히면 안 된다. 뒤 단계가 먼저 도착했을 때
 * 그 이벤트를 버리게 되는데, <b>버린 이벤트는 다시 오지 않아 송금이 영원히 멈춘다</b>
 * (Step 4d의 e2e에서 실제로 겪었다). 대신 <b>진행도가 앞서면 건너뛰어서라도 적용</b>한다.
 * 뒤 단계 이벤트가 왔다는 건 앞 단계가 이미 끝났다는 뜻이므로, 건너뛰어도 사실과 어긋나지 않는다.
 *
 * <p>실패 계열은 이 줄 위에 있지 않아 진행도가 없다.
 * {@link #COMPENSATING}은 되돌리는 중이라 <b>더 진행해서는 안 되고</b>,
 * 종결 상태({@link #COMPLETED}·{@link #FAILED})는 무슨 이벤트가 와도 바뀌지 않는다 —
 * 그게 재전송에 대한 멱등성이 된다.
 */
public enum TransferStatus {

	PENDING(0),
	DEBIT_COMPLETED(1),
	CREDIT_COMPLETED(2),
	COMPLETED(3),
	/** 진행도 -1 = 정상 흐름 위에 있지 않다는 뜻. 어떤 진행도와 비교해도 앞서지 않는다. */
	COMPENSATING(-1),
	FAILED(-1),

	/**
	 * 상대 은행에 보냈는데 <b>답이 없다</b> — 들어갔는지 모른다 (Phase 6.5).
	 *
	 * <p><b>실패가 아니다.</b> 실패로 처리하면 이미 나간 돈을 환불해 이중 지급이 되고,
	 * 성공으로 처리하면 안 간 돈을 갔다고 하는 셈이다. 어느 쪽으로도 밀 수 없어서
	 * <b>제3의 상태</b>가 필요하다.
	 *
	 * <p>진행도는 {@code -1}이다 — 정상 흐름 위에 있지 않아서다. 여기서 빠져나가는 길은
	 * <b>조회로 확인하는 것</b>뿐이고, 확인되면 {@code CREDIT_COMPLETED}나 {@code COMPENSATING}으로
	 * 간다. 그 전이는 늘 앞선 진행도를 갖는 이벤트로 오므로 {@link #isAheadOf}가 그대로 통과시킨다.
	 */
	CREDIT_UNKNOWN(-1);

	private final int progress;

	TransferStatus(int progress) {
		this.progress = progress;
	}

	/** 이 상태로 가는 것이 {@code current}에서 볼 때 앞으로 나아가는 것인가. */
	public boolean isAheadOf(TransferStatus current) {
		return this.progress > current.progress;
	}

	/** 한 번 닫힌 송금은 뒤늦은 이벤트로 다시 열리지 않는다. */
	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}

	/** 되돌리는 중이면 정상 흐름을 더 진행시켜서는 안 된다. */
	public boolean isCompensating() {
		return this == COMPENSATING;
	}
}
