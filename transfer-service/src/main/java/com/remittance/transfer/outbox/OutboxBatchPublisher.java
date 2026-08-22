package com.remittance.transfer.outbox;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Outbox 한 배치를 발행한다 (Phase 6 Step 2).
 *
 * <h2>왜 {@link OutboxRelay}에서 분리했나</h2>
 * 적체가 있을 때 다음 폴링을 기다리지 않고 이어서 비우려면 <b>배치 하나가 트랜잭션 하나</b>여야 한다.
 * 그런데 같은 빈 안에서 자기 메서드를 부르면 {@code @Transactional} 프록시를 타지 않는다 —
 * 이 저장소가 이미 두 번 밟은 함정이다(`BalanceMutationExecutor`, `NotificationRecorder`).
 * 그래서 처음부터 다른 빈으로 갈라 둔다.
 *
 * <h2>건마다 기다리지 않는다</h2>
 * 예전에는 이벤트 하나를 보내고 {@code join()}으로 응답을 기다린 뒤 다음 것을 보냈다.
 * 100건이면 <b>브로커와 100번 왕복</b>한다. 2026-08-22 baseline에서 종결 처리량이
 * 초당 19건에 묶여 있던 원인이 여기다.
 *
 * <p>지금은 <b>전부 보내고 마지막에 한 번 기다린다.</b> 프로듀서가 알아서 묶어 보내므로
 * 왕복이 사실상 한 번으로 줄어든다.
 *
 * <h2>순서는 어떻게 지키나</h2>
 * 애그리거트 ID를 키로 쓰므로 같은 송금의 이벤트는 <b>같은 파티션</b>으로 간다.
 * 프로듀서가 {@code acks=all} + {@code enable.idempotence=true}라, 재시도가 일어나도
 * 파티션 안에서의 순서가 뒤바뀌지 않는다 (어긋나면 브로커가 거절한다).
 *
 * <p>그래도 남는 창이 있다 — 앞의 것이 끝내 실패하면 <b>뒤의 것은 이미 보내진 뒤</b>다.
 * 그때는 실패 지점부터 마킹하지 않고 다음 폴링에서 다시 보낸다. 즉 <b>중복은 생길 수 있고
 * 순서는 어긋날 수 있다.</b> 둘 다 이 시스템이 이미 견디도록 만들어져 있다 —
 * 소비하는 쪽이 멱등하고(§6), 송금 상태 전이가 단조(monotonic)라 순서가 뒤바뀌어도 갇히지 않는다(§4-④).
 */
@Component
@RequiredArgsConstructor
public class OutboxBatchPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxBatchPublisher.class);

	private final OutboxEventRepository outboxEventRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;

	/**
	 * @return 실제로 발행하고 마킹한 건수. {@code batchSize}보다 적으면 <b>더 비울 게 없거나
	 *         중간에 실패한 것</b>이므로, 부르는 쪽은 이번 주기를 여기서 끝내면 된다.
	 */
	@Transactional
	public int publishBatch(int batchSize) {
		List<OutboxEvent> pending =
				outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(Limit.of(batchSize));
		if (pending.isEmpty()) {
			return 0;
		}

		// ① 전부 보낸다. 여기서는 기다리지 않는다.
		List<CompletableFuture<SendResult<String, String>>> sent = new ArrayList<>(pending.size());
		for (OutboxEvent event : pending) {
			sent.add(kafkaTemplate.send(
					event.getEventType(), event.getAggregateId().toString(), event.getPayload()));
		}

		// ② 보낸 순서대로 결과를 확인한다. 첫 실패에서 멈추고 그 뒤는 마킹하지 않는다.
		int published = 0;
		for (int i = 0; i < pending.size(); i++) {
			OutboxEvent event = pending.get(i);
			try {
				sent.get(i).join();
			} catch (Exception e) {
				log.warn("Outbox 이벤트 발행 실패 - 여기서부터 다음 폴링에 재시도 (id={}, type={}, 남은={})",
						event.getId(), event.getEventType(), pending.size() - i, e);
				break;
			}
			event.markPublished();
			published++;
		}
		return published;
	}
}
