package com.remittance.notification.service;

import com.remittance.notification.AbstractIntegrationTest;
import com.remittance.notification.domain.Notification;
import com.remittance.notification.domain.NotificationStatus;
import com.remittance.notification.domain.NotificationType;
import com.remittance.notification.messaging.TransferEvents;
import com.remittance.notification.repository.NotificationRepository;
import com.remittance.notification.send.NotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 완료된 송금의 알림 <b>둘을 한 트랜잭션으로 묶으면서 생긴 위험</b>을 본다 (Phase 6).
 *
 * <p>커밋을 넷에서 둘로 줄이려고 자리 잡기와 보냄 표시를 각각 한 번에 묶었다.
 * 그런데 <b>발송은 묶을 수 없다</b> — 보낸 사람에게는 나갔는데 받은 사람에게 실패할 수 있다.
 *
 * <p>이때 둘 다 {@code PENDING}으로 남기면 재배달 때 <b>이미 나간 알림이 한 번 더 나간다.</b>
 * 알림은 회수할 수 없으므로 그건 사고다. 그래서 {@code markSentAll}을 {@code finally}에 두고
 * <b>실제로 나간 것만</b> 표시한 뒤 예외를 올린다.
 *
 * <p>묶기 전에는 이 상황이 자연히 안전했다 — 첫째를 보내고 <b>바로</b> 표시했기 때문이다.
 * 묶으면서 잃을 뻔한 성질이라 여기서 못 박는다.
 */
@SpringBootTest
@Import(PartialSendFailureTest.FailSecondSenderConfig.class)
class PartialSendFailureTest extends AbstractIntegrationTest {

	@Autowired
	private NotificationService notificationService;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private FailSecondSender sender;

	private TransferEvents.TransferSettled completed(UUID transferId, UUID from, UUID to) {
		return new TransferEvents.TransferSettled(transferId, "COMPLETED", from, to,
				new BigDecimal("10000.00"), "KRW", null, Instant.now());
	}

	@Test
	void 둘째_발송이_실패해도_첫째는_보냄으로_남는다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();
		sender.failFor(NotificationType.TRANSFER_RECEIVED);

		assertThatThrownBy(() -> notificationService.onCompleted(completed(transferId, from, to)))
				.as("실패는 삼키지 않는다 — 오프셋이 커밋되지 않아야 재배달된다")
				.isInstanceOf(IllegalStateException.class);

		assertThat(statusOf(transferId, NotificationType.TRANSFER_SENT, from))
				.as("나간 알림은 나갔다고 적혀야 한다. 아니면 재배달 때 한 번 더 나간다")
				.isEqualTo(NotificationStatus.SENT);
		assertThat(statusOf(transferId, NotificationType.TRANSFER_RECEIVED, to))
				.as("못 나간 알림은 PENDING이라야 재배달 때 다시 시도된다")
				.isEqualTo(NotificationStatus.PENDING);
	}

	@Test
	void 재배달되면_못_나간_것만_다시_보낸다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();

		sender.failFor(NotificationType.TRANSFER_RECEIVED);
		assertThatThrownBy(() -> notificationService.onCompleted(completed(transferId, from, to)))
				.isInstanceOf(IllegalStateException.class);

		sender.failFor(null); // 이번엔 발송이 된다
		notificationService.onCompleted(completed(transferId, from, to));

		assertThat(sender.sentTypes)
				.as("보낸 사람에게는 한 번만 나가야 한다 — 회수할 수 없는 알림이다")
				.containsExactly(NotificationType.TRANSFER_SENT, NotificationType.TRANSFER_RECEIVED);
	}

	private NotificationStatus statusOf(UUID transferId, NotificationType type, UUID recipient) {
		return notificationRepository
				.findByTransferIdAndTypeAndRecipientAccountId(transferId, type, recipient)
				.map(Notification::getStatus)
				.orElseThrow(() -> new AssertionError("알림이 저장되지 않았다: " + type));
	}

	static class FailSecondSender implements NotificationSender {

		private final List<NotificationType> sentTypes = new CopyOnWriteArrayList<>();
		private volatile NotificationType failing;

		void failFor(NotificationType type) {
			this.failing = type;
		}

		@Override
		public void send(Notification notification) {
			if (notification.getType() == failing) {
				throw new IllegalStateException("발송 실패를 흉내낸다: " + notification.getType());
			}
			sentTypes.add(notification.getType());
		}
	}

	@TestConfiguration
	static class FailSecondSenderConfig {
		@Bean
		@Primary
		FailSecondSender failSecondSender() {
			return new FailSecondSender();
		}
	}
}
