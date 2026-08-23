package com.remittance.notification.messaging;

import com.remittance.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 송금이 <b>끝났다는 소식만</b> 듣고 알림으로 바꾼다.
 *
 * <p>중간 단계를 듣지 않는 이유는 알림의 성격 때문이다. 사용자에게 필요한 건 결과 하나지,
 * "출금됐습니다 → 입금됐습니다 → 기록됐습니다"가 아니다.
 *
 * <p>이 서비스는 Saga에 <b>끼어들지 않는다.</b> 아무 이벤트도 발행하지 않으므로, 여기가 통째로
 * 죽어도 송금은 정상적으로 완료된다. 알림이 송금을 막을 수 있다면 그건 잘못 붙인 것이다.
 *
 * <h2>스레드를 파티션 수에 맞춘다 (Phase 6 Step 2)</h2>
 * 2026-08-23 측정에서 <b>종결 경로가 전부 lag 0으로 비워진 뒤에도 여기만 밀렸다.</b>
 * 도착이 초당 79건인데 처리가 18건/s라 적체가 20,000건을 넘었고, 그 속도면 비우는 데
 * 20분이 걸린다. <b>"송금은 끝났는데 알림이 한참 뒤에 오는"</b> 상태다.
 *
 * <p>자원이 모자란 게 아니었다 — 그때 MySQL CPU는 8.7%, 이 서비스는 3.3%였다.
 * 스레드 하나가 메시지당 커밋 네 번(수신자 2명 × 자리잡기 + 보냄표시)을 <b>순차로</b>
 * 기다린 결과다. 커밋 한 번이 약 14ms라 메시지 하나에 55ms를 쓴다.
 *
 * <p>같은 송금의 소식은 키가 같아 같은 파티션으로 가므로, 스레드를 늘려도
 * <b>한 송금의 알림 순서는 그대로다.</b> 병렬이 되는 것은 서로 다른 송금끼리다.
 */
@Component
@RequiredArgsConstructor
public class TransferOutcomeConsumer {

	/**
	 * 리스너당 스레드 수. 파티션 수(3)에 맞춘 값이 기본이고, 파티션을 넘겨 봐야 놀기만 한다.
	 * 기본값이 코드에 있어야 테스트가 같은 값으로 돈다 — 테스트용 yml이 운영 yml을 가리기 때문이다.
	 */
	private static final String CONCURRENCY = "${remittance.kafka.listener.concurrency:3}";

	private final NotificationService notificationService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.COMPLETED, topics = TransferEvents.COMPLETED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onCompleted(String payload) {
		notificationService.onCompleted(parse(payload));
	}

	@KafkaListener(id = TransferEvents.FAILED, topics = TransferEvents.FAILED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onFailed(String payload) {
		notificationService.onFailed(parse(payload));
	}

	private TransferEvents.TransferSettled parse(String payload) {
		return objectMapper.readValue(payload, TransferEvents.TransferSettled.class);
	}
}
