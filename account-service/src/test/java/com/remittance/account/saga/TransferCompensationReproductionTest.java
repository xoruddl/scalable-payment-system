package com.remittance.account.saga;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.service.AccountService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 Step 4b — 문제 재현 테스트 (현재는 실패한다).
 *
 * <p>Step 4a는 정상 흐름만 이벤트로 옮겼다. 출금은 성공했는데 입금이 실패하면
 * <b>돈이 공중에 뜬 채로 멈춘다</b> — 출금 계좌에서는 빠졌고, 입금 계좌에는 들어가지 않았으며,
 * 송금은 PENDING인 채로 남는다. 아무도 그걸 되돌리지 않는다.
 *
 * <p>Step 4b에서 보상 흐름({@code transfer.credit-failed} → 환불 → {@code transfer.failed})을
 * 붙이면 green이 된다.
 */
@Tag("reproduction")
@SpringBootTest
class TransferCompensationReproductionTest extends AbstractIntegrationTest {

	@Autowired
	private TransferSagaService transferSagaService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	private final BigDecimal amount = new BigDecimal("1000.00");

	private BigDecimal balanceOf(UUID accountId) {
		return accountRepository.findByAccountId(accountId).orElseThrow().getBalance();
	}

	@Test
	void 입금이_실패하면_출금이_보상되어야_한다() {
		Account from = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(from.getAccountId(), BigDecimal.valueOf(5_000), "KRW");
		// 통화가 다른 계좌라 입금 단계에서 반드시 실패한다
		Account to = accountService.createAccount(UUID.randomUUID(), "USD", AccountType.PERSONAL);
		UUID transferId = UUID.randomUUID();

		transferSagaService.onRequested(new TransferEvents.Requested(
				transferId, from.getAccountId(), to.getAccountId(), amount, "KRW"));
		assertThat(balanceOf(from.getAccountId())).isEqualByComparingTo("4000.00");

		transferSagaService.onDebited(new TransferEvents.Debited(
				transferId, from.getAccountId(), to.getAccountId(), amount, "KRW",
				new BigDecimal("4000.00"), java.time.Instant.now()));

		assertThat(balanceOf(from.getAccountId()))
				.as("입금이 실패했으면 출금도 되돌려야 한다. 그러지 않으면 돈이 사라진 채로 남는다")
				.isEqualByComparingTo("5000.00");
		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.extracting(OutboxEvent::getEventType)
				.as("송금을 실패로 종결시킬 이벤트가 나가야 한다")
				.contains(TransferEvents.FAILED);
	}
}
