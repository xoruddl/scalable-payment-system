package com.remittance.account.service;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.AccountType;
import com.remittance.account.web.dto.AccountBalancePage;
import com.remittance.account.web.dto.AccountBalanceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사는 계좌를 <b>하나도 빠짐없이</b> 훑어야 한다. 한 계좌를 건너뛰면 그 계좌의 불일치는
 * 영영 발견되지 않는다.
 */
@SpringBootTest
class ReconciliationQueryServiceTest extends AbstractIntegrationTest {

	@Autowired
	private ReconciliationQueryService reconciliationQueryService;

	@Autowired
	private AccountService accountService;

	@Test
	void 커서로_끊어_읽어도_모든_계좌를_한_번씩_본다() {
		List<UUID> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL)
					.getAccountId());
		}

		List<UUID> seen = new ArrayList<>();
		Long cursor = null;
		while (true) {
			AccountBalancePage page = reconciliationQueryService.balances(cursor, 2);
			page.items().stream().map(AccountBalanceView::accountId).forEach(seen::add);
			if (!page.hasNext()) {
				break;
			}
			cursor = page.nextCursor();
		}

		assertThat(seen).containsAll(created);
		assertThat(seen).doesNotHaveDuplicates();
	}

	@Test
	void 잔액을_그대로_돌려준다() {
		UUID accountId = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL)
				.getAccountId();
		accountService.credit(accountId, BigDecimal.valueOf(5_000), "KRW");

		AccountBalancePage page = reconciliationQueryService.balances(null, 1000);

		assertThat(page.items())
				.filteredOn(view -> view.accountId().equals(accountId))
				.singleElement()
				.satisfies(view -> assertThat(view.balance()).isEqualByComparingTo("5000.00"));
	}
}
