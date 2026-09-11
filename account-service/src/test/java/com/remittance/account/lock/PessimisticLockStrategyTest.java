package com.remittance.account.lock;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 — 비관적 락으로 바꿔도 돈이 틀리지 않는가.
 *
 * 짝이 되는 시험이 둘 있다. {@code AccountLockStrategyTest}는 분산 락을 빼면 어떻게 되는지를
 * 보고, 거기서는 "성공한 만큼만 움직인다"까지만 걸었다 — 일부가 거절되는 것이 그 전략의
 * 알려진 대가였기 때문이다.
 *
 * 여기서는 더 세게 건다. 비관적 락은 부딪히면 기다리지 거절하지 않으므로
 * 20건이 전부 성공해야 한다. 하나라도 거절되면 그건 대가가 아니라 결함이다.
 *
 * 그리고 낙관적 락 충돌이 0이어야 한다. 행 락을 먼저 잡고 들어가면 두 스레드가 같은 버전을
 * 읽는 일 자체가 생길 수 없다. 0이 아니면 잠그지 않고 잔액을 만진 경로가 있다는 뜻이고,
 * 그게 이 전략에서 {@code @Version}을 남겨둔 이유다 — 방어선이 아니라 탐지기로.
 */
@SpringBootTest(properties = "account.lock.strategy=PESSIMISTIC")
class PessimisticLockStrategyTest extends AbstractIntegrationTest {

	private static final int THREADS = 20;
	private static final BigDecimal AMOUNT = BigDecimal.valueOf(100);

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountLockPolicy lockPolicy;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * 기다리는 시간의 상한이 실제로 걸려 있는가.
	 *
	 * InnoDB 기본값은 50초다. 그 값으로 돌면 붐비는 계좌 하나가 커넥션을 50초씩 물고 있어
	 * 풀이 마른다 — 이 전략의 유일한 실패 모드가 정확히 그것이다.
	 * 설정은 프로퍼티 한 줄이라 오타가 나도 조용히 넘어가므로, 값으로 확인한다.
	 */
	@Test
	void 행_락_대기_상한이_3초로_걸려_있다() {
		assertThat(jdbcTemplate.queryForObject("SELECT @@innodb_lock_wait_timeout", Integer.class))
				.as("connection-init-sql이 안 먹었다면 기본값 50이 나온다")
				.isEqualTo(3);
	}

	@Test
	void 전략이_PESSIMISTIC이면_분산_락을_잡지_않는다() {
		assertThat(lockPolicy.usesPessimisticLock()).isTrue();
		assertThat(lockPolicy.usesDistributedLock()).isFalse();
		long before = lockWaitCount();

		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(account.getAccountId(), AMOUNT, "KRW");

		assertThat(lockWaitCount())
				.as("행 락으로 가는데 Redis를 다녀왔다면 스위치가 안 먹은 것이다")
				.isEqualTo(before);
	}

	@Test
	void 같은_계좌에_몰려도_전부_성공하고_합이_맞는다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();
		long conflictsBefore = optimisticConflictCount();

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
					rejected.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(60, TimeUnit.SECONDS)).as("시간 안에 끝나야 한다").isTrue();
		executor.shutdownNow();

		assertThat(rejected.get())
				.as("비관적 락은 기다린다 — 거절이 나오면 대가가 아니라 결함이다")
				.isZero();
		assertThat(succeeded.get()).isEqualTo(THREADS);
		assertThat(accountService.getBalance(accountId).total())
				.isEqualByComparingTo(AMOUNT.multiply(BigDecimal.valueOf(THREADS)));
		assertThat(optimisticConflictCount() - conflictsBefore)
				.as("행 락을 잡고 들어갔는데 버전 충돌이 났다면, 잠그지 않고 잔액을 만진 경로가 있다")
				.isZero();
	}

	@Test
	void 출금과_입금이_뒤섞여도_합이_맞는다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();
		// 출금이 잔액 부족으로 실패하지 않도록 먼저 채운다. 여기서 재는 것은 잔액 규칙이
		// 아니라 동시성이다.
		accountService.credit(accountId, AMOUNT.multiply(BigDecimal.valueOf(THREADS)), "KRW");

		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		AtomicInteger failed = new AtomicInteger();

		for (int i = 0; i < THREADS; i++) {
			boolean credit = i % 2 == 0;
			executor.submit(() -> {
				try {
					start.await();
					// 출금은 전 조각을, 입금은 조각 하나를 잠근다. 잡는 범위가 겹치므로
					// 순서가 고정돼 있지 않으면 여기서 교착이 난다.
					if (credit) {
						accountService.credit(accountId, AMOUNT, "KRW");
						return;
					}
					accountService.debit(accountId, AMOUNT, "KRW");
				} catch (Exception e) {
					failed.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(60, TimeUnit.SECONDS)).as("교착에 빠지면 여기서 멈춘다").isTrue();
		executor.shutdownNow();

		assertThat(failed.get()).as("입출금이 같은 수라 전부 성공해야 한다").isZero();
		assertThat(accountService.getBalance(accountId).total())
				.as("넣은 만큼 뺐으므로 처음 채운 금액이 그대로 남아야 한다")
				.isEqualByComparingTo(AMOUNT.multiply(BigDecimal.valueOf(THREADS)));
	}

	private long lockWaitCount() {
		return meterRegistry.find("remittance.lock.wait").timers().stream()
				.mapToLong(timer -> timer.count())
				.sum();
	}

	private long optimisticConflictCount() {
		return meterRegistry.find("remittance.optimistic.lock.conflict").counters().stream()
				.mapToLong(counter -> (long) counter.count())
				.sum();
	}
}
