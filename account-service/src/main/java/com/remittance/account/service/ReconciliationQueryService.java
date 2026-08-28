package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.external.PendingExternalCreditRepository;
import com.remittance.account.repository.AccountBalanceShardRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.support.Timestamps;
import com.remittance.account.web.dto.AccountBalancePage;
import com.remittance.account.web.dto.AccountBalanceView;
import com.remittance.account.web.dto.UnknownExternalCreditView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 대사에 필요한 조회만 모아둔다. 잔액을 바꾸는 일은 여기서 하지 않는다. */
@Service
@RequiredArgsConstructor
public class ReconciliationQueryService {

	private final AccountRepository accountRepository;
	private final AccountBalanceShardRepository shardRepository;
	private final PendingExternalCreditRepository pendingExternalCreditRepository;

	@Transactional(readOnly = true)
	public AccountBalancePage balances(Long cursor, int size) {
		// 다음 페이지가 있는지 보려고 한 건 더 읽는다
		List<Account> found = accountRepository.findByIdGreaterThanOrderByIdAsc(
				cursor != null ? cursor : 0L, Limit.of(size + 1));

		boolean hasNext = found.size() > size;
		List<Account> page = hasNext ? found.subList(0, size) : found;
		Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();

		// 계좌마다 조각을 따로 읽으면 페이지 크기만큼 쿼리가 나간다(N+1).
		// 대사는 계좌 전체를 훑으므로 그 차이가 그대로 대사 시간이 된다.
		Map<UUID, BigDecimal> totals = shardRepository
				.totalsOf(page.stream().map(Account::getAccountId).toList()).stream()
				.collect(Collectors.toMap(AccountBalanceShardRepository.Total::getAccountId,
						AccountBalanceShardRepository.Total::getTotal));

		List<AccountBalanceView> views = page.stream()
				.map(account -> new AccountBalanceView(account.getAccountId(),
						// 조각이 하나도 없는 계좌는 있을 수 없지만, 있다면 0이 아니라 드러나야 한다.
						totals.getOrDefault(account.getAccountId(), BigDecimal.ZERO),
						account.getCurrency()))
				.toList();

		return new AccountBalancePage(views, hasNext ? nextCursor : null, hasNext);
	}

	/**
	 * 상대 은행에 보냈는데 <b>오래 결론이 안 난</b> 건들 (Phase 6.5).
	 *
	 * <p>확인 루프가 스스로 못 푸는 건이 있다. 상대가 계속 답을 못 주면 조회는 영원히 돌고,
	 * 그동안 <b>고객 돈이 어디 있는지 아무도 모른다.</b> 그건 기계가 아니라 사람이 처리할 일이다.
	 *
	 * <p><b>{@code sent=true}만 본다.</b> 회로나 격벽에 막혀 못 보낸 건은 돈이 나가지 않았으므로
	 * 사람을 부를 일이 아니다 — 그건 상대가 살아나면 저절로 빠진다.
	 */
	@Transactional(readOnly = true)
	public List<UnknownExternalCreditView> unknownExternalCredits(Duration olderThan, int limit) {
		return pendingExternalCreditRepository
				.findBySentTrueAndCreatedAtBeforeOrderByCreatedAtAsc(
						Timestamps.now().minus(olderThan), Limit.of(limit))
				.stream().map(UnknownExternalCreditView::from).toList();
	}
}
