package com.remittance.notification.repository;

import com.remittance.notification.domain.Notification;
import com.remittance.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/** 이 소식을 이 사람에게 이미 잡아뒀는가. 중복 발송을 가르는 조회다. */
	Optional<Notification> findByTransferIdAndTypeAndRecipientAccountId(
			UUID transferId, NotificationType type, UUID recipientAccountId);

	List<Notification> findByRecipientAccountIdOrderByIdDesc(UUID recipientAccountId);

	List<Notification> findByTransferIdOrderByIdAsc(UUID transferId);
}
