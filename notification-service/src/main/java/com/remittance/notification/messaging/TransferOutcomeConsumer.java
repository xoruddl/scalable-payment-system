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
 */
@Component
@RequiredArgsConstructor
public class TransferOutcomeConsumer {

	private final NotificationService notificationService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.COMPLETED, topics = TransferEvents.COMPLETED, groupId = "${spring.kafka.consumer.group-id}")
	public void onCompleted(String payload) {
		notificationService.onCompleted(parse(payload));
	}

	@KafkaListener(id = TransferEvents.FAILED, topics = TransferEvents.FAILED, groupId = "${spring.kafka.consumer.group-id}")
	public void onFailed(String payload) {
		notificationService.onFailed(parse(payload));
	}

	private TransferEvents.TransferSettled parse(String payload) {
		return objectMapper.readValue(payload, TransferEvents.TransferSettled.class);
	}
}
