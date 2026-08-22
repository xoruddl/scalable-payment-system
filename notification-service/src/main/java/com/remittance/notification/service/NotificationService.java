package com.remittance.notification.service;

import com.remittance.notification.domain.Notification;
import com.remittance.notification.domain.NotificationType;
import com.remittance.notification.messaging.TransferEvents;
import com.remittance.notification.repository.NotificationRepository;
import com.remittance.notification.send.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 종결된 송금 한 건을 알림으로 바꿔 내보낸다.
 *
 * <h2>여기서 지키려는 것 하나 — 같은 소식으로 두 번 알리지 않는다</h2>
 * 이벤트는 at-least-once라 같은 소식이 두 번 온다. 그런데 <b>알림은 되돌릴 수 없다.</b>
 * "10만원을 보냈습니다"가 두 번 가면 사용자는 두 번 빠져나간 줄 안다.
 *
 * <h2>순서 — 자리를 먼저 잡고, 보내고, 그다음에 보냈다고 적는다</h2>
 * <pre>
 *   ① 자리 잡기(PENDING 저장)  ─▶  ② 발송  ─▶  ③ SENT로 표시
 * </pre>
 *
 * <p>이 순서여야 어디서 죽든 안전하다.
 * <ul>
 *   <li>①과 ② 사이에 죽으면 → 재배달 때 PENDING을 보고 <b>다시 보낸다.</b></li>
 *   <li>②와 ③ 사이에 죽으면 → 재배달 때도 PENDING이라 <b>한 번 더 나간다.</b>
 *       발송과 기록을 원자적으로 묶을 수 없는 이상 남는 창이고, 이쪽을 택한 것이다 —
 *       <b>드물게 두 번 가는 것</b>이 <b>영영 안 가는 것</b>보다 낫다고 봤다.</li>
 * </ul>
 *
 * <p>반대로 ①에서 곧바로 SENT로 적으면 ①~② 사이에 죽었을 때 재배달이 "이미 보냈다"로 읽고
 * 건너뛴다. <b>알림이 조용히 사라지고 아무도 모른다.</b> 그래서 상태가 두 개 필요하다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationRepository notificationRepository;
	private final NotificationRecorder notificationRecorder;
	private final NotificationSender notificationSender;

	/** 완료된 송금은 <b>두 사람</b>에게 알린다 — 보낸 사람과 받은 사람은 서로 다른 소식을 받는다. */
	public void onCompleted(TransferEvents.TransferSettled event) {
		deliver(event.transferId(), NotificationType.TRANSFER_SENT, event.fromAccountId(),
				"%s %s을(를) 보냈습니다.".formatted(format(event.amount()), event.currency()));
		deliver(event.transferId(), NotificationType.TRANSFER_RECEIVED, event.toAccountId(),
				"%s %s이(가) 입금되었습니다.".formatted(format(event.amount()), event.currency()));
	}

	/**
	 * 실패는 <b>보낸 사람에게만</b> 알린다. 받는 쪽은 애초에 오지 않은 돈이라 알릴 일이 없다 —
	 * 알리면 있지도 않았던 거래를 알려주는 꼴이 된다.
	 */
	public void onFailed(TransferEvents.TransferSettled event) {
		deliver(event.transferId(), NotificationType.TRANSFER_FAILED, event.fromAccountId(),
				"%s %s 송금이 실패했습니다. (%s)".formatted(format(event.amount()), event.currency(),
						event.failureReason() == null ? "사유 미상" : event.failureReason()));
	}

	private void deliver(UUID transferId, NotificationType type, UUID recipientAccountId, String message) {
		Notification notification = notificationRecorder.claim(transferId, type, recipientAccountId, message);
		if (notification.isSent()) {
			log.debug("이미 보낸 알림이라 건너뛴다 (transferId={}, type={})", transferId, type);
			return;
		}

		// 여기서 실패하면 예외가 그대로 올라가 오프셋이 커밋되지 않는다 → 재배달로 다시 시도된다.
		notificationSender.send(notification);
		notificationRecorder.markSent(notification.getId());
	}

	@Transactional(readOnly = true)
	public List<Notification> notificationsOf(UUID recipientAccountId) {
		return notificationRepository.findByRecipientAccountIdOrderByIdDesc(recipientAccountId);
	}

	private String format(BigDecimal amount) {
		return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
	}
}
