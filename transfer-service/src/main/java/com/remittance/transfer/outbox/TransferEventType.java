package com.remittance.transfer.outbox;

/**
 * 이 서비스가 <b>발행하는</b> 송금 도메인 이벤트. 값이 그대로 Kafka 토픽명이 된다.
 *
 * <p>접수와 종결, 세 개뿐이다. 중간 단계 이벤트(debited/credited/…)는 그 일을 실제로 한
 * 서비스가 발행한다 — 하지 않은 일을 대신 알리는 서비스가 있으면 계약이 흐려진다.
 * 소비하는 쪽은 {@link com.remittance.transfer.messaging.TransferEvents}에 있다.
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
