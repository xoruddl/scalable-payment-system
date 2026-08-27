package com.remittance.account.external;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.saga.TransferSagaService;
import com.remittance.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 상대 은행이 <b>답하지 않았을 때</b> (Phase 6.5 Step 2b).
 *
 * <p>이 시스템에서 가장 불편한 상태다. 우리 고객 계좌는 이미 줄었는데 상대가 받았는지 모른다.
 * <b>실패로 처리하면 이미 나간 돈을 환불해 이중 지급</b>이 되고,
 * <b>성공으로 처리하면 안 간 돈을 갔다고 하는 셈</b>이다.
 *
 * <p>그래서 거는 계약은 셋이다.
 * <ol>
 *   <li>답이 없으면 <b>다시 보내지 않는다</b> — 재시도는 "안 갔다"를 전제로 하는데 그걸 모른다</li>
 *   <li>대신 <b>기록으로 남긴다</b> — 메모리에만 두면 재기동 한 번에 사라진다</li>
 *   <li>결론은 <b>조회로만</b> 낸다 — 그쪽 장부에 무엇이 적혔는지가 유일한 근거다</li>
 * </ol>
 */
@SpringBootTest
class UnknownCreditTest extends AbstractIntegrationTest {

	private static final String THEIR_ACCOUNT = "1234-5678";

	/** 테스트마다 다른 은행. 정산 계좌는 은행당 하나라 코드를 고정하면 잔액이 새어 든다. */
	private final String bank = "KB" + UUID.randomUUID().toString().substring(0, 8);

	@Autowired
	private TransferSagaService transferSagaService;

	@Autowired
	private ExternalCreditProber prober;

	@Autowired
	private ExternalCallBulkhead bulkhead;

	@Autowired
	private PendingExternalCreditRepository pending;

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

	private TransferEvents.Debited debited(UUID transferId, UUID from) {
		return new TransferEvents.Debited(transferId, from, null, bank, THEIR_ACCOUNT,
				BigDecimal.valueOf(50_000), "KRW", BigDecimal.ZERO, Instant.now());
	}

	private void bankDoesNotAnswer(UUID transferId) {
		willThrow(new ExternalCreditUnknownException(bank, transferId, new RuntimeException("timeout")))
				.given(externalBankClient).credit(eq(bank), eq(transferId), any(), any(), any());
	}

	/**
	 * 배경 스케줄러는 테스트에서 꺼져 있다. 확인할 때만 직접 부른다.
	 *
	 * <p><b>이 테스트의 건만</b> 본다. 컨테이너 DB를 여러 테스트가 공유하므로
	 * {@code findAll()}로 돌리면 남의 행까지 확인하려 든다 — 실제로 그렇게 깨졌다.
	 */
	private static boolean awaitQuietly(java.util.concurrent.CountDownLatch latch) {
		try {
			return latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void runProber(UUID transferId) {
		prober.inquireOne(pending.findById(transferId).orElseThrow());
	}

	@Test
	void 답이_없으면_다시_보내지_않고_기록으로_남긴다() {
		UUID transferId = UUID.randomUUID();
		bankDoesNotAnswer(transferId);

		transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));

		assertThat(pending.findById(transferId))
				.as("메모리에만 두면 재기동 한 번에 사라지고, 그러면 아무도 모르는 채로 끝난다")
				.isPresent();
		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.extracting(OutboxEvent::getEventType)
				.as("단순히 느린 건과 구분되려면 상태가 밖으로 나가야 한다")
				.containsExactly(TransferEvents.CREDIT_UNKNOWN);
		assertThat(accountRepository.findBySettlementBankCode(bank))
				.as("받았는지 모르는데 정산 계좌에 적으면 안 간 돈을 갔다고 하는 셈이다")
				.isEmpty();
	}

