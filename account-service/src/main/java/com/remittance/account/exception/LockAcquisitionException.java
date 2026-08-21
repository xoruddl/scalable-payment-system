package com.remittance.account.exception;

import java.time.Duration;

/**
 * 제한 시간 안에 분산 락을 잡지 못한 경우.
 * 같은 계좌에 요청이 몰려 대기가 길어졌다는 뜻이므로, 클라이언트는 재시도할 수 있다.
 */
public class LockAcquisitionException extends RuntimeException {

	public LockAcquisitionException(String lockKey, Duration waitTimeout) {
		super("락 획득에 실패했습니다 (key=" + lockKey + ", 대기 " + waitTimeout.toMillis() + "ms 초과)");
	}
}
