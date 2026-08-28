package com.remittance.reconciliation.service;

import com.remittance.reconciliation.client.AccountClient;
import com.remittance.reconciliation.client.LedgerClient;
import com.remittance.reconciliation.client.TransferClient;
import com.remittance.reconciliation.domain.FindingType;
import com.remittance.reconciliation.domain.ReconciliationFinding;
import com.remittance.reconciliation.domain.ReconciliationRun;
import com.remittance.reconciliation.repository.ReconciliationFindingRepository;
import com.remittance.reconciliation.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 대사 한 회차를 돈다 — 세 서비스에 물어보고 어긋난 것을 찾아 기록한다.
 *
 * <p><b>고치지 않는다.</b> 여기서 잔액을 맞추거나 송금을 종결시키면, 원인을 모르는 채 증상만
 * 지우는 꼴이 된다. 게다가 남의 서비스 데이터를 바꾸는 순간 서비스 경계가 무너진다.
 * 찾아서 남기는 데까지가 이 서비스의 일이다.
 *
 * <p>대사는 <b>계좌 쪽을 기준으로</b> 훑는다. 원장을 기준으로 돌면 "계좌는 있는데 원장이 통째로 빈"
 * 경우를 못 잡는다 — 정작 그게 가장 흔한 사고다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private final AccountClient accountClient;
	private final LedgerClient ledgerClient;
	private final TransferClient transferClient;
	private final ReconciliationRunRepository runRepository;
	private final ReconciliationFindingRepository findingRepository;
	private final ReconciliationProperties properties;
	private final ReconciliationMetrics metrics;

	@Transactional
	public ReconciliationRun runOnce() {
		ReconciliationRun run = runRepository.save(new ReconciliationRun(Instant.now()));
		try {
			List<ReconciliationFinding> findings = new ArrayList<>();
			int accountsChecked = reconcileBalances(run.getId(), findings);
			findUnsettledTransfers(run.getId(), findings);
			findStrandedKeys(run.getId(), findings);
			findUnknownExternalCredits(run.getId(), findings);

			findingRepository.saveAll(findings);
			run.complete(accountsChecked, findings.size(), Instant.now());
			if (!findings.isEmpty()) {
				log.warn("대사에서 어긋남을 찾았다 (runId={}, 계좌 {}건 확인, 발견 {}건)",
						run.getId(), accountsChecked, findings.size());
			}
			metrics.record(run, findings);
			return run;
		} catch (RuntimeException e) {
			// 결과를 지우지 않고 실패로 남긴다. "깨끗했다"와 "못 읽었다"는 전혀 다른 얘기다.
			log.error("대사가 끝까지 돌지 못했다 (runId={})", run.getId(), e);
			run.fail(shortReason(e), Instant.now());
			// 실패한 회차도 알린다. 이걸 빼면 배치가 계속 실패하는 동안 지표는 마지막 성공
			// 회차의 값에 멈춰 있어, 화면상으로는 아무 일도 없는 것처럼 보인다.
			metrics.record(run, List.of());
			return run;
		}
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
	 * 상대 은행에 <b>보냈는데</b> 오래 결론이 안 난 건들 (Phase 6.5).
	 *
	 * <h2>왜 대사가 또 보나 — 이미 로그도 지표도 있는데</h2>
	 * 확인 루프는 오래된 건에 {@code ERROR} 로그를 남기고, 게이지는 <b>지금 몇 건인지</b>를 낸다.
	 * 둘 다 <b>어느 건인지는 말해주지 않는다.</b> 로그는 그 프로세스가 살아 있는 동안만 흐르고,
	 * 게이지는 숫자 하나다. 사람이 상대 은행에 연락하려면 <b>송금 ID와 금액</b>이 필요하고,
	 * 그건 회차별로 남는 대사 결과가 할 일이다.
	 *
	 * <p><b>같은 송금이 {@code UNSETTLED_TRANSFER}로도 잡힐 수 있다.</b> 임계값이 다르므로
	 * (2분 vs 5분) 먼저 "흐름이 끊겼다"로 잡히고, 계속 안 풀리면 여기서 "상대 은행 건이고
	 * 돈이 나갔을 수 있다"가 더해진다. <b>같은 사실의 중복이 아니라 다른 사실</b>이다 —
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
	 * 묶인 키는 두 종류이고 <b>대응이 정반대다.</b> 뭉뚱그려 적으면 보는 사람이 매번 직접
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

	private String shortReason(RuntimeException e) {
		String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
		return reason.length() > 500 ? reason.substring(0, 500) : reason;
	}
}
