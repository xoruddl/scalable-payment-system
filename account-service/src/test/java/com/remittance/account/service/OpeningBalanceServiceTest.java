package com.remittance.account.service;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.AccountType;
import com.remittance.account.exception.StaleBalanceSnapshotException;
import com.remittance.account.exception.UnpublishedJournalException;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 Step 6a — <b>원장 도입 이전 잔액을 한 줄로 이월한다.</b>
 *
 * <p>Step 5b e2e에서 {@code BALANCE_MISMATCH} 17건이 계속 잡혔다. 전부 Step 5a 이전에 만들어진
 * 계좌라 <b>오탐이 아니라 정탐</b>이었고, 그래서 무시할 수도 지울 수도 없었다.
 * 이월은 그 과거를 원장에 적어 넣어 대사가 다시 의미를 갖게 하는 일이다.
 *
 * <p>여기서 가장 위험한 건 <b>잘못된 금액을 심는 것</b>이다. 이월분은 되돌릴 장치가 없어,
 * 한 번 어긋나면 그 계좌는 계속 어긋난 채로 남는다. 그래서 "심는다"보다
 * <b>"언제 심지 않는가"</b>를 더 많이 확인한다.
 */
@SpringBootTest
class OpeningBalanceServiceTest extends AbstractIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Autowired
	private OpeningBalanceService openingBalanceService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Step 5a 이전에 만들어진 계좌를 흉내낸다 — 잔액은 있는데 원장에는 아무것도 없는 상태다.
	 *
	 * <p>테스트에서는 릴레이가 꺼져 있어 분개가 Outbox에 그대로 쌓인다. 그걸 발행 처리해서
	 * "원장이 받아갈 건 다 받아갔고, 그럼에도 원장 합이 0인" 상황을 만든다.
	 */
	private UUID legacyAccount(String amount) {
		UUID accountId = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL)
				.getAccountId();
		accountService.credit(accountId, new BigDecimal(amount), "KRW");
		drainOutbox(accountId);
		return accountId;
	}

	/** 릴레이가 한 바퀴 돈 것과 같은 상태로 만든다. */
	private void drainOutbox(UUID accountId) {
		List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByIdAsc(accountId);
		events.forEach(OutboxEvent::markPublished);
		outboxEventRepository.saveAll(events);
	}

	private List<AccountEvents.BalanceChanged> journalOf(UUID accountId) {
		return outboxEventRepository.findByAggregateIdOrderByIdAsc(accountId).stream()
				.filter(event -> event.getEventType().equals(AccountEvents.BALANCE_CHANGED))
				.map(event -> objectMapper.readValue(event.getPayload(), AccountEvents.BalanceChanged.class))
				.toList();
	}

	private BigDecimal balanceOf(UUID accountId) {
		return accountService.getBalance(accountId).total();
	}

	@Test
	void 원장이_빈_옛_계좌는_잔액만큼_이월된다() {
		UUID accountId = legacyAccount("50000");

		OpeningBalanceResult result =
				openingBalanceService.carryForward(accountId, new BigDecimal("50000.00"), BigDecimal.ZERO);

		assertThat(result.outcome()).isEqualTo(OpeningBalanceResult.Outcome.SEEDED);
		assertThat(result.amount()).isEqualByComparingTo("50000.00");
		assertThat(journalOf(accountId))
				.last()
				.satisfies(entry -> {
					assertThat(entry.reason()).isEqualTo(AccountEvents.BalanceChangeReason.OPENING_BALANCE);
					assertThat(entry.direction()).isEqualTo(AccountEvents.TransactionDirection.CREDIT);
					assertThat(entry.amount()).isEqualByComparingTo("50000.00");
					assertThat(entry.transferId())
							.as("어느 송금 때문도 아니다 — 원장이 없던 시절 전체를 뭉뚱그린 한 줄이다")
							.isNull();
				});
	}

	/**
	 * 이월은 <b>과거를 적는 일이지 돈을 넣는 일이 아니다.</b> 잔액이 함께 늘면 그 순간
	 * 계좌에 없던 돈이 생기고, 대사를 맞추려던 작업이 진짜 사고가 된다.
	 */
	@Test
	void 이월해도_계좌_잔액은_그대로다() {
		UUID accountId = legacyAccount("50000");

		openingBalanceService.carryForward(accountId, new BigDecimal("50000.00"), BigDecimal.ZERO);

		assertThat(balanceOf(accountId)).isEqualByComparingTo("50000.00");
	}

	/** 이월분과 원래 분개를 더하면 잔액이 나와야 한다 — 대사가 하려는 계산이 그것이다. */
	@Test
	void 원장이_일부만_있는_계좌는_모자란_만큼만_이월된다() {
		UUID accountId = legacyAccount("50000");

		OpeningBalanceResult result = openingBalanceService.carryForward(
				accountId, new BigDecimal("50000.00"), new BigDecimal("20000.00"));

		assertThat(result.amount()).isEqualByComparingTo("30000.00");
		assertThat(new BigDecimal("20000.00").add(result.amount()))
				.as("원장 합 + 이월분 = 계좌 잔액")
				.isEqualByComparingTo(balanceOf(accountId));
	}

	@Test
	void 두_번_이월해도_분개는_한_줄만_남는다() {
		UUID accountId = legacyAccount("50000");
		openingBalanceService.carryForward(accountId, new BigDecimal("50000.00"), BigDecimal.ZERO);
		drainOutbox(accountId);

		OpeningBalanceResult second =
				openingBalanceService.carryForward(accountId, new BigDecimal("50000.00"), BigDecimal.ZERO);

		assertThat(second.outcome()).isEqualTo(OpeningBalanceResult.Outcome.ALREADY_CARRIED);
		assertThat(journalOf(accountId))
				.extracting(AccountEvents.BalanceChanged::reason)
				.as("두 번 심으면 그 액수만큼 원장이 잔액보다 커진다")
				.containsExactly(AccountEvents.BalanceChangeReason.DEPOSIT,
						AccountEvents.BalanceChangeReason.OPENING_BALANCE);
	}

	@Test
	void 이미_맞는_계좌에는_아무것도_심지_않는다() {
		UUID accountId = legacyAccount("50000");

		OpeningBalanceResult result = openingBalanceService.carryForward(
				accountId, new BigDecimal("50000.00"), new BigDecimal("50000.00"));

		assertThat(result.outcome()).isEqualTo(OpeningBalanceResult.Outcome.ALREADY_CONSISTENT);
		assertThat(journalOf(accountId))
				.extracting(AccountEvents.BalanceChanged::reason)
				.containsExactly(AccountEvents.BalanceChangeReason.DEPOSIT);
	}

	/**
	 * 잔액과 원장 합은 서로 다른 서비스에서 다른 순간에 읽은 값이다. 그 사이에 잔액이 움직였다면
	 * 계산해둔 차이는 이미 틀렸다 — 그대로 심으면 맞추려던 원장이 오히려 어긋난다.
	 */
	@Test
	void 잔액_스냅샷이_낡았으면_거절한다() {
		UUID accountId = legacyAccount("50000");
		accountService.credit(accountId, new BigDecimal("10000"), "KRW");
		drainOutbox(accountId);

		assertThatThrownBy(() -> openingBalanceService.carryForward(
				accountId, new BigDecimal("50000.00"), BigDecimal.ZERO))
				.isInstanceOf(StaleBalanceSnapshotException.class);

		assertThat(journalOf(accountId))
				.extracting(AccountEvents.BalanceChanged::reason)
				.doesNotContain(AccountEvents.BalanceChangeReason.OPENING_BALANCE);
	}

	/**
	 * 잔액 검사만으로는 못 잡는 경우다. <b>잔액은 그대로인데 원장만 뒤처져 있다.</b>
	 * 미발행 분개는 "잔액에는 이미 반영됐지만 원장은 아직 모르는 변경"이라,
	 * 지금 차이를 심으면 그 변경을 이월분에 한 번, 뒤늦게 도착한 분개에 또 한 번 세게 된다.
	 */
	@Test
	void 발행되지_않은_분개가_남아_있으면_거절한다() {
		UUID accountId = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL)
				.getAccountId();
		accountService.credit(accountId, new BigDecimal("50000"), "KRW");
		// drainOutbox를 하지 않는다 — 입금 분개가 아직 원장으로 가지 않은 상태.

		assertThatThrownBy(() -> openingBalanceService.carryForward(
				accountId, new BigDecimal("50000.00"), BigDecimal.ZERO))
				.isInstanceOf(UnpublishedJournalException.class);

		assertThat(journalOf(accountId))
				.extracting(AccountEvents.BalanceChanged::reason)
				.containsExactly(AccountEvents.BalanceChangeReason.DEPOSIT);
	}

	/** 반대 방향도 있다 — 원장이 잔액보다 많으면 빼는 쪽으로 이월해야 합이 맞는다. */
	@Test
	void 원장이_잔액보다_많으면_출금_방향으로_이월된다() {
		UUID accountId = legacyAccount("50000");

		OpeningBalanceResult result = openingBalanceService.carryForward(
				accountId, new BigDecimal("50000.00"), new BigDecimal("80000.00"));

		assertThat(result.amount()).isEqualByComparingTo("-30000.00");
		assertThat(journalOf(accountId))
				.last()
				.satisfies(entry -> {
					assertThat(entry.direction()).isEqualTo(AccountEvents.TransactionDirection.DEBIT);
					assertThat(entry.amount())
							.as("금액은 절댓값으로 남기고 방향으로 부호를 표현한다")
							.isEqualByComparingTo("30000.00");
				});
	}
}
