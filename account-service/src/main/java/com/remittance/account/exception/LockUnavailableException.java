package com.remittance.account.exception;

/**
 * 분산 락 저장소(Redis)에 닿지 못해 락을 잡지 못했다 (Phase 6.7, D-006).
 *
 * {@link LockAcquisitionException}과 다르다. 그쪽은 Redis가 답했는데 남이 쥐고 있었던 것(붐빔)이고,
 * 이쪽은 Redis가 답하지 않은 것(저장소가 없음)이다.
 *
 * 락을 잡는 단계에서만 던진다 ★
 * 그래서 이 예외가 올라왔다면 보호하려던 작업은 아직 한 번도 실행되지 않았다.
 * {@code BalanceGuard}가 이 예외를 받고 행 락만으로 대신 진행해도 작업이 두 번 돌지 않는 근거가 이것이다.
 */
public class LockUnavailableException extends RuntimeException {

	/** 회로가 열려 있어 Redis를 부르지도 않았다. */
	public LockUnavailableException(String lockKey) {
		super("분산 락 회로가 열려 있어 Redis를 부르지 않았다 (key=" + lockKey + ")");
	}

	public LockUnavailableException(String lockKey, Throwable cause) {
		super("분산 락 저장소(Redis)에 닿지 못했다 (key=" + lockKey + ")", cause);
	}
}
