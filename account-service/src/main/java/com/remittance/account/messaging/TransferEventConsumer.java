package com.remittance.account.messaging;

import com.remittance.account.saga.TransferSagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 이벤트 수신부. 역직렬화 외에는 아무 판단도 하지 않고 {@link TransferSagaService}에 넘긴다 —
 * 메시징 기술과 도메인 처리를 섞지 않기 위해서다.
 *
 * <p>컨슈머 그룹은 서비스 단위로 하나다. 인스턴스를 늘리면 파티션이 나뉘어 자동으로 분산되고,
 * 같은 송금의 이벤트는 파티션 키(송금 ID) 덕분에 항상 같은 인스턴스가 순서대로 받는다.
 */
@Component
@RequiredArgsConstructor
public class TransferEventConsumer {

	private final TransferSagaService transferSagaService;
	private final ObjectMapper objectMapper;

	@KafkaListener(topics = TransferEvents.REQUESTED, groupId = "${spring.kafka.consumer.group-id}")
	public void onRequested(String payload) {
		transferSagaService.onRequested(objectMapper.readValue(payload, TransferEvents.Requested.class));
	}

	@KafkaListener(topics = TransferEvents.DEBITED, groupId = "${spring.kafka.consumer.group-id}")
	public void onDebited(String payload) {
		transferSagaService.onDebited(objectMapper.readValue(payload, TransferEvents.Debited.class));
	}
}
