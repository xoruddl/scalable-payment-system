package com.remittance.account.lock;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 Step 1 — 분산 락을 뺀 쪽이 <b>안전한가</b>를 먼저 본다.
 *
 * <p>ROADMAP의 예상은 "충돌이 적은 계좌는 빨라지고 핫 계좌는 나빠진다"이고, <b>빠르냐 느리냐는
 * 부하 시험이 답할 문제</b>다. 여기서 확인할 것은 그보다 앞선 질문이다 —
 * <b>분산 락을 빼도 돈이 틀리지 않는가.</b>
 *
 * <p>틀리지 않아야 하는 이유는 낙관적 락({@code @Version})이 남아 있기 때문이다.
 * 두 스레드가 같은 버전을 읽고 둘 다 쓰려 하면 <b>뒤쪽이 거절당한다.</b>
 * 거절당한 쪽은 다시 읽어 처음부터 하거나, 재시도를 다 쓰면 예외로 끝난다.
 *
 * <p>그래서 여기서 거는 것은 <b>"전부 성공한다"가 아니라 "성공한 만큼만 잔액이 움직인다"</b>다.
 * 일부가 실패하는 것은 이 전략의 <b>알려진 대가</b>이지 결함이 아니다 —
 * 같은 저장소의 {@code AccountConcurrencyReproductionTest}가 그걸 재현해 둔 것이고,
 * 분산 락은 원래 그 대가를 없애려고 넣은 것이다.
 *
 * <p><b>절대 허용되지 않는 것은 유실</b>이다. 성공했다고 응답했는데 잔액에 안 담기거나,
 * 실패한 것이 잔액에 담기면 그건 돈이 틀린 것이다.
 */
@SpringBootTest(properties = "account.lock.strategy=OPTIMISTIC")
class AccountLockStrategyTest extends AbstractIntegrationTest {

	private static final int THREADS = 20;
	private static final BigDecimal AMOUNT = BigDecimal.valueOf(100);

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountLockPolicy lockPolicy;

	@Autowired
	private MeterRegistry meterRegistry;

	@Test
	void 전략이_OPTIMISTIC이면_분산_락을_잡지_않는다() {
		assertThat(lockPolicy.usesDistributedLock()).isFalse();
		long before = lockWaitCount();

		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(account.getAccountId(), AMOUNT, "KRW");

		assertThat(lockWaitCount())
				.as("분산 락을 껐는데 Redis를 다녀왔다면 스위치가 안 먹은 것이다")
				.isEqualTo(before);
	}

	@Test
	void 충돌이_나도_성공한_만큼만_잔액이_움직인다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();

		AtomicInteger succeeded = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();

		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);

		for (int i = 0; i < THREADS; i++) {
			executor.submit(() -> {
				try {
					start.await();
					accountService.credit(accountId, AMOUNT, "KRW");
					succeeded.incrementAndGet();
				} catch (Exception e) {
					// 재시도를 다 쓰고 거절된 것. 이 전략의 알려진 대가다.
					rejected.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(60, TimeUnit.SECONDS)).as("시간 안에 끝나야 한다").isTrue();
		executor.shutdownNow();

		assertThat(succeeded.get() + rejected.get()).isEqualTo(THREADS);
		assertThat(accountService.getBalance(accountId).getBalance())
				.as("성공 %d건 · 거절 %d건 — 잔액은 성공한 만큼만 움직여야 한다",
						succeeded.get(), rejected.get())
				.isEqualByComparingTo(AMOUNT.multiply(BigDecimal.valueOf(succeeded.get())));
	}

	private long lockWaitCount() {
		return meterRegistry.find("remittance.lock.wait").timers().stream()
				.mapToLong(timer -> timer.count())
				.sum();
	}
}
