package com.remittance.notification.service;

import com.remittance.notification.domain.Notification;
import com.remittance.notification.domain.NotificationType;
import com.remittance.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 알림의 <b>기록</b>만 맡는다 — 자리를 잡고, 보냈다고 표시한다. 발송은 하지 않는다.
 *
 * <p>{@link NotificationService}에서 떼어낸 이유는 {@code @Transactional} 때문이다.
 * <b>같은 빈 안에서 부르면 프록시를 타지 않아 트랜잭션이 걸리지 않는다.</b> 그러면
 * {@code markSent()}의 변경이 더티 체킹으로 반영되지 않아 <b>상태가 영원히 PENDING으로 남고,
 * 재배달이 올 때마다 알림이 다시 나간다.</b> 실제로 그렇게 만들어서 테스트가 잡아냈다.
 *
 * <p>(account-service의 {@code BalanceMutationExecutor}와 같은 이유로 갈라놓은 구조다.)
 */
@Component
@RequiredArgsConstructor
public class NotificationRecorder {

	private final NotificationRepository notificationRepository;

	/**
	 * 이 소식에 대한 자리를 잡는다. 이미 있으면 그걸 그대로 쓴다.
	 *
	 * <p>같은 송금의 이벤트는 송금 ID를 키로 한 파티션에 모이고, 한 파티션은 그룹 안에서 컨슈머
	 * 하나만 읽는다. 그래서 여기까지 동시에 들어오는 일은 없다 — 그럼에도 unique 제약을 둔 이유는,
	 * <b>그 전제가 깨져도 중복 알림만은 막히게</b> 하기 위해서다.
	 */
	@Transactional
	public Notification claim(UUID transferId, NotificationType type, UUID recipientAccountId, String message) {
		return notificationRepository
				.findByTransferIdAndTypeAndRecipientAccountId(transferId, type, recipientAccountId)
				.orElseGet(() -> notificationRepository.saveAndFlush(Notification.builder()
						.transferId(transferId)
						.type(type)
						.recipientAccountId(recipientAccountId)
						.message(message)
						.build()));
	}

	@Transactional
	public void markSent(Long notificationId) {
		notificationRepository.findById(notificationId).ifPresent(Notification::markSent);
	}
}