	@Test
	void 조회에서_받았다고_하면_그때_정산_계좌에_적는다() {
		UUID transferId = UUID.randomUUID();
		bankDoesNotAnswer(transferId);
		transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));

		given(externalBankClient.inquire(bank, transferId))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null));
		runProber(transferId);

		Account settlement = accountRepository.findBySettlementBankCode(bank).orElseThrow();
		assertThat(accountService.getBalance(settlement.getAccountId()).total())
				.as("확인된 뒤에야 적는다 — 그래야 원장이 거짓말을 하지 않는다")
				.isEqualByComparingTo("50000");
		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.extracting(OutboxEvent::getEventType)
				.as("확인이 늦었을 뿐, 흐름은 평소와 똑같이 이어진다")
				.contains(TransferEvents.CREDITED);
		assertThat(pending.findById(transferId))
				.as("결론이 났으면 더 물어볼 이유가 없다")
				.isEmpty();
	}

	@Test
	void 조회에서_거절이면_보상으로_넘어간다() {
		UUID transferId = UUID.randomUUID();
		bankDoesNotAnswer(transferId);
		transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));

		given(externalBankClient.inquire(bank, transferId))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.REJECTED, "수취 계좌 없음"));
		runProber(transferId);

		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.extracting(OutboxEvent::getEventType)
				.as("출금은 이미 나갔으니 되돌려야 한다")
				.contains(TransferEvents.CREDIT_FAILED);
		assertThat(pending.findById(transferId)).isEmpty();
	}

	@Test
	void 상대가_못_받았다고_확인해줬을_때만_다시_보낸다() {
		UUID transferId = UUID.randomUUID();
		bankDoesNotAnswer(transferId);
		transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));
		// 첫 호출(타임아웃)은 이미 있었다. 여기서부터 세기 위해 초기화한다.
		Mockito.clearInvocations(externalBankClient);

		given(externalBankClient.inquire(bank, transferId))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.NOT_FOUND, null));
		runProber(transferId);

		// 타임아웃 직후의 재전송과는 다르다 — 그때는 "안 갔다"가 추측이었고,
		// 지금은 상대가 확인해준 사실이다.
		verify(externalBankClient).credit(eq(bank), eq(transferId), any(), any(), any());
		assertThat(pending.findById(transferId))
				.as("보냈다고 결론이 난 것은 아니다. 결론은 늘 조회로만 낸다")
				.isPresent();
	}

	@Test
	void 격벽에_막히면_보내지_않은_것으로_기록하고_알리지_않는다() {
		// 격벽 정원을 다 채워둔다.
		java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(2);
		try (java.util.concurrent.ExecutorService pool =
				java.util.concurrent.Executors.newFixedThreadPool(2)) {
			for (int i = 0; i < 2; i++) {
				pool.submit(() -> bulkhead.call(() -> {
					occupied.countDown();
					try {
						hold.await(10, java.util.concurrent.TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return null;
				}));
			}
			assertThat(awaitQuietly(occupied)).isTrue();

			UUID transferId = UUID.randomUUID();
			transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));

			PendingExternalCredit saved = pending.findById(transferId).orElseThrow();
			assertThat(saved.isSent())
					.as("보내지도 못했다 — 돈은 안 나갔다")
					.isFalse();
			assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
					.as("안 보낸 건을 '모른다'고 알리면 없는 사고를 보고하는 것이다")
					.isEmpty();
			verify(externalBankClient, never()).credit(any(), eq(transferId), any(), any(), any());
			hold.countDown();
		}
	}

	@Test
	void 미전송_건은_조회하지_않고_보낸다() {
		java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(2);
		UUID transferId = UUID.randomUUID();
		try (java.util.concurrent.ExecutorService pool =
				java.util.concurrent.Executors.newFixedThreadPool(2)) {
			for (int i = 0; i < 2; i++) {
				pool.submit(() -> bulkhead.call(() -> {
					occupied.countDown();
					try {
						hold.await(10, java.util.concurrent.TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return null;
				}));
			}
			assertThat(awaitQuietly(occupied)).isTrue();
			transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));
			hold.countDown();
		}
		Mockito.clearInvocations(externalBankClient);
		given(externalBankClient.credit(eq(bank), eq(transferId), any(), any(), any()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null));

		runProber(transferId);

		// 조회부터 하면 반드시 NOT_FOUND가 나온다 — 왕복 한 번이 그냥 낭비다.
		verify(externalBankClient, never()).inquire(any(), eq(transferId));
		verify(externalBankClient).credit(eq(bank), eq(transferId), any(), any(), any());
		assertThat(pending.findById(transferId).orElseThrow().isSent())
				.as("보냈으면 이제부터는 조회로만 결론짓는다 — 아니면 이중 지급이 된다")
				.isTrue();
	}

	@Test
	void 조회에도_답이_없으면_그대로_두고_다음에_다시_묻는다() {
		UUID transferId = UUID.randomUUID();
		bankDoesNotAnswer(transferId);
		transferSagaService.onDebited(debited(transferId, fundedAccount(100_000)));
		Mockito.clearInvocations(externalBankClient);

		willThrow(new ExternalCreditUnknownException(bank, transferId, new RuntimeException("timeout")))
				.given(externalBankClient).inquire(bank, transferId);
		runProber(transferId);

		assertThat(pending.findById(transferId))
				.as("모르는 채로는 지우면 안 된다 — 지우는 순간 아무도 확인하지 않는다")
				.isPresent();
		assertThat(pending.findById(transferId).orElseThrow().getInquiries())
				.as("몇 번 물어봤는지가 오래 안 풀리는 건을 가리는 근거다")
				.isEqualTo(1);
		verify(externalBankClient, never()).credit(any(), any(), any(), any(), any());
	}

	@Test
	void 상대가_계속_답하지_않으면_호출을_멈추고_나머지는_미전송으로_남긴다() {
		given(externalBankClient.credit(eq(bank), any(), any(), any(), any()))
				.willThrow(new ExternalCreditUnknownException(
						bank, UUID.randomUUID(), new RuntimeException("timeout")));
		UUID from = fundedAccount(500_000);
		List<UUID> transferIds = IntStream.range(0, 10)
				.mapToObj(ignored -> UUID.randomUUID())
				.toList();

		transferIds.forEach(transferId -> transferSagaService.onDebited(debited(transferId, from)));

		verify(externalBankClient, times(5))
				.credit(eq(bank), any(), eq(THEIR_ACCOUNT), any(), any());
		assertThat(transferIds)
				.allSatisfy(transferId -> assertThat(pending.findById(transferId)).isPresent());
		assertThat(transferIds.stream()
				.map(transferId -> pending.findById(transferId).orElseThrow().isSent()))
				.as("실제로 호출한 5건만 보낸 상태이고, 회로가 막은 5건은 미전송이어야 한다")
				.containsExactly(true, true, true, true, true, false, false, false, false, false);
		assertThat(transferIds.stream()
				.flatMap(transferId -> outboxEventRepository
						.findByAggregateIdOrderByIdAsc(transferId).stream())
				.filter(event -> TransferEvents.CREDIT_UNKNOWN.equals(event.getEventType())))
				.as("처음 5건만 보냈다가 답을 못 받았다. 차단된 5건은 보내지 않았으므로 모르는 상태가 아니다")
				.hasSize(5);
	}
}
