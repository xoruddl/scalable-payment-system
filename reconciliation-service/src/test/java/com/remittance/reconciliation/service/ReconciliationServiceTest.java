package com.remittance.reconciliation.service;

import com.remittance.reconciliation.AbstractIntegrationTest;
import com.remittance.reconciliation.client.AccountClient;
import com.remittance.reconciliation.client.LedgerClient;
import com.remittance.reconciliation.client.TransferClient;
import com.remittance.reconciliation.domain.FindingType;
import com.remittance.reconciliation.domain.ReconciliationFinding;
import com.remittance.reconciliation.domain.ReconciliationRun;
import com.remittance.reconciliation.repository.ReconciliationFindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * Phase 2 Step 5b — 대사가 <b>어긋난 것을 실제로 찾아내는지</b>.
 *
 * <p>다른 서비스는 모킹한다. 여기서 검증할 것은 HTTP 배선이 아니라 <b>판단</b>이다 —
 * 무엇을 어긋남으로 볼지, 못 읽었을 때 어떻게 처신할지.
 */
@SpringBootTest
class ReconciliationServiceTest extends AbstractIntegrationTest {

	@Autowired
	private ReconciliationService reconciliationService;

	@Autowired
	private ReconciliationFindingRepository findingRepository;

	@MockitoBean
	private AccountClient accountClient;

	@MockitoBean
	private LedgerClient ledgerClient;

	@MockitoBean
	private TransferClient transferClient;

	private void noUnsettledWork() {
		given(transferClient.unsettledTransfers(any())).willReturn(List.of());
		given(transferClient.strandedKeys(any())).willReturn(List.of());
	}

	private void accountsReturn(AccountClient.Balance... balances) {
		given(accountClient.balances(any(), anyInt()))
				.willReturn(new AccountClient.BalancePage(List.of(balances), null, false));
	}

	private AccountClient.Balance account(UUID accountId, String balance) {
		return new AccountClient.Balance(accountId, new BigDecimal(balance), "KRW");
	}

	private List<ReconciliationFinding> findingsOf(ReconciliationRun run) {
		return findingRepository.findByRunIdOrderByIdAsc(run.getId());
	}

	@Test
	void 잔액과_원장_합이_같으면_아무것도_찾지_않는다() {
		UUID accountId = UUID.randomUUID();
		accountsReturn(account(accountId, "7000.00"));
		given(ledgerClient.balancesOf(any())).willReturn(Map.of(accountId, new BigDecimal("7000.00")));
		noUnsettledWork();

		ReconciliationRun run = reconciliationService.runOnce();

		assertThat(findingsOf(run)).isEmpty();
		assertThat(run.getAccountsChecked()).isEqualTo(1);
		assertThat(run.getFailureReason())
				.as("끝까지 돌았으면 실패 사유가 없어야 한다 — 0건과 '못 돌았다'는 다르다")
				.isNull();
	}

	/** 이게 대사의 본체다. 잔액 변경이 원장에 닿지 못하면 여기서 잡혀야 한다. */
	@Test
	void 원장에_빠진_돈이_있으면_금액_차이까지_찾아낸다() {
		UUID accountId = UUID.randomUUID();
		accountsReturn(account(accountId, "7000.00"));
		// 원장에는 1000원어치 줄이 닿지 못했다
		given(ledgerClient.balancesOf(any())).willReturn(Map.of(accountId, new BigDecimal("6000.00")));
		noUnsettledWork();

		ReconciliationRun run = reconciliationService.runOnce();

		assertThat(findingsOf(run))
				.singleElement()
				.satisfies(finding -> {
					assertThat(finding.getType()).isEqualTo(FindingType.BALANCE_MISMATCH);
					assertThat(finding.getSubject()).isEqualTo(accountId.toString());
					assertThat(finding.getDetail())
							.as("얼마가 어긋났는지 바로 보여야 손댈 수 있다")
							.contains("1000.00");
				});
	}

	/**
	 * 계좌는 있는데 원장이 통째로 빈 경우다. 원장을 기준으로 훑었다면 <b>존재조차 몰랐을</b> 계좌라,
	 * 대사가 계좌 쪽을 기준으로 도는 이유가 여기 있다.
	 */
	@Test
	void 원장이_통째로_비어_있어도_찾아낸다() {
		UUID accountId = UUID.randomUUID();
		accountsReturn(account(accountId, "5000.00"));
		given(ledgerClient.balancesOf(any())).willReturn(Map.of());
		noUnsettledWork();

		assertThat(findingsOf(reconciliationService.runOnce()))
				.singleElement()
				.extracting(ReconciliationFinding::getType)
				.isEqualTo(FindingType.BALANCE_MISMATCH);
	}

