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
 *
 * <h2>스레드를 파티션 수에 맞춘다 (Phase 6 Step 2)</h2>
 * 2026-08-23 측정에서 <b>앞단이 전부 놀고 있는데</b>(account·ledger lag 0) 이 서비스만
 * 세 토픽을 혼자 비우고 있었다. 리스너마다 <b>스레드가 1개</b>였기 때문이다.
 * 종결 신호인 {@code transfer.ledger-recorded}가 초당 41건이었고 실측 종결 처리량이
 * 30건/s로, <b>막힌 구간과 전체 숫자가 같은 자릿수</b>였다.
 *
 * <h2>같은 송금이 동시에 갱신되지는 않나</h2>
 * 스레드 하나가 파티션 하나를 맡고, 같은 송금은 키가 같아 같은 파티션으로 간다.
 * 그래서 <b>한 리스너 안에서는</b> 여전히 한 스레드가 순서대로 처리한다.
 *
 * <p>다만 <b>리스너끼리는</b> 원래부터 별개 스레드였다 — {@code credited}와
 * {@code ledger-recorded}가 같은 송금 행을 동시에 건드리는 일은 스레드가 1개일 때도
 * 있었다. 즉 이 변경으로 <b>새로운 종류의 경합이 생기지는 않는다.</b>
 * 그건 낙관적 락(`@Version`)과 단조 상태 전이(§4-④)가 이미 막고 있다.
 */
@Component
@RequiredArgsConstructor
public class TransferSagaConsumer {

	/**
	 * 리스너당 스레드 수. 파티션 수(3)에 맞춘 값이 기본이고, 파티션을 넘겨 봐야 놀기만 한다.
	 * 기본값이 코드에 있어야 테스트가 같은 값으로 돈다 — 테스트용 yml이 운영 yml을 가리기 때문이다.
	 */
	private static final String CONCURRENCY = "${remittance.kafka.listener.concurrency:3}";

	private final TransferService transferService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.DEBITED, topics = TransferEvents.DEBITED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onDebited(String payload) {
		transferService.applyDebited(objectMapper.readValue(payload, TransferEvents.Debited.class));
	}

	@KafkaListener(id = TransferEvents.CREDITED, topics = TransferEvents.CREDITED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onCredited(String payload) {
		transferService.applyCredited(objectMapper.readValue(payload, TransferEvents.Credited.class));
	}

	@KafkaListener(id = TransferEvents.LEDGER_RECORDED, topics = TransferEvents.LEDGER_RECORDED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onLedgerRecorded(String payload) {
		transferService.applyLedgerRecorded(objectMapper.readValue(payload, TransferEvents.LedgerRecorded.class));
	}

	@KafkaListener(id = TransferEvents.DEBIT_FAILED, topics = TransferEvents.DEBIT_FAILED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onDebitFailed(String payload) {
		transferService.applyDebitFailed(objectMapper.readValue(payload, TransferEvents.StepFailed.class));
	}

	@KafkaListener(id = TransferEvents.CREDIT_FAILED, topics = TransferEvents.CREDIT_FAILED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onCreditFailed(String payload) {
		transferService.applyCreditFailed(objectMapper.readValue(payload, TransferEvents.StepFailed.class));
	}

	@KafkaListener(id = TransferEvents.DEBIT_REVERSED, topics = TransferEvents.DEBIT_REVERSED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onDebitReversed(String payload) {
		transferService.applyDebitReversed(objectMapper.readValue(payload, TransferEvents.StepFailed.class));
	}
}
