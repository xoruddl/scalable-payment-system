package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.web.dto.AccountBalancePage;
import com.remittance.account.web.dto.AccountBalanceView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 대사에 필요한 조회만 모아둔다. 잔액을 바꾸는 일은 여기서 하지 않는다. */
@Service
@RequiredArgsConstructor
public class ReconciliationQueryService {

	private final AccountRepository accountRepository;

	@Transactional(readOnly = true)
	public AccountBalancePage balances(Long cursor, int size) {
		// 다음 페이지가 있는지 보려고 한 건 더 읽는다
		List<Account> found = accountRepository.findByIdGreaterThanOrderByIdAsc(
				cursor != null ? cursor : 0L, Limit.of(size + 1));

		boolean hasNext = found.size() > size;
		List<Account> page = hasNext ? found.subList(0, size) : found;
		Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();

		return new AccountBalancePage(page.stream().map(AccountBalanceView::from).toList(),
				hasNext ? nextCursor : null, hasNext);
	}
}
