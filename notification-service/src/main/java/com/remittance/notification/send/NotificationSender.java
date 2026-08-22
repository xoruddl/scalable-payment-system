package com.remittance.notification.send;

import com.remittance.notification.domain.Notification;

/**
 * 알림을 실제로 내보내는 통로. 지금은 로그로 흉내만 낸다({@link LoggingNotificationSender}).
 *
 * <p>인터페이스로 갈라둔 이유는 <b>발송이 실패할 수 있다는 사실을 흐리지 않기 위해서</b>다.
 * 푸시든 SMS든 바깥으로 나가는 호출이라 느리고 실패한다. 그 실패가 예외로 올라와야
 * 컨슈머가 오프셋을 커밋하지 않고, 그래야 재배달로 다시 시도된다.
 */
public interface NotificationSender {

	void send(Notification notification);
}
