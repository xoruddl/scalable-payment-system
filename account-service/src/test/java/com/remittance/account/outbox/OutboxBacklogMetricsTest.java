package com.remittance.account.outbox;

import com.remittance.account.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 적체가 <b>바깥에서 보이는지</b> 지킨다 (Phase 5 Step 2).
 *
 * <p>릴레이가 부하를 못 따라가면 <b>접수는 계속 202를 주고 HTTP 에러율도 안 오르는데
 * 돈만 안 움직이는</b> 상태가 된다. k6가 재는 "종결 지연"이 늘어나는 건 보이지만
 * <b>어디서</b> 늘어나는지는 이 값이 답한다.
 *
 * <p>릴레이는 테스트 설정에서 꺼져 있으므로(`outbox.relay.enabled=false`) 넣어둔 이벤트가
 * 세는 도중에 발행되어 사라지지 않는다.
 */
@SpringBootTest
class OutboxBacklogMetricsTest extends AbstractIntegrationTest {

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private MeterRegistry meterRegistry;

	private double backlog() {
		return meterRegistry.get("remittance.outbox.backlog").gauge().value();
	}

	private void saveUnpublished() {
		outboxEventRepository.saveAndFlush(OutboxEvent.builder()
				.aggregateType("Account")
				.aggregateId(UUID.randomUUID())
				.eventType("test.backlog")
				.payload("{}")
				.build());
	}

	@Test
	void 발행되지_않은_이벤트가_쌓이면_그만큼_올라간다() {
		double before = backlog();

		saveUnpublished();
		saveUnpublished();

		assertThat(backlog())
				.as("이 값이 없으면 릴레이가 못 따라가는 순간을 바깥에서 볼 방법이 없다")
				.isEqualTo(before + 2);
	}

	@Test
	void 발행된_이벤트는_적체로_세지_않는다() {
		saveUnpublished();
		double before = backlog();

		OutboxEvent published = outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(Limit.of(1)).getFirst();
		published.markPublished();
		outboxEventRepository.saveAndFlush(published);

		// 쌓인 총량이 아니라 "아직 안 나간 것"을 세야 한다. 총량을 세면 트래픽이 많을수록
		// 값이 계속 올라가서 적체와 구분되지 않는다.
		assertThat(backlog()).isEqualTo(before - 1);
	}
}
