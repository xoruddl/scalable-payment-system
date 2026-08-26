package com.remittance.notification.service;

import com.remittance.notification.domain.NotificationType;

import java.util.UUID;

/**
 * 아직 저장되지 않은 알림 하나. <b>한 소식이 만들어내는 알림들을 함께 다루기 위해</b> 있다.
 *
 * <p>완료된 송금은 보낸 사람과 받은 사람 <b>둘</b>에게 알린다. 전에는 그 둘을 각각 저장하고
 * 각각 표시해서 <b>커밋을 네 번</b> 썼는데, 커밋 하나가 평균 47.8ms짜리 공유 관문을 지난다.
 * 묶어서 다루면 두 번으로 줄어든다.
 */
public record NotificationDraft(UUID transferId, NotificationType type, UUID recipientAccountId, String message) {
}
