package com.remittance.transfer.outbox;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 송금 상태 변경과 이벤트 기록을 <b>하나의 트랜잭션</b>으로 묶는다.
 * Outbox 패턴의 핵심은 이 원자성이므로, 두 저장이 반드시 같은 트랜잭션 경계 안에 있어야 한다.
 *
 * <p>주의: 호출부(TransferService)가 이 메서드를 <b>다른 빈을 통해</b> 호출해야 프록시가 적용된다.
 * 같은 클래스 안의 self-invocation이면 @Transactional이 걸리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferOutboxRecorder {

	private static final String AGGREGATE_TYPE = "Transfer";

	private final TransferRepository transferRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	@Transactional
	public Transfer record(Transfer transfer, TransferEventType eventType) {
		Transfer saved = transferRepository.save(transfer);
		outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType(AGGREGATE_TYPE)
				.aggregateId(saved.getTransferId())
				.eventType(eventType.topic())
				.payload(objectMapper.writeValueAsString(TransferEventPayload.from(saved)))
				.build());
		return saved;
	}
}
