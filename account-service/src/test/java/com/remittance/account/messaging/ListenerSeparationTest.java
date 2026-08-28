package com.remittance.account.messaging;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.external.ExternalBankClient;
import com.remittance.account.external.ExternalCreditResult;
import com.remittance.account.external.ExternalCreditStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 내부 입금과 외부 호출을 <b>다른 컨슈머가</b> 처리하는가 (Phase 6.5).
 *
 * <h2>왜 나눴나</h2>
 * 같은 리스너에서 둘 다 하니 느린 상대가 우리 내부 송금을 묶었다.
 * 상대 2초 지연에서 내부 종결 p99가 <b>3,071 → 58,790ms</b>. 격벽으로 11,579ms까지
 * 줄였지만 <b>같은 풀을 나눠 쓰는 한 거기까지</b>였다.
 *
 * <h2>여기서 거는 것</h2>
 * <ol>
 *   <li><b>각자 자기 몫만</b> 처리한다 — 겹치면 같은 송금이 두 번 처리된다</li>
 *   <li><b>그룹이 다르다</b> — 같은 그룹이면 파티션을 나눠 갖게 되어 분리가 아니다</li>
 * </ol>
 */
@SpringBootTest
class ListenerSeparationTest extends AbstractIntegrationTest {

	@Autowired
	private TransferEventConsumer internalConsumer;

	@Autowired
	private ExternalCreditConsumer externalConsumer;

	@Autowired
	private KafkaListenerEndpointRegistry registry;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ExternalBankClient externalBankClient;

	private String debitedPayload(boolean external) {
		TransferEvents.Debited event = external
				? new TransferEvents.Debited(UUID.randomUUID(), UUID.randomUUID(), null,
						"KB", "1111-2222", BigDecimal.valueOf(1_000), "KRW", BigDecimal.ZERO, Instant.now())
				: TransferEvents.Debited.internal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
						BigDecimal.valueOf(1_000), "KRW", BigDecimal.ZERO, Instant.now());
		return objectMapper.writeValueAsString(event);
	}

	@Test
	void 내부_리스너는_외부_송금을_건드리지_않는다() {
		internalConsumer.onDebited(debitedPayload(true));

		// 건드리면 분리한 의미가 없다 — 느린 상대가 다시 이쪽 스레드를 묶는다.
		verify(externalBankClient, never()).credit(any(), any(), any(), any(), any());
	}

	@Test
	void 외부_리스너는_내부_송금을_건드리지_않는다() {
		// 계좌가 없는 내부 송금이라, 처리했다면 AccountNotFoundException이 났을 것이다.
		externalConsumer.onDebited(debitedPayload(false));
	}

	@Test
	void 외부_리스너는_외부_송금을_처리한다() {
		given(externalBankClient.credit(any(), any(), any(), any(), any()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.REJECTED, "테스트"));

		externalConsumer.onDebited(debitedPayload(true));

		verify(externalBankClient).credit(any(), any(), any(), any(), any());
	}

	@Test
	void 두_리스너는_서로_다른_그룹이라_각자_스레드를_갖는다() {
		String internalGroup = groupOf(TransferEvents.DEBITED);
		String externalGroup = groupOf(TransferEvents.DEBITED + ".external");

		// 같은 그룹이면 파티션을 나눠 갖게 되어 <b>분리가 아니라 그냥 쪼개기</b>가 된다.
		// 그러면 느린 외부 호출이 내부 송금의 파티션을 붙드는 일이 그대로 생긴다.
		assertThat(externalGroup)
				.as("그룹이 같으면 스레드도 나눠 갖는다 — 분리한 것이 아니다")
				.isNotEqualTo(internalGroup);
	}

	private String groupOf(String listenerId) {
		MessageListenerContainer container = registry.getListenerContainer(listenerId);
		assertThat(container).as("%s 리스너가 등록되지 않았다", listenerId).isNotNull();
		return ((ConcurrentMessageListenerContainer<?, ?>) container).getGroupId();
	}
}
