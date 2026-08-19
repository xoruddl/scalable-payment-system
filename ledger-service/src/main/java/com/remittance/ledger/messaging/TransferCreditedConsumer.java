package com.remittance.ledger.messaging;

import com.remittance.ledger.domain.TransactionDirection;
import com.remittance.ledger.service.TransactionService;
import com.remittance.ledger.web.dto.RecordTransactionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 입금까지 끝난 송금을 원장에 남기고, 끝났음을 알린다.
 * 하나의 송금은 출금(DEBIT)/입금(CREDIT) 두 줄로 기록된다.
 *
 * <p><b>여기엔 Outbox를 두지 않았다.</b> Outbox는 "DB 커밋"과 "이벤트 발행"을 원자적으로 묶기 위한
 * 장치인데, 이 서비스는 그 둘이 어긋나도 스스로 복구된다.
 * <ul>
 *   <li>기록은 성공했는데 발행이 실패하면 → 오프셋이 커밋되지 않아 이벤트가 재전송되고,
 *       원장 기록이 멱등하므로 다시 기록해도 그대로다. 그리고 다시 발행한다.</li>
 *   <li>기록이 실패하면 → 발행 자체를 하지 않는다.</li>
 * </ul>
 * 즉 <b>멱등한 쓰기 + 발행 후 ack</b> 조합이면 같은 보장을 얻는다.
 * (게다가 MongoDB는 단일 노드에서 다중 문서 트랜잭션을 쓸 수 없어, 있어도 원자적이지 않다.)
 *
 * <p>{@code block()}을 쓰는 이유: Kafka 리스너는 전용 스레드에서 돌기 때문에 막아도 이벤트 루프를
 * 굶기지 않는다. 오히려 여기서 <b>끝날 때까지 기다려야</b> 실패 시 오프셋이 커밋되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferCreditedConsumer {

	private final TransactionService transactionService;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;

	@KafkaListener(topics = TransferEvents.CREDITED, groupId = "${spring.kafka.consumer.group-id}")
	public void onCredited(String payload) {
		TransferEvents.Credited event = objectMapper.readValue(payload, TransferEvents.Credited.class);

		transactionService.recordTransactions(List.of(
				new RecordTransactionRequest(event.transferId(), event.fromAccountId(),
						TransactionDirection.DEBIT, event.amount(), event.fromBalanceAfter()),
				new RecordTransactionRequest(event.transferId(), event.toAccountId(),
						TransactionDirection.CREDIT, event.amount(), event.toBalanceAfter())
		), event.occurredAt()).block();

		TransferEvents.LedgerRecorded recorded =
				new TransferEvents.LedgerRecorded(event.transferId(), event.occurredAt());
		kafkaTemplate.send(TransferEvents.LEDGER_RECORDED, event.transferId().toString(),
				objectMapper.writeValueAsString(recorded)).join();
	}
}
