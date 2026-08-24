package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.repository.AccountBalanceShardRepository;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.web.dto.AccountBalancePage;
import com.remittance.account.web.dto.AccountBalanceView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
}
