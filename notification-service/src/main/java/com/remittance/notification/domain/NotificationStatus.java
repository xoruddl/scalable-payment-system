package com.remittance.notification.domain;

/**
 * 알림 한 건의 진행 상태.
 *
 * <p>이 두 값이 있어야 <b>"보내야 하는데 아직 못 보낸 것"과 "이미 보낸 것"</b>이 갈린다.
 * 자리를 잡자마자 SENT로 적어버리면, 발송 도중 죽었을 때 재배달이 와도 "이미 보냈다"로 읽고
 * 건너뛰어 <b>알림이 조용히 사라진다.</b>
 */
public enum NotificationStatus {

	/** 자리는 잡았고 아직 못 보냈다. 재배달이 오면 다시 보내야 한다. */
	PENDING,
	/** 보냈다. 재배달이 와도 다시 보내지 않는다. */
	SENT
}
