package com.remittance.notification.web;

import com.remittance.notification.service.NotificationService;
import com.remittance.notification.web.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 발송된 알림을 들여다보는 조회 API.
 *
 * <p>발송 자체는 로그로 흉내내는 단계라, 이게 없으면 <b>알림이 실제로 어떻게 나갔는지 확인할
 * 길이 로그뿐</b>이다. e2e에서 눈으로 확인할 창구로 둔다.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

	private final NotificationService notificationService;

	@GetMapping("/accounts/{accountId}")
	public List<NotificationResponse> byAccount(@PathVariable UUID accountId) {
		return notificationService.notificationsOf(accountId).stream()
				.map(NotificationResponse::from)
				.toList();
	}
}
