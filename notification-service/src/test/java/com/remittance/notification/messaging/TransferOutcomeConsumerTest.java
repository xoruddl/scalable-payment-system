package com.remittance.notification.messaging;

import com.remittance.notification.AbstractIntegrationTest;
import com.remittance.notification.domain.Notification;
import com.remittance.notification.domain.NotificationStatus;
import com.remittance.notification.domain.NotificationType;
import com.remittance.notification.repository.NotificationRepository;
import com.remittance.notification.send.NotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 3 — 송금이 끝나면 알린다. <b>다만 한 번만.</b>
 *
 * <p>이 서비스가 연습하는 건 알림 기능 자체가 아니라 <b>되돌릴 수 없는 부수효과를
 * at-least-once 이벤트 위에서 다루는 법</b>이다. 원장은 같은 줄을 덮어쓰면 그만이고 잔액은
 * 처리 흔적으로 막을 수 있지만, <b>이미 나간 알림은 회수할 수 없다.</b>
 *
 * <p>그래서 확인하는 건 두 가지다 — <b>빠짐없이 가는가</b>, 그리고 <b>두 번 가지 않는가.</b>
 */
@SpringBootTest
@Import(TransferOutcomeConsumerTest.CountingSenderConfig.class)
class TransferOutcomeConsumerTest extends AbstractIntegrationTest {

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CountingSender sender;

	private final BigDecimal amount = new BigDecimal("10000.00");

	private TransferEvents.TransferSettled settled(UUID transferId, UUID from, UUID to,
			String status, String failureReason) {
		return new TransferEvents.TransferSettled(
				transferId, status, from, to, amount, "KRW", failureReason, Instant.now());
	}

	private void publish(String topic, TransferEvents.TransferSettled event) {
		kafkaTemplate.send(topic, event.transferId().toString(),
				objectMapper.writeValueAsString(event)).join();
	}

	private List<Notification> notificationsOf(UUID transferId) {
		return notificationRepository.findByTransferIdOrderByIdAsc(transferId);
	}

	private long sentCountFor(UUID transferId) {
		return sender.sent.stream().filter(n -> n.getTransferId().equals(transferId)).count();
	}

	/** 완료된 송금은 두 사람의 일이다. 한쪽만 알리면 나머지 한쪽은 돈이 오간 걸 모른다. */
	@Test
	void 완료된_송금은_보낸_쪽과_받은_쪽_모두에게_알린다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();

		publish(TransferEvents.COMPLETED, settled(transferId, from, to, "COMPLETED", null));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(notificationsOf(transferId))
						.hasSize(2)
						.satisfiesExactlyInAnyOrder(
								n -> {
									assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_SENT);
									assertThat(n.getRecipientAccountId()).isEqualTo(from);
								},
								n -> {
									assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_RECEIVED);
									assertThat(n.getRecipientAccountId()).isEqualTo(to);
								}));
	}

	/**
	 * 실패는 <b>보낸 쪽에게만</b> 간다. 받는 쪽에 알리면 있지도 않았던 거래를 알려주는 꼴이 된다 —
	 * "당신에게 오려던 돈이 실패했습니다"는 받는 사람이 알 이유가 없는 소식이다.
	 */
	@Test
	void 실패한_송금은_보낸_쪽에게만_알린다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();

		publish(TransferEvents.FAILED, settled(transferId, from, to, "FAILED", "잔액이 부족합니다"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(notificationsOf(transferId))
						.singleElement()
						.satisfies(n -> {
							assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
							assertThat(n.getRecipientAccountId()).isEqualTo(from);
							assertThat(n.getMessage())
									.as("왜 실패했는지가 없으면 사용자는 다시 시도할지 판단할 수 없다")
									.contains("잔액이 부족합니다");
						}));
	}

	/**
	 * <b>이 서비스의 핵심.</b> 이벤트는 at-least-once라 같은 소식이 두 번 온다.
	 * "10000원을 보냈습니다"가 두 번 가면 사용자는 두 번 빠져나간 줄 안다.
	 */
	@Test
	void 같은_소식을_두_번_받아도_알림은_한_번만_나간다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();
		TransferEvents.TransferSettled event = settled(transferId, from, to, "COMPLETED", null);

		publish(TransferEvents.COMPLETED, event);
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(sentCountFor(transferId)).isEqualTo(2));

		publish(TransferEvents.COMPLETED, event);

		await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(notificationsOf(transferId))
					.as("같은 소식으로 알림 행이 늘면 그 즉시 두 번 나간다")
					.hasSize(2);
			assertThat(sentCountFor(transferId))
					.as("실제 발송도 두 번을 넘으면 안 된다")
					.isEqualTo(2);
		});
	}

	/**
	 * 발송이 실패하면 오프셋이 커밋되지 않아 재배달된다. 그때 <b>다시 보내야</b> 한다 —
	 * 자리를 잡자마자 SENT로 적어버리면 재배달이 "이미 보냈다"로 읽고 건너뛰어,
	 * <b>알림이 조용히 사라지고 아무도 모른다.</b>
	 */
	@Test
	void 발송이_실패하면_재배달로_다시_보낸다() {
		UUID transferId = UUID.randomUUID();
		UUID from = UUID.randomUUID();

		sender.failFor(from, 1);
		publish(TransferEvents.FAILED, settled(transferId, from, UUID.randomUUID(), "FAILED", "테스트"));

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
				assertThat(notificationsOf(transferId))
						.singleElement()
						.satisfies(n -> assertThat(n.getStatus())
								.as("첫 발송이 실패했어도 재시도해서 끝내 나가야 한다")
								.isEqualTo(NotificationStatus.SENT)));
	}

	/** 실제로 몇 번 나갔는지 세려면 발송하는 쪽을 붙잡고 있어야 한다. */
	static class CountingSender implements NotificationSender {

		final List<Notification> sent = new CopyOnWriteArrayList<>();
		private volatile UUID failingRecipient;
		private final AtomicInteger remainingFailures = new AtomicInteger();

		/** 이 사람에게 가는 발송을 앞으로 {@code times}번 실패시킨다. */
		void failFor(UUID recipientAccountId, int times) {
			this.failingRecipient = recipientAccountId;
			this.remainingFailures.set(times);
		}

		@Override
		public void send(Notification notification) {
			if (notification.getRecipientAccountId().equals(failingRecipient)
					&& remainingFailures.getAndDecrement() > 0) {
				throw new IllegalStateException("발송 실패를 흉내낸다");
			}
			sent.add(notification);
		}
	}

	@TestConfiguration
	static class CountingSenderConfig {

		@Bean
		@Primary
		CountingSender countingSender() {
			return new CountingSender();
		}
	}
}