	@Test
	void 계좌가_여러_페이지여도_전부_훑는다() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		given(accountClient.balances(null, 2)).willReturn(
				new AccountClient.BalancePage(List.of(account(first, "100.00")), 1L, true));
		given(accountClient.balances(1L, 2)).willReturn(
				new AccountClient.BalancePage(List.of(account(second, "200.00")), null, false));
		given(ledgerClient.balancesOf(any())).willReturn(Map.of());
		noUnsettledWork();

		ReconciliationRun run = reconciliationService.runOnce();

		assertThat(run.getAccountsChecked())
				.as("첫 페이지만 보고 끝내면 뒤쪽 계좌의 불일치를 영영 못 본다")
				.isEqualTo(2);
		assertThat(findingsOf(run)).hasSize(2);
	}

	@Test
	void 종결되지_못한_송금을_찾아낸다() {
		UUID transferId = UUID.randomUUID();
		accountsReturn();
		given(ledgerClient.balancesOf(any())).willReturn(Map.of());
		given(transferClient.unsettledTransfers(any())).willReturn(List.of(
				new TransferClient.UnsettledTransfer(transferId, "COMPENSATING", Instant.now())));
		given(transferClient.strandedKeys(any())).willReturn(List.of());

		assertThat(findingsOf(reconciliationService.runOnce()))
				.singleElement()
				.satisfies(finding -> {
					assertThat(finding.getType()).isEqualTo(FindingType.UNSETTLED_TRANSFER);
					assertThat(finding.getDetail()).contains("COMPENSATING");
				});
	}

	@Test
	void 발이_묶인_멱등성_키를_찾아낸다() {
		accountsReturn();
		given(ledgerClient.balancesOf(any())).willReturn(Map.of());
		given(transferClient.unsettledTransfers(any())).willReturn(List.of());
		given(transferClient.strandedKeys(any())).willReturn(List.of(
				new TransferClient.StrandedKey("key-1", Instant.now(), null)));

		assertThat(findingsOf(reconciliationService.runOnce()))
				.singleElement()
				.extracting(ReconciliationFinding::getType)
				.isEqualTo(FindingType.STRANDED_IDEMPOTENCY_KEY);
	}

	/**
	 * Phase 2 Step 6b — 묶인 키는 두 종류이고 <b>대응이 정반대다.</b>
	 *
	 * <p>접수가 커밋된 키는 재요청하면 그 송금을 돌려받으므로 사실상 해결된 것이고,
	 * 커밋되지 않은 키는 재요청해야 비로소 풀린다. 뭉뚱그려 적으면 보는 사람이 매번 직접
	 * 캐봐야 하고, 접수된 송금을 못 봤다고 착각해 <b>같은 송금을 두 번 보낼 수 있다.</b>
	 */
	@Test
	void 묶인_키가_접수까지_갔는지를_구분해_보고한다() {
		accountsReturn();
		given(ledgerClient.balancesOf(any())).willReturn(Map.of());
		given(transferClient.unsettledTransfers(any())).willReturn(List.of());
		UUID committed = UUID.randomUUID();
		given(transferClient.strandedKeys(any())).willReturn(List.of(
				new TransferClient.StrandedKey("key-committed", Instant.now(), committed),
				new TransferClient.StrandedKey("key-uncommitted", Instant.now(), null)));

		assertThat(findingsOf(reconciliationService.runOnce()))
				.hasSize(2)
				.satisfiesExactlyInAnyOrder(
						finding -> {
							assertThat(finding.getSubject()).isEqualTo("key-committed");
							assertThat(finding.getDetail())
									.as("어느 송금으로 접수됐는지 보여야 재요청해도 되는지 판단할 수 있다")
									.contains(committed.toString())
									.contains("접수는 커밋됐다");
						},
						finding -> {
							assertThat(finding.getSubject()).isEqualTo("key-uncommitted");
							assertThat(finding.getDetail()).contains("커밋되지 않았다");
						});
	}

	/**
	 * 대사가 못 돌았는데 "발견 0건"으로 남으면, 보는 사람은 <b>깨끗하다고 오해한다.</b>
	 * 배치가 죽은 걸 정상으로 읽는 게 어긋남 자체보다 위험하다.
	 */
	@Test
	void 다른_서비스를_못_읽으면_회차를_실패로_남긴다() {
		willThrow(new ResourceAccessException("connection refused"))
				.given(accountClient).balances(any(), anyInt());

		ReconciliationRun run = reconciliationService.runOnce();

		assertThat(run.getFailureReason())
				.as("0건이라는 결과를 그대로 믿으면 안 된다는 표시가 남아야 한다")
				.contains("connection refused");
		assertThat(run.getFinishedAt()).isNotNull();
	}
}
