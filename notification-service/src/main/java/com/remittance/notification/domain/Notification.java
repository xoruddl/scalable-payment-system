package com.remittance.notification.domain;

import com.remittance.notification.support.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 보냈거나 보낼 알림 한 건.
 *
 * <p><b>알림은 되돌릴 수 없다.</b> 잘못 보낸 알림은 회수할 방법이 없고, 돈이 걸린 알림이 두 번
 * 가면 사용자는 두 번 보내진 줄 안다. 그런데 이벤트는 at-least-once라 같은 소식이 두 번 온다.
 *
 * <p>그래서 <b>(송금, 종류, 받는 사람)</b>을 unique로 묶어 <b>DB가 중복을 막게</b> 한다.
 * 코드에서 "이미 보냈나" 확인하고 보내는 방식은 확인과 발송 사이가 벌어져 있어 믿을 수 없다.
 * 받는 사람까지 넣는 이유는 <b>완료된 송금 한 건이 두 사람에게</b> 가기 때문이다 —
 * 보내는 쪽과 받는 쪽은 서로 다른 알림이다.
 */
@Entity
@Table(name = "notifications", uniqueConstraints = @UniqueConstraint(
		name = "uk_notification_transfer_type_recipient",
		columnNames = {"transferId", "type", "recipientAccountId"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private UUID transferId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30, updatable = false)
	private NotificationType type;

	/** 누구에게 가는 알림인가. 연락처가 없는 단계라 계좌 ID를 받는 사람으로 삼는다. */
	@Column(nullable = false, updatable = false)
	private UUID recipientAccountId;

	@Column(nullable = false, length = 300, updatable = false)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private NotificationStatus status;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	/** 실제로 나간 시각. {@code null}이면 아직 못 보냈다는 뜻이다. */
	@Column
	private Instant sentAt;

	@Builder
	public Notification(UUID transferId, NotificationType type, UUID recipientAccountId, String message) {
		this.transferId = transferId;
		this.type = type;
		this.recipientAccountId = recipientAccountId;
		this.message = message;
		this.status = NotificationStatus.PENDING;
		this.createdAt = Timestamps.now();
	}

	public void markSent() {
		this.status = NotificationStatus.SENT;
		this.sentAt = Timestamps.now();
	}

	public boolean isSent() {
		return this.status == NotificationStatus.SENT;
	}
}
