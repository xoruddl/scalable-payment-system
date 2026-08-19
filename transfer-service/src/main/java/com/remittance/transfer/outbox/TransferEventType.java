package com.remittance.transfer.outbox;

/**
 * 송금 도메인 이벤트. 값이 그대로 Kafka 토픽명이 된다.
 * Step 4에서 Choreography Saga로 전환하면 단계별 이벤트가 여기에 추가된다.
 */
public enum TransferEventType {

	REQUESTED("transfer.requested"),
	COMPLETED("transfer.completed"),
	FAILED("transfer.failed");

	private final String topic;

	TransferEventType(String topic) {
		this.topic = topic;
	}

	public String topic() {
		return topic;
	}
}
