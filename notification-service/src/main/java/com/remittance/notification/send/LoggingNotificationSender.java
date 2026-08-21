package com.remittance.notification.send;

import com.remittance.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 발송 시뮬레이션. 실제 푸시·SMS 연동은 이 프로젝트의 관심사가 아니다 —
 * 여기서 연습하려는 건 <b>이벤트를 두 번 받아도 알림은 한 번만 나가게 하는 것</b>이다.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

	@Override
	public void send(Notification notification) {
		log.info("[알림 발송] to={} type={} transferId={} : {}",
				notification.getRecipientAccountId(), notification.getType(),
				notification.getTransferId(), notification.getMessage());
	}
}
