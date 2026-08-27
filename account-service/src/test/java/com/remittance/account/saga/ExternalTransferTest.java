package com.remittance.account.saga;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.external.ExternalBankClient;
import com.remittance.account.external.ExternalCreditResult;
import com.remittance.account.external.ExternalCreditStatus;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 상대 은행으로 나가는 송금 (Phase 6.5 Step 2).
 *
 * <p>여기서 볼 것은 <b>원장이 두 다리를 그대로 받는가</b>이다.
 * 상대 계좌는 우리 원장에 적을 수 없으므로 <b>그 은행의 정산 계좌</b>로 적는다.
 *
 * <pre>
 *   고객 계좌  −50,000  ─┐
 *                        ├─ 우리 원장에 두 다리 (원장·대사 로직을 안 고쳐도 된다)
 *   KB 정산계좌 +50,000  ─┘
 * </pre>
 *
 * <p>상대 은행은 목이다. <b>여기서 확인할 것은 우리 쪽 처리</b>이고,
 * 상대가 실제로 어떻게 구는지는 {@code external-bank-service}의 테스트가 본다.
 */
@SpringBootTest
class ExternalTransferTest extends AbstractIntegrationTest {

	private static final String THEIR_ACCOUNT = "1234-5678";

	/**
	 * <b>테스트마다 다른 은행</b>을 쓴다. 정산 계좌는 은행당 하나라서, 코드를 고정하면
	 * 앞 테스트가 쌓아둔 잔액이 다음 테스트에 그대로 보인다 — 실제로 그렇게 깨졌다.
	 * 컨테이너 DB를 여러 테스트가 공유하므로 <b>테스트가 스스로 격리를 만들어야</b> 한다.
	 */
	private final String bank = "KB" + UUID.randomUUID().toString().substring(0, 8);

	@Autowired
	private TransferSagaService transferSagaService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@MockitoBean
	private ExternalBankClient externalBankClient;

	private UUID fundedAccount(long amount) {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(account.getAccountId(), BigDecimal.valueOf(amount), "KRW");
		return account.getAccountId();
	}

	private TransferEvents.Debited debited(UUID transferId, UUID from, long amount) {
		return new TransferEvents.Debited(transferId, from, null, bank, THEIR_ACCOUNT,
				BigDecimal.valueOf(amount), "KRW", BigDecimal.ZERO, Instant.now());
	}

	private Account settlementOf(String bankCode) {
		return accountRepository.findBySettlementBankCode(bankCode).orElseThrow(
				() -> new AssertionError("정산 계좌가 없다: " + bankCode));
	}

	@Test
	void 상대가_받으면_그_은행의_정산_계좌로_적힌다() {
		given(externalBankClient.credit(eq(bank), any(), eq(THEIR_ACCOUNT), any(), any()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null));
		UUID transferId = UUID.randomUUID();
		UUID from = fundedAccount(100_000);

		transferSagaService.onDebited(debited(transferId, from, 50_000));

		Account settlement = settlementOf(bank);
		assertThat(settlement.getAccountType())
				.as("고객 계좌가 아니라 우리 장부상의 자리다")
				.isEqualTo(AccountType.SETTLEMENT);
		assertThat(accountService.getBalance(settlement.getAccountId()).total())
				.as("상대 계좌를 우리 원장에 적을 수는 없지만, 그 은행에 지급할 채무는 우리 것이다")
				.isEqualByComparingTo("50000");
	}

	@Test
	void 정산_계좌는_은행당_하나만_만들어진다() {
		given(externalBankClient.credit(eq(bank), any(), any(), any(), any()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null));
		UUID from = fundedAccount(100_000);

		transferSagaService.onDebited(debited(UUID.randomUUID(), from, 10_000));
		transferSagaService.onDebited(debited(UUID.randomUUID(), from, 20_000));

		// 은행마다 답이 하나여야 "KB로 가는 돈은 어디에 쌓이나"에 답할 수 있다.
		assertThat(accountRepository.findAll().stream()
				.filter(account -> bank.equals(account.getSettlementBankCode())).count()).isEqualTo(1);
		assertThat(accountService.getBalance(settlementOf(bank).getAccountId()).total())
				.isEqualByComparingTo("30000");
	}

	@Test
	void 상대가_받으면_원장이_두_다리를_받도록_credited를_낸다() {
		given(externalBankClient.credit(eq(bank), any(), any(), any(), any()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null));
		UUID transferId = UUID.randomUUID();
		UUID from = fundedAccount(100_000);

		transferSagaService.onDebited(debited(transferId, from, 50_000));

		// 이 이벤트가 나가지 않으면 송금이 영영 COMPLETED가 되지 않는다.
		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.extracting(OutboxEvent::getEventType)
				.contains(TransferEvents.CREDITED);
	}

	@Test
	void 상대가_거절하면_보상으로_넘어간다() {
		given(externalBankClient.credit(eq(bank), any(), any(), any(), any()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.REJECTED, "수취 계좌를 찾을 수 없습니다"));
		UUID transferId = UUID.randomUUID();
		UUID from = fundedAccount(100_000);

		transferSagaService.onDebited(debited(transferId, from, 50_000));

		// 다시 보내도 결과가 같으므로 재시도가 아니라 환불로 가야 한다.
		// 출금은 이미 나갔으니 돌려놓지 않으면 고객 돈이 사라진다.
		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.extracting(OutboxEvent::getEventType)
				.containsExactly(TransferEvents.CREDIT_FAILED);
		assertThat(accountRepository.findBySettlementBankCode(bank))
				.as("거절당한 돈이 정산 계좌에 쌓이면 안 된다")
				.isEmpty();
	}

	@Test
	void 우리_은행_송금은_상대_은행을_부르지_않는다() {
		UUID transferId = UUID.randomUUID();
		UUID from = fundedAccount(100_000);
		UUID to = fundedAccount(0);

		transferSagaService.onDebited(TransferEvents.Debited.internal(
				transferId, from, to, BigDecimal.valueOf(50_000), "KRW", BigDecimal.ZERO, Instant.now()));

		verify(externalBankClient, org.mockito.Mockito.never()).credit(any(), any(), any(), any(), any());
		assertThat(accountService.getBalance(to).total()).isEqualByComparingTo("50000");
	}
}
