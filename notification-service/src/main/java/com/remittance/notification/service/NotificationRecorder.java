package com.remittance.notification.service;

import com.remittance.notification.domain.Notification;
import com.remittance.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 알림의 기록만 맡는다 — 자리를 잡고, 보냈다고 표시한다. 발송은 하지 않는다.
 *
 * {@link NotificationService}에서 떼어낸 이유는 {@code @Transactional} 때문이다.
 * 같은 빈 안에서 부르면 프록시를 타지 않아 트랜잭션이 걸리지 않는다. 그러면
 * {@code markSent()}의 변경이 더티 체킹으로 반영되지 않아 상태가 영원히 PENDING으로 남고,
 * 재배달이 올 때마다 알림이 다시 나간다. 실제로 그렇게 만들어서 테스트가 잡아냈다.
 *
 * (account-service의 {@code BalanceMutationExecutor}와 같은 이유로 갈라놓은 구조다.)
 *
 * 한 소식의 알림들을 함께 처리한다 (Phase 6, 2026-08-26)
 * 전에는 알림 하나씩 저장하고 하나씩 표시해서, 완료된 송금 한 건에 커밋을 네 번 썼다.
 * 커밋 하나가 평균 47.8ms짜리 공유 관문을 지나고, 그 관문의 35.5%를 이 서비스가 쓰고 있었다 —
 * 정작 알림은 종결 경로도 아닌 잎사귀인데도.
 *
 * 둘을 한 트랜잭션에 담으면 커밋이 두 번이 된다. 단계를 합치는 게 아니라
 * 같은 단계의 여러 건을 묶는 것이라, 아래의 순서 보장은 그대로다.
 */
@Component
@RequiredArgsConstructor
public class NotificationRecorder {

	private final NotificationRepository notificationRepository;

	/**
	 * 이 소식이 만들어낼 알림들의 자리를 한 트랜잭션에 잡는다. 이미 있으면 그걸 그대로 쓴다.
	 *
	 * 같은 송금의 이벤트는 송금 ID를 키로 한 파티션에 모이고, 한 파티션은 그룹 안에서 컨슈머
	 * 하나만 읽는다. 그래서 여기까지 동시에 들어오는 일은 없다 — 그럼에도 unique 제약을 둔 이유는,
	 * 그 전제가 깨져도 중복 알림만은 막히게 하기 위해서다.
	 */
	@Transactional
	public List<Notification> claimAll(List<NotificationDraft> drafts) {
		List<Notification> claimed = new ArrayList<>(drafts.size());
		for (NotificationDraft draft : drafts) {
			claimed.add(notificationRepository
					.findByTransferIdAndTypeAndRecipientAccountId(
							draft.transferId(), draft.type(), draft.recipientAccountId())
					.orElseGet(() -> notificationRepository.save(Notification.builder()
							.transferId(draft.transferId())
							.type(draft.type())
							.recipientAccountId(draft.recipientAccountId())
							.message(draft.message())
							.build())));
		}
		// id가 있어야 발송 뒤에 어느 것을 표시할지 알 수 있다.
		notificationRepository.flush();
		return claimed;
	}

	/**
	 * 실제로 나간 것만 표시한다. 발송이 중간에 실패하면 성공한 것까지 되돌아가면 안 된다 —
	 * 되돌리면 재배달 때 이미 나간 알림이 한 번 더 나간다.
	 */
	@Transactional
	public void markSentAll(Collection<Long> notificationIds) {
		notificationRepository.findAllById(notificationIds).forEach(Notification::markSent);
	}
}
