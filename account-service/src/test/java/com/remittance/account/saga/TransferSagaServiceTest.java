package com.remittance.account.saga;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.AccountNotActiveException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Account Service가 맡은 Saga 단계들 — 출금(4a) · 입금(4a) · 환불(4b).
 *
 * <p>핵심 계약은 <b>"잔액 변경과 다음 이벤트 기록이 함께 일어나고, 두 번 일어나지 않는다"</b>이다.
 * 이벤트는 at-least-once로 재전송되므로 두 번째 계약이 특히 중요하다 —
 * 출금이 두 번 적용되면 그대로 사고다.
 *
 * <p>실패 흐름에는 계약이 하나 더 붙는다. <b>단계가 실패했으면 그 사실이 반드시 이벤트로 나가야 한다.</b>
 * 조용히 멈추면 송금이 PENDING인 채로 영원히 남는다(Step 4a가 그랬다).
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
	 * <b>다음 단계 이벤트(debited)가 나가면 안 된다</b> — 출금이 안 됐는데 입금이 일어나기 때문이다.
	 * 대신 실패 사실을 알려야 송금이 종결된다.
	 */
	@Test
	void 잔액이_부족하면_출금하지_않고_transfer_debit_failed를_남긴다() {
		UUID from = fundedAccount(100);
		UUID to = fundedAccount(0);
		UUID transferId = UUID.randomUUID();

		transferSagaService.onRequested(
				new TransferEvents.Requested(transferId, from, to, amount, "KRW"));

		assertThat(balanceOf(from)).isEqualByComparingTo("100.00");
		assertThat(eventsOf(transferId))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.getEventType())
							.as("입금으로 이어지는 debited가 아니라, 흐름을 꺾는 debit-failed가 나가야 한다")
							.isEqualTo(TransferEvents.DEBIT_FAILED);
					TransferEvents.DebitFailed body =
							objectMapper.readValue(event.getPayload(), TransferEvents.DebitFailed.class);
					assertThat(body.failureReason()).contains("잔액이 부족");
				});
	}

	// ───────────────────────── Step 4b — 실패·보상 ─────────────────────────

	/** 통화가 다른 계좌로는 입금할 수 없다. 입금 단계를 반드시 실패시키는 가장 간단한 방법. */
	private UUID foreignAccount() {
		return accountService.createAccount(UUID.randomUUID(), "USD", AccountType.PERSONAL).getAccountId();
	}

	/**
	 * 여기가 Saga에서 가장 위험한 지점이다. 출금은 이미 나갔는데 입금이 안 됐으므로
	 * <b>돈이 공중에 뜬다</b>. 그 사실을 이벤트로 남기지 않으면 아무도 되돌려주지 않는다.
	 */
	@Test
	void 입금이_실패하면_transfer_credit_failed를_남겨_보상을_부른다() {
		UUID from = fundedAccount(5_000);
		UUID to = foreignAccount();
		UUID transferId = UUID.randomUUID();

		transferSagaService.onDebited(new TransferEvents.Debited(
				transferId, from, to, amount, "KRW", new BigDecimal("4000.00"), Instant.now()));

		assertThat(eventsOf(transferId))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.getEventType()).isEqualTo(TransferEvents.CREDIT_FAILED);
					TransferEvents.CreditFailed body =
							objectMapper.readValue(event.getPayload(), TransferEvents.CreditFailed.class);
					assertThat(body.fromAccountId())
							.as("되돌릴 계좌와 금액이 본문에 있어야 보상하는 쪽이 되묻지 않는다")
							.isEqualTo(from);
					assertThat(body.amount()).isEqualByComparingTo(amount);
					assertThat(body.failureReason()).contains("통화가 일치하지 않습니다");
				});
	}

	/**
	 * 실패도 "처리했다"고 기록해야 한다. 잔액이 부족한 송금은 몇 번을 다시 받아도 부족하므로,
	 * 흔적을 남기지 않으면 재전송될 때마다 실패 이벤트가 새로 나가고 <b>Transfer가 같은 실패를 반복해서 듣는다</b>.
	 */
	@Test
	void 같은_이벤트로_두_번_실패해도_실패_이벤트는_한_번만_나간다() {
		UUID from = fundedAccount(100);
		UUID to = fundedAccount(0);
		UUID transferId = UUID.randomUUID();
		TransferEvents.Requested event =
				new TransferEvents.Requested(transferId, from, to, amount, "KRW");

		transferSagaService.onRequested(event);
		transferSagaService.onRequested(event);

		assertThat(eventsOf(transferId)).hasSize(1);
	}

	@Test
	void 보상_이벤트를_받으면_출금을_되돌리고_transfer_debit_reversed를_남긴다() {
		UUID from = fundedAccount(5_000);
		UUID to = foreignAccount();
		UUID transferId = UUID.randomUUID();
		transferSagaService.onRequested(new TransferEvents.Requested(transferId, from, to, amount, "KRW"));
		assertThat(balanceOf(from)).isEqualByComparingTo("4000.00");

		transferSagaService.onCreditFailed(new TransferEvents.CreditFailed(
				transferId, from, to, amount, "KRW", "통화가 일치하지 않습니다", Instant.now()));

		assertThat(balanceOf(from))
				.as("보상이 끝나면 출금 전 잔액으로 정확히 돌아와야 한다")
				.isEqualByComparingTo("5000.00");
		assertThat(eventsOf(transferId))
				.extracting(OutboxEvent::getEventType)
				.containsExactly(TransferEvents.DEBITED, TransferEvents.DEBIT_REVERSED);
	}

	/** 보상도 at-least-once로 재전송된다. 두 번 환불하면 없던 돈이 생긴다. */
	@Test
	void 같은_보상_이벤트를_두_번_받아도_환불은_한_번만_된다() {
		UUID from = fundedAccount(4_000);
		UUID to = foreignAccount();
		UUID transferId = UUID.randomUUID();
		TransferEvents.CreditFailed event = new TransferEvents.CreditFailed(
				transferId, from, to, amount, "KRW", "통화가 일치하지 않습니다", Instant.now());

		transferSagaService.onCreditFailed(event);
		transferSagaService.onCreditFailed(event);

		assertThat(balanceOf(from))
				.as("두 번 돌려주면 없던 돈이 생긴다")
				.isEqualByComparingTo("5000.00");
		assertThat(eventsOf(transferId)).hasSize(1);
	}

	/**
	 * 전진 단계는 실패하면 실패 이벤트를 남기고 물러나지만, <b>보상은 물러날 곳이 없다.</b>
	 * 여기서 예외를 삼키면 고객 돈이 사라진 채로 조용히 끝난다.
	 * 밖으로 던져야 컨슈머가 재시도하고, 끝내 안 되면 DLT로 가서 사람 눈에 띈다.
	 */
	@Test
	void 보상_자체가_실패하면_삼키지_않고_밖으로_던진다() {
		UUID from = fundedAccount(4_000);
		UUID to = foreignAccount();
		UUID transferId = UUID.randomUUID();
		// 환불받아야 할 계좌가 그 사이 닫혔다
		Account frozen = accountRepository.findByAccountId(from).orElseThrow();
		frozen.freeze();
		accountRepository.saveAndFlush(frozen);

		assertThatThrownBy(() -> transferSagaService.onCreditFailed(new TransferEvents.CreditFailed(
				transferId, from, to, amount, "KRW", "통화가 일치하지 않습니다", Instant.now())))
				.isInstanceOf(AccountNotActiveException.class);

		assertThat(eventsOf(transferId))
				.as("되돌리지도 못했으면서 되돌렸다고 알리면 안 된다")
				.isEmpty();
	}
}
