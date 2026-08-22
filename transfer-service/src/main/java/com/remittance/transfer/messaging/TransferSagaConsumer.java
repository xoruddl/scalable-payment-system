package com.remittance.transfer.messaging;

import com.remittance.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga가 진행되는 동안 흘러나오는 이벤트를 받아 송금 상태를 따라 올린다.
 *
 * <p>Transfer Service는 더 이상 흐름을 <b>지시</b>하지 않는다. 요청을 접수해
 * {@code transfer.requested}를 던지고 나면, 그 뒤로는 각 단계가 알려주는 걸 듣고
 * 상태만 기록하는 <b>관전자</b>에 가깝다. 대신 "지금 이 송금이 어디까지 갔는지"를
 * 조회할 수 있는 유일한 창구가 된다.
 */
@Component
@RequiredArgsConstructor
public class TransferSagaConsumer {

	private final TransferService transferService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.DEBITED, topics = TransferEvents.DEBITED, groupId = "${spring.kafka.consumer.group-id}")
	public void onDebited(String payload) {
		transferService.applyDebited(objectMapper.readValue(payload, TransferEvents.Debited.class));
	}

	@KafkaListener(id = TransferEvents.CREDITED, topics = TransferEvents.CREDITED, groupId = "${spring.kafka.consumer.group-id}")
	public void onCredited(String payload) {
		transferService.applyCredited(objectMapper.readValue(payload, TransferEvents.Credited.class));
	}

	@KafkaListener(id = TransferEvents.LEDGER_RECORDED, topics = TransferEvents.LEDGER_RECORDED, groupId = "${spring.kafka.consumer.group-id}")
	public void onLedgerRecorded(String payload) {
		transferService.applyLedgerRecorded(objectMapper.readValue(payload, TransferEvents.LedgerRecorded.class));
	}

	@KafkaListener(id = TransferEvents.DEBIT_FAILED, topics = TransferEvents.DEBIT_FAILED, groupId = "${spring.kafka.consumer.group-id}")
	public void onDebitFailed(String payload) {
		transferService.applyDebitFailed(objectMapper.readValue(payload, TransferEvents.StepFailed.class));
	}

	@KafkaListener(id = TransferEvents.CREDIT_FAILED, topics = TransferEvents.CREDIT_FAILED, groupId = "${spring.kafka.consumer.group-id}")
	public void onCreditFailed(String payload) {
		transferService.applyCreditFailed(objectMapper.readValue(payload, TransferEvents.StepFailed.class));
	}

	@KafkaListener(id = TransferEvents.DEBIT_REVERSED, topics = TransferEvents.DEBIT_REVERSED, groupId = "${spring.kafka.consumer.group-id}")
	public void onDebitReversed(String payload) {
		transferService.applyDebitReversed(objectMapper.readValue(payload, TransferEvents.StepFailed.class));
	}
}
