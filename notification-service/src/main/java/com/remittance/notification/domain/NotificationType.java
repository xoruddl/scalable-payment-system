package com.remittance.notification.domain;

/** 무엇을 알리는가. */
public enum NotificationType {

	/** 보낸 돈이 도착했다 (보내는 쪽에게) */
	TRANSFER_SENT,
	/** 돈이 들어왔다 (받는 쪽에게) */
	TRANSFER_RECEIVED,
	/** 송금이 실패했다 (보내는 쪽에게만) */
	TRANSFER_FAILED
}
