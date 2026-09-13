package com.remittance.reconciliation.service;

import com.remittance.reconciliation.client.AccountClient;
import com.remittance.reconciliation.client.LedgerClient;
import com.remittance.reconciliation.client.TransferClient;
import com.remittance.reconciliation.domain.FindingType;
import com.remittance.reconciliation.domain.ReconciliationFinding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 세 서비스에 물어보고 어긋난 것을 찾는다. 찾기만 한다 — 저장은 부르는 쪽이 한다.
 *
 * 왜 갈랐나 — 트랜잭션이 HTTP를 붙들고 있었다 ★ (2026-09-11)
 * 이 검사들은 원래 {@code @Transactional}이 걸린 {@code runOnce()} 안에 있었다.
 * 즉 DB 트랜잭션과 커넥션을 쥔 채로 세 서비스에 HTTP를 쳤다. 계좌가 늘수록 나빠지는
 * 구조다 — 호출 수가 {@code 2 x ceil(계좌수/pageSize) + 3}이고 각 호출의 read timeout이
 * 5초라, 계좌 1만 개면 최악 500초 넘게 트랜잭션이 열려 있게 된다.
 *
 * 이 규칙은 이 저장소가 이미 다른 곳에 ★를 붙여 적어둔 것이다 —
 * account-service의 {@code TransferSagaService.creditExternal} 참고.
 * *"느린 상대가 DB 트랜잭션을 붙들면 안 된다."* 상대 은행에 적용한 규칙이
 * 대사에는 안 걸려 있었다.
 *
 * 그래서 이 클래스에는 {@code @Transactional}이 없다. 리포지토리도 안 받는다 —
 * 받을 수 있으면 언젠가 쓰게 되고, 그러면 갈라놓은 이유가 무너진다.
 *
 * 대사는 계좌 쪽을 기준으로 훑는다. 원장을 기준으로 돌면 "계좌는 있는데 원장이 통째로 빈"
 * 경우를 못 잡는다 — 정작 그게 가장 흔한 사고다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationChecks {

	private final AccountClient accountClient;
	private final LedgerClient ledgerClient;
	private final TransferClient transferClient;
	private final ReconciliationProperties properties;

	/**
	 * 한 회차가 찾아낸 것.
	 *
	 * @param accountsChecked 대조한 계좌 수. 0건 발견이 "깨끗하다"인지 "아무것도 안 봤다"인지를
	 *                        가르는 값이라 발견 목록과 함께 다녀야 한다.
	 */
	public record Result(int accountsChecked, List<ReconciliationFinding> findings) {

		public boolean hasFindings() {
			return !findings.isEmpty();
		}

		public int findingCount() {
			return findings.size();
		}
	}

	/** 못 읽으면 예외가 그대로 올라간다 — 부르는 쪽이 회차를 실패로 남겨야 하기 때문이다. */
	public Result run(Long runId) {
		List<ReconciliationFinding> findings = new ArrayList<>();
		int accountsChecked = reconcileBalances(runId, findings);
		findUnsettledTransfers(runId, findings);
		findStrandedKeys(runId, findings);
		findUnknownExternalCredits(runId, findings);
		return new Result(accountsChecked, findings);
	}

	/** @return 대조한 계좌 수 */
	private int reconcileBalances(Long runId, List<ReconciliationFinding> findings) {
		int checked = 0;
		Long cursor = null;
		while (true) {
			AccountClient.BalancePage page = accountClient.balances(cursor, properties.pageSize());
			if (page == null || page.items().isEmpty()) {
				return checked;
			}

			List<UUID> accountIds = page.items().stream().map(AccountClient.Balance::accountId).toList();
			Map<UUID, BigDecimal> ledgerBalances = ledgerClient.balancesOf(accountIds);

			for (AccountClient.Balance account : page.items()) {
				checked++;
				BigDecimal fromLedger = LedgerClient.asLookup(ledgerBalances).apply(account.accountId());
				BigDecimal difference = account.balance().subtract(fromLedger);
				if (difference.abs().compareTo(properties.tolerance()) > 0) {
					findings.add(balanceMismatch(runId, account, fromLedger, difference));
				}
			}

			if (!page.hasNext()) {
				return checked;
			}
			cursor = page.nextCursor();
		}
	}

	private ReconciliationFinding balanceMismatch(Long runId, AccountClient.Balance account,
			BigDecimal fromLedger, BigDecimal difference) {
		return ReconciliationFinding.builder()
				.runId(runId)
				.type(FindingType.BALANCE_MISMATCH)
				.subject(account.accountId().toString())
				.detail("계좌 잔액 %s, 원장 합 %s, 차이 %s".formatted(
						account.balance().toPlainString(), fromLedger.toPlainString(),
						difference.toPlainString()))
				.detectedAt(Instant.now())
				.build();
	}

	private void findUnsettledTransfers(Long runId, List<ReconciliationFinding> findings) {
		List<TransferClient.UnsettledTransfer> unsettled =
				transferClient.unsettledTransfers(properties.unsettledAfter());
		if (unsettled == null) {
			return;
		}
		unsettled.forEach(transfer -> findings.add(ReconciliationFinding.builder()
				.runId(runId)
				.type(FindingType.UNSETTLED_TRANSFER)
				.subject(transfer.transferId().toString())
				.detail("%s 상태로 %s부터 멈춰 있다".formatted(transfer.status(), transfer.requestedAt()))
				.detectedAt(Instant.now())
				.build()));
	}

	/**
	 * 상대 은행에 보냈는데 오래 결론이 안 난 건들 (Phase 6.5).
	 *
	 * 왜 대사가 또 보나 — 이미 로그도 지표도 있는데
	 * 확인 루프는 오래된 건에 {@code ERROR} 로그를 남기고, 게이지는 지금 몇 건인지를 낸다.
	 * 둘 다 어느 건인지는 말해주지 않는다. 로그는 그 프로세스가 살아 있는 동안만 흐르고,
	 * 게이지는 숫자 하나다. 사람이 상대 은행에 연락하려면 송금 ID와 금액이 필요하고,
	 * 그건 회차별로 남는 대사 결과가 할 일이다.
	 *
	 * 같은 송금이 {@code UNSETTLED_TRANSFER}로도 잡힐 수 있다. 임계값이 다르므로
	 * (2분 vs 5분) 먼저 "흐름이 끊겼다"로 잡히고, 계속 안 풀리면 여기서 "상대 은행 건이고
	 * 돈이 나갔을 수 있다"가 더해진다. 같은 사실의 중복이 아니라 다른 사실이다 —
	 * 앞은 송금이 종결되지 않았다는 것이고, 뒤는 그 이유가 남의 시스템에 있다는 것이다.
	 */
	private void findUnknownExternalCredits(Long runId, List<ReconciliationFinding> findings) {
		List<AccountClient.UnknownExternalCredit> unknown =
				accountClient.unknownExternalCredits(properties.externalCreditUnknownAfter());
		if (unknown == null) {
			return;
		}
		unknown.forEach(credit -> findings.add(ReconciliationFinding.builder()
				.runId(runId)
				.type(FindingType.UNKNOWN_EXTERNAL_CREDIT)
				.subject(credit.transferId().toString())
				.detail("%s에 %s %s를 보냈는데 %s부터 결과를 모른다 (조회 %d회)".formatted(
						credit.bankCode(), credit.amount().toPlainString(), credit.currency(),
						credit.createdAt(), credit.inquiries()))
				.detectedAt(Instant.now())
				.build()));
	}

	private void findStrandedKeys(Long runId, List<ReconciliationFinding> findings) {
		List<TransferClient.StrandedKey> stranded =
				transferClient.strandedKeys(properties.keyStrandedAfter());
		if (stranded == null) {
			return;
		}
		stranded.forEach(key -> findings.add(ReconciliationFinding.builder()
				.runId(runId)
				.type(FindingType.STRANDED_IDEMPOTENCY_KEY)
				.subject(key.idempotencyKey())
				.detail(strandedKeyDetail(key))
				.detectedAt(Instant.now())
				.build()));
	}

	/**
	 * 묶인 키는 두 종류이고 대응이 정반대다. 뭉뚱그려 적으면 보는 사람이 매번 직접
	 * 캐봐야 하고, 그러다 접수된 송금을 못 봤다고 착각해 같은 송금을 두 번 보내게 된다.
	 */
	private String strandedKeyDetail(TransferClient.StrandedKey key) {
		if (key.committedTransferId() != null) {
			return "%s부터 IN_PROGRESS로 남아 있지만 접수는 커밋됐다 (transferId=%s). 재요청하면 그 송금을 돌려받는다"
					.formatted(key.createdAt(), key.committedTransferId());
		}
		return "%s부터 IN_PROGRESS로 남아 있고 접수가 커밋되지 않았다. 재요청하면 키가 풀리고 새로 접수된다"
				.formatted(key.createdAt());
	}
}
