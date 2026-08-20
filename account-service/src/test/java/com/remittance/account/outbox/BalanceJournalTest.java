package com.remittance.account.outbox;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.saga.TransferSagaService;
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
 * Phase 2 Step 5a — <b>잔액이 움직였으면 예외 없이 분개장에 남는다.</b>
 *
 * <p>이게 이 단계의 전부이자 가장 깨지기 쉬운 계약이다. 잔액을 바꾸는 경로가 하나라도 분개를
 * 빠뜨리면 "원장 합 = 잔액"이 무너지고, 그 위에 세울 정합성 대사가 통째로 의미를 잃는다.
 * 실제로 Step 5a 전에는 송금만 원장에 남고 입출금 API와 보상 환불이 빠져 있었다.
 *
 * <p>그래서 <b>잔액이 움직이는 경로를 하나씩 짚어가며</b> 확인한다.
 */
@SpringBootTest
class BalanceJournalTest extends AbstractIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private TransferSagaService transferSagaService;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	private final BigDecimal amount = new BigDecimal("1000.00");

	private UUID account(String currency) {
		return accountService.createAccount(UUID.randomUUID(), currency, AccountType.PERSONAL).getAccountId();
	}

	private UUID fundedAccount() {
		UUID accountId = account("KRW");
		accountService.credit(accountId, BigDecimal.valueOf(5_000), "KRW");
		return accountId;
	}

	/** 이 계좌 앞으로 남은 분개 항목들. 파티션 키가 계좌 ID라 aggregateId로 찾는다. */
	private List<AccountEvents.BalanceChanged> journalOf(UUID accountId) {
		return outboxEventRepository.findByAggregateIdOrderByIdAsc(accountId).stream()
				.filter(event -> event.getEventType().equals(AccountEvents.BALANCE_CHANGED))
				.map(event -> objectMapper.readValue(event.getPayload(), AccountEvents.BalanceChanged.class))
				.toList();
	}

	@Test
	void 입금_API로_움직인_돈도_분개장에_남는다() {
		UUID accountId = account("KRW");

		accountService.credit(accountId, BigDecimal.valueOf(5_000), "KRW");

		assertThat(journalOf(accountId))
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.reason()).isEqualTo(AccountEvents.BalanceChangeReason.DEPOSIT);
					assertThat(entry.direction()).isEqualTo(AccountEvents.TransactionDirection.CREDIT);
					assertThat(entry.balanceAfter()).isEqualByComparingTo("5000.00");
					assertThat(entry.transferId())
							.as("송금과 무관한 변경이므로 송금 ID가 없다")
							.isNull();
				});
	}

	@Test
	void 출금_API로_움직인_돈도_분개장에_남는다() {
		UUID accountId = fundedAccount();

		accountService.debit(accountId, BigDecimal.valueOf(2_000), "KRW");

		assertThat(journalOf(accountId))
				.extracting(AccountEvents.BalanceChanged::reason)
				.containsExactly(AccountEvents.BalanceChangeReason.DEPOSIT,
						AccountEvents.BalanceChangeReason.WITHDRAWAL);
	}

	@Test
	void 송금_출금도_분개장에_남는다() {
		UUID from = fundedAccount();
		UUID to = account("KRW");
		UUID transferId = UUID.randomUUID();

		transferSagaService.onRequested(
				new TransferEvents.Requested(transferId, from, to, amount, "KRW"));

		assertThat(journalOf(from))
				.last()
				.satisfies(entry -> {
					assertThat(entry.reason()).isEqualTo(AccountEvents.BalanceChangeReason.TRANSFER_DEBIT);
					assertThat(entry.balanceAfter()).isEqualByComparingTo("4000.00");
					assertThat(entry.transferId())
							.as("송금 때문에 움직였으면 어느 송금인지 남아야 대사에서 짚어낼 수 있다")
							.isEqualTo(transferId);
				});
	}

	@Test
	void 송금_입금도_분개장에_남는다() {
		UUID from = fundedAccount();
		UUID to = account("KRW");
		UUID transferId = UUID.randomUUID();

		transferSagaService.onDebited(new TransferEvents.Debited(
				transferId, from, to, amount, "KRW", new BigDecimal("4000.00"), Instant.now()));

		assertThat(journalOf(to))
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.reason()).isEqualTo(AccountEvents.BalanceChangeReason.TRANSFER_CREDIT);
					assertThat(entry.balanceAfter()).isEqualByComparingTo("1000.00");
				});
	}

	/**
	 * 보상 환불이 빠지면 원장에는 나간 돈만 남고 돌아온 돈은 없어, 대사가 <b>있지도 않은 불일치</b>를
	 * 보고하게 된다. 되돌린 것도 사실이므로 남겨야 한다.
	 */
	@Test
	void 보상_환불도_분개장에_남아_합이_출금_전으로_돌아온다() {
		UUID from = fundedAccount();
		UUID to = account("USD");
		UUID transferId = UUID.randomUUID();

		transferSagaService.onRequested(new TransferEvents.Requested(transferId, from, to, amount, "KRW"));
		transferSagaService.onCreditFailed(new TransferEvents.CreditFailed(
				transferId, from, to, amount, "KRW", "통화가 일치하지 않습니다", Instant.now()));

		List<AccountEvents.BalanceChanged> journal = journalOf(from);
		assertThat(journal)
				.extracting(AccountEvents.BalanceChanged::reason)
				.containsExactly(AccountEvents.BalanceChangeReason.DEPOSIT,
						AccountEvents.BalanceChangeReason.TRANSFER_DEBIT,
						AccountEvents.BalanceChangeReason.TRANSFER_REFUND);
		assertThat(signedSum(journal))
				.as("분개 합이 계좌 잔액과 같아야 대사가 성립한다")
				.isEqualByComparingTo("5000.00");
	}

	/** 입금은 더하고 출금은 빼서, 원장만으로 잔액을 재구성한다 — 대사가 하려는 계산이다. */
	private BigDecimal signedSum(List<AccountEvents.BalanceChanged> journal) {
		return journal.stream()
				.map(entry -> entry.direction() == AccountEvents.TransactionDirection.CREDIT
						? entry.amount() : entry.amount().negate())
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
