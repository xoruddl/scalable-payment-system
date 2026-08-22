package com.remittance.account.messaging;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.saga.TransferSagaService;
import com.remittance.account.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 2 Step 4c — 컨슈머가 실패했을 때 벌어지는 일.
 *
 * <p>확인하려는 계약은 둘이다.
 * <ul>
 *   <li>일시적인 실패는 <b>재시도되어 결국 처리된다</b> — 한 번 삐끗했다고 송금이 멈추면 안 된다.</li>
 *   <li>끝내 실패한 메시지는 <b>사라지지 않고 DLT에 남는다</b> — 돈이 걸린 이벤트라 유실이 곧 사고다.</li>
 * </ul>
 *
 * <p>이 테스트만 컨슈머 그룹을 따로 쓴다. 다른 테스트 컨텍스트와 그룹을 공유하면 메시지가
 * <b>다른 컨텍스트의 리스너</b>에게 갈 수 있어, "이 컨텍스트의 스파이가 몇 번 불렸나"를 셀 수 없다.
 */
@SpringBootTest(properties = "spring.kafka.consumer.group-id=account-error-handling-test")
@Import(KafkaErrorHandlingTest.DeadLetterProbe.class)
class KafkaErrorHandlingTest extends AbstractIntegrationTest {

	private static final String CREDIT_FAILED_DLT = TransferEvents.CREDIT_FAILED + ".DLT";

	/** DLT에 실제로 메시지가 도착하는지 보려면 누군가는 그 토픽을 듣고 있어야 한다. */
	@TestConfiguration
	static class DeadLetterProbe {

		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@KafkaListener(topics = CREDIT_FAILED_DLT, groupId = "dead-letter-probe")
		void onDeadLetter(String payload) {
			received.add(payload);
		}
	}

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DeadLetterProbe deadLetterProbe;

	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * DLT로 보낸 건수 (Phase 5 Step 2).
	 *
	 * <p>아직 한 건도 없으면 미터 자체가 없다 — 카운터는 처음 증가할 때 만들어진다.
	 * 그때 예외 대신 0을 돌려줘야 "전 대비 +1"을 셀 수 있다.
	 */
	private double dltCount(String topic) {
		try {
			return meterRegistry.get("remittance.kafka.dlt.published").tag("topic", topic).counter().count();
		} catch (MeterNotFoundException notYet) {
			return 0;
		}
	}

	@MockitoSpyBean
	private TransferSagaService transferSagaService;

	private final BigDecimal amount = new BigDecimal("1000.00");

	private UUID fundedAccount() {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(account.getAccountId(), BigDecimal.valueOf(5_000), "KRW");
		return account.getAccountId();
	}

	private void publish(String topic, Object payload, UUID transferId) {
		kafkaTemplate.send(topic, transferId.toString(), objectMapper.writeValueAsString(payload)).join();
	}

	/**
	 * 낙관적 락 충돌처럼 <b>다시 하면 될 수도 있는</b> 실패다. 여기서 포기하면 송금이 멈춘다.
	 */
	@Test
	void 일시적으로_실패한_이벤트는_재시도되어_결국_처리된다() {
		UUID from = fundedAccount();
		UUID to = fundedAccount();
		UUID transferId = UUID.randomUUID();

		// 첫 배달만 실패시키고, 재배달되면 정상 처리한다
		willThrow(new ConcurrentUpdateException(from))
				.willCallRealMethod()
				.given(transferSagaService).onRequested(any());

		publish(TransferEvents.REQUESTED,
				new TransferEvents.Requested(transferId, from, to, amount, "KRW"), transferId);

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
			assertThat(accountRepository.findByAccountId(from).orElseThrow().getBalance())
					.as("한 번 실패했다고 포기하면 출금이 영영 일어나지 않는다")
					.isEqualByComparingTo("4000.00");
		});
		verify(transferSagaService, atLeast(2)).onRequested(any());
	}

	/**
	 * 보상(환불) 실패는 다시 시도해도 결과가 같다. 이런 메시지를 그냥 버리면
	 * <b>고객 돈이 사라진 채로 아무 기록도 남지 않는다.</b> DLT에 남아야 사람이 찾아낼 수 있다.
	 *
	 * <p>이런 실패는 <b>재시도 없이 곧바로</b> DLT로 가야 한다. 결과가 달라질 리 없는데 백오프를
	 * 다 소진하면, 그동안 같은 파티션의 뒤 메시지들이 발이 묶인다.
	 */
	@Test
	void 끝내_실패한_이벤트는_버려지지_않고_DLT로_간다() {
		double dltBefore = dltCount(TransferEvents.CREDIT_FAILED);
		UUID from = fundedAccount();
		UUID transferId = UUID.randomUUID();
		// 환불받아야 할 계좌가 닫혀 있어 보상이 성공할 수 없다
		Account frozen = accountRepository.findByAccountId(from).orElseThrow();
		frozen.freeze();
		accountRepository.saveAndFlush(frozen);

		publish(TransferEvents.CREDIT_FAILED, new TransferEvents.CreditFailed(
				transferId, from, UUID.randomUUID(), amount, "KRW", "통화가 일치하지 않습니다", Instant.now()),
				transferId);

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
				assertThat(deadLetterProbe.received)
						.as("되돌리지 못한 환불이 조용히 사라지면 아무도 모른다")
						.anySatisfy(payload -> assertThat(payload).contains(transferId.toString())));
		verify(transferSagaService, times(1))
				.onCreditFailed(argThat(event -> event.transferId().equals(transferId)));
		assertThat(dltCount(TransferEvents.CREDIT_FAILED))
				.as("DLT에 남는 것만으로는 아무도 모른다 - 2026-08-22 e2e에서 로그 한 줄 없이 "
						+ "적재되는 것을 확인했다. 그래프에서 튀어야 사람에게 닿는다")
				.isEqualTo(dltBefore + 1);
	}
}
