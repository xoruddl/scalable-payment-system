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
import java.util.ArrayList;
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
 * <p><b>한 소식이 만드는 알림들은 ①과 ③을 함께 한다</b> (Phase 6, 2026-08-26).
 * 완료된 송금은 둘에게 알리는데, 전에는 각각 저장하고 각각 표시해 <b>커밋을 네 번</b> 썼다.
 * 커밋 하나가 평균 47.8ms짜리 공유 관문을 지나므로 그 값이 싸지 않다.
 * <b>단계를 합치는 게 아니라 같은 단계의 두 건을 묶는 것</b>이라 아래 보장은 그대로다.
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
		deliver(List.of(
				new NotificationDraft(event.transferId(), NotificationType.TRANSFER_SENT, event.fromAccountId(),
						"%s %s을(를) 보냈습니다.".formatted(format(event.amount()), event.currency())),
				new NotificationDraft(event.transferId(), NotificationType.TRANSFER_RECEIVED, event.toAccountId(),
						"%s %s이(가) 입금되었습니다.".formatted(format(event.amount()), event.currency()))));
	}

	/**
	 * 실패는 <b>보낸 사람에게만</b> 알린다. 받는 쪽은 애초에 오지 않은 돈이라 알릴 일이 없다 —
	 * 알리면 있지도 않았던 거래를 알려주는 꼴이 된다.
	 */
	public void onFailed(TransferEvents.TransferSettled event) {
		deliver(List.of(new NotificationDraft(event.transferId(), NotificationType.TRANSFER_FAILED,
				event.fromAccountId(),
				"%s %s 송금이 실패했습니다. (%s)".formatted(format(event.amount()), event.currency(),
						event.failureReason() == null ? "사유 미상" : event.failureReason()))));
	}

	private void deliver(List<NotificationDraft> drafts) {
		List<Notification> claimed = notificationRecorder.claimAll(drafts);

		List<Long> justSent = new ArrayList<>(claimed.size());
		try {
			for (Notification notification : claimed) {
				if (notification.isSent()) {
					log.debug("이미 보낸 알림이라 건너뛴다 (transferId={}, type={})",
							notification.getTransferId(), notification.getType());
					continue;
				}
				// 여기서 실패하면 예외가 그대로 올라가 오프셋이 커밋되지 않는다 → 재배달로 다시 시도된다.
				notificationSender.send(notification);
				justSent.add(notification.getId());
			}
		} finally {
			// <b>finally여야 한다.</b> 둘째 발송이 실패했다고 첫째까지 PENDING으로 남기면,
			// 재배달 때 이미 나간 알림이 한 번 더 나간다. 나간 것은 나갔다고 적고 나서 예외를 올린다.
			if (!justSent.isEmpty()) {
				notificationRecorder.markSentAll(justSent);
			}
		}
	}

	@Transactional(readOnly = true)
	public List<Notification> notificationsOf(UUID recipientAccountId) {
		return notificationRepository.findByRecipientAccountIdOrderByIdDesc(recipientAccountId);
	}

	private String format(BigDecimal amount) {
		return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
	}
}
