package com.remittance.notification.web.dto;

import com.remittance.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
		UUID transferId,
		String type,
		UUID recipientAccountId,
		String message,
		String status,
		Instant sentAt
) {
	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
				notification.getTransferId(),
				notification.getType().name(),
				notification.getRecipientAccountId(),
				notification.getMessage(),
				notification.getStatus().name(),
				notification.getSentAt());
	}
}
