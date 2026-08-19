package com.remittance.account.saga;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 Step 4a — Account Service가 맡은 Saga 두 단계.
 *
 * <p>핵심 계약은 <b>"잔액 변경과 다음 이벤트 기록이 함께 일어나고, 두 번 일어나지 않는다"</b>이다.
 * 이벤트는 at-least-once로 재전송되므로 두 번째 계약이 특히 중요하다 —
 * 출금이 두 번 적용되면 그대로 사고다.
 */
@SpringBootTest
class TransferSagaServiceTest extends AbstractIntegrationTest {

	@Autowired
	private TransferSagaService transferSagaService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	private final BigDecimal amount = new BigDecimal("1000.00");

	private UUID fundedAccount(int balance) {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(account.getAccountId(), BigDecimal.valueOf(balance), "KRW");
		return account.getAccountId();
	}

	private BigDecimal balanceOf(UUID accountId) {
		return accountRepository.findByAccountId(accountId).orElseThrow().getBalance();
	}

	private List<OutboxEvent> eventsOf(UUID transferId) {
		return outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId);
	}

	@Test
	void 송금_접수_이벤트를_받으면_출금하고_transfer_debited를_남긴다() {
		UUID from = fundedAccount(5_000);
		UUID to = fundedAccount(0);
		UUID transferId = UUID.randomUUID();

		transferSagaService.onRequested(
				new TransferEvents.Requested(transferId, from, to, amount, "KRW"));

		assertThat(balanceOf(from)).isEqualByComparingTo("4000.00");
		assertThat(eventsOf(transferId))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.getEventType()).isEqualTo(TransferEvents.DEBITED);
					TransferEvents.Debited body =
							objectMapper.readValue(event.getPayload(), TransferEvents.Debited.class);
					assertThat(body.fromBalanceAfter())
							.as("다음 단계가 되묻지 않도록 변경 후 잔액을 실어 보낸다")
							.isEqualByComparingTo("4000.00");
				});
	}

	@Test
	void 출금_완료_이벤트를_받으면_입금하고_transfer_credited를_남긴다() {
		UUID from = fundedAccount(5_000);
		UUID to = fundedAccount(1_000);
		UUID transferId = UUID.randomUUID();

		transferSagaService.onDebited(new TransferEvents.Debited(
				transferId, from, to, amount, "KRW", new BigDecimal("4000.00"), Instant.now()));

		assertThat(balanceOf(to)).isEqualByComparingTo("2000.00");
		assertThat(eventsOf(transferId))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.getEventType()).isEqualTo(TransferEvents.CREDITED);
					TransferEvents.Credited body =
							objectMapper.readValue(event.getPayload(), TransferEvents.Credited.class);
					assertThat(body.fromBalanceAfter()).isEqualByComparingTo("4000.00");
					assertThat(body.toBalanceAfter()).isEqualByComparingTo("2000.00");
				});
	}

	/**
	 * Outbox 릴레이는 발행 성공 직후 마킹 전에 죽을 수 있어 같은 이벤트를 두 번 보낼 수 있다.
	 * 그때 출금이 두 번 적용되면 안 된다.
	 */
	@Test
	void 같은_접수_이벤트를_두_번_받아도_출금은_한_번만_된다() {
		UUID from = fundedAccount(5_000);
		UUID to = fundedAccount(0);
		UUID transferId = UUID.randomUUID();
		TransferEvents.Requested event =
				new TransferEvents.Requested(transferId, from, to, amount, "KRW");

		transferSagaService.onRequested(event);
		transferSagaService.onRequested(event);

		assertThat(balanceOf(from))
				.as("재전송으로 두 번 빠지면 그대로 사고다")
				.isEqualByComparingTo("4000.00");
		assertThat(eventsOf(transferId))
				.as("다음 단계 이벤트도 한 번만 나가야 입금이 두 번 되지 않는다")
				.hasSize(1);
	}

	/**
	 * 잔액이 모자라면 다시 시도해도 결과가 같으므로 재시도하지 않고 멈춘다.
	 * <b>이때 다음 단계 이벤트가 나가면 안 된다</b> — 출금이 안 됐는데 입금이 일어나기 때문이다.
	 */
	@Test
	void 잔액이_부족하면_아무것도_바꾸지_않고_다음_이벤트도_남기지_않는다() {
		UUID from = fundedAccount(100);
		UUID to = fundedAccount(0);
		UUID transferId = UUID.randomUUID();

		transferSagaService.onRequested(
				new TransferEvents.Requested(transferId, from, to, amount, "KRW"));

		assertThat(balanceOf(from)).isEqualByComparingTo("100.00");
		assertThat(eventsOf(transferId)).isEmpty();
	}
}
