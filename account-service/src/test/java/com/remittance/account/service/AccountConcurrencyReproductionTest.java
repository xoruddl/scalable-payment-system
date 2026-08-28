package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 Step 0 — 문제 재현 테스트 (현재는 실패한다).
 *
 * 같은 계좌에 출금이 동시에 몰리면, 현재는 낙관적 락 재시도({@code AccountService.withOptimisticRetry})에만
 * 의존하기 때문에 재시도 5회를 소진한 요청이 ConcurrentUpdateException으로 떨어진다.
 * 즉 "동시성이 높아지면 정상 요청이 실패한다".
 *
 * Step 2에서 Redis 분산 락으로 계좌별 출금/입금을 직렬화하면 green이 된다.
 */
@SpringBootTest
class AccountConcurrencyReproductionTest extends com.remittance.account.AbstractIntegrationTest {

	@Autowired
	private AccountService accountService;

	@Test
	void 동시_출금_시_실패_없이_잔액이_정확히_차감되어야_한다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();
		accountService.credit(accountId, BigDecimal.valueOf(10_000), "KRW");

		int threadCount = 20;
		BigDecimal debitAmount = BigDecimal.valueOf(100);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					start.await();
					accountService.debit(accountId, debitAmount, "KRW");
				} catch (Throwable t) {
					failures.add(t);
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		done.await(30, TimeUnit.SECONDS);
		executor.shutdownNow();

		assertThat(failures)
				.as("동시 출금 %d건이 실패 없이 모두 처리되어야 한다", threadCount)
				.isEmpty();
		assertThat(accountService.getBalance(accountId).total())
				.as("10000 - (100 × %d) = 8000 이어야 한다", threadCount)
				.isEqualByComparingTo("8000");
	}
}
