package com.remittance.account.outbox;

import com.remittance.account.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 커밋된 Outbox 행은 다음 폴링을 기다리지 않고 나간다 (D-007).
 *
 * 주기를 10분으로 줘 재운다. 기동 직후 한 바퀴 뒤로는 주기로 돌지 않으므로, 몇 초 안에 발행됐다면
 * 커밋이 깨운 것이다.
 *
 * ⚠️ 릴레이를 켠 다른 테스트 컨텍스트가 같은 JVM에 캐시돼 있으면, 그 릴레이가 같은 DB를 훑어 대신 발행할 수
 * 있다. 그래서 이 클래스가 결함을 가르는 힘은 혼자 돌릴 때 온전하다. 루프가 신호에 답하는지는
 * {@link OutboxRelayLoopTest}가 컨텍스트 없이 가른다. transfer-service의 같은 이름 테스트와 짝이다.
 */
@SpringBootTest(properties = {
		"outbox.relay.enabled=true",
		"outbox.relay.interval-ms=600000"
})
class OutboxRelayWakeupTest extends AbstractIntegrationTest {

	/** 주기(10분)로는 닿을 수 없고, 깨웠다면 넉넉한 시간. 토픽을 처음 만드는 시간까지 넣었다. */
	private static final Duration 깨우면_닿는_시간 = Duration.ofSeconds(10);

	/** 커밋을 이만큼 미룬다. 커밋 전에 깨웠다면 릴레이는 이 사이에 돌아 빈손으로 돌아가고 10분을 잔다. */
	private static final Duration 커밋을_미루는_시간 = Duration.ofMillis(500);

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void 커밋되면_다음_폴링을_기다리지_않고_발행한다() {
		UUID aggregateId = 적는다(Duration.ZERO);

		발행될_때까지_기다린다(aggregateId);
	}

	@Test
	void 커밋되기_전에는_깨우지_않는다() {
		UUID aggregateId = 적는다(커밋을_미루는_시간);

		발행될_때까지_기다린다(aggregateId);
	}

	/** 운영 코드처럼 트랜잭션 안에서 적고, 커밋을 {@code 커밋_전에} 만큼 미룬다. */
	private UUID 적는다(Duration 커밋_전에) {
		UUID aggregateId = UUID.randomUUID();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			outboxEventRepository.save(OutboxEvent.builder()
					.aggregateType("Account")
					.aggregateId(aggregateId)
					.eventType("test.event")
					.payload("{}")
					.build());
			잔다(커밋_전에);
		});
		return aggregateId;
	}

	private void 발행될_때까지_기다린다(UUID aggregateId) {
		await().atMost(깨우면_닿는_시간).untilAsserted(() ->
				assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(aggregateId))
						.singleElement()
						.extracting(OutboxEvent::getPublishedAt)
						.as("주기(10분)를 기다리고 있다 — 커밋이 릴레이를 깨우지 못했다")
						.isNotNull());
	}

	private static void 잔다(Duration duration) {
		try {
			Thread.sleep(duration);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
