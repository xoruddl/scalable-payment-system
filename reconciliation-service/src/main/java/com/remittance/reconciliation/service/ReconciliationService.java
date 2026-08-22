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

	@Transactional
	public ReconciliationRun runOnce() {
		ReconciliationRun run = runRepository.save(new ReconciliationRun(Instant.now()));
		try {
			List<ReconciliationFinding> findings = new ArrayList<>();
			int accountsChecked = reconcileBalances(run.getId(), findings);
			findUnsettledTransfers(run.getId(), findings);
			findStrandedKeys(run.getId(), findings);

			findingRepository.saveAll(findings);
			run.complete(accountsChecked, findings.size(), Instant.now());
			if (!findings.isEmpty()) {
				log.warn("대사에서 어긋남을 찾았다 (runId={}, 계좌 {}건 확인, 발견 {}건)",
						run.getId(), accountsChecked, findings.size());
			}
			return run;
		} catch (RuntimeException e) {
			// 결과를 지우지 않고 실패로 남긴다. "깨끗했다"와 "못 읽었다"는 전혀 다른 얘기다.
			log.error("대사가 끝까지 돌지 못했다 (runId={})", run.getId(), e);
			run.fail(shortReason(e), Instant.now());
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
