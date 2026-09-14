package com.remittance.account.lock;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalanceShard;
import com.remittance.account.domain.AccountType;
import com.remittance.account.repository.AccountBalanceShardRepository;
import com.remittance.account.service.AccountService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 — Redis 락과 행 락을 함께 쓰는 기본 전략(LAYERED).
 *
 * 프로퍼티 없이 뜬다. 기본값이 곧 운영 설정이다.
 *
 * 두 락이 맡는 일이 달라서 시험도 둘로 나눈다.
 *   - Redis 락이 줄을 세우는가 — 몰려도 전부 성공하고, 행 락에서 포기할 일이 없어야 한다
 *   - Redis 락이 없는 틈을 행 락이 막는가 — 이게 DISTRIBUTED와 다른 점이다.
 *     DISTRIBUTED에서는 그 틈을 {@code @Version}이 막았다(충돌 → 처음부터 다시).
 *     여기서는 행 락이 기다리게 하므로 충돌까지 가지 않아야 한다
 */
@SpringBootTest
class LayeredLockStrategyTest extends AbstractIntegrationTest {

	private static final int THREADS = 20;
	private static final BigDecimal AMOUNT = BigDecimal.valueOf(100);

	@Autowired
	private AccountService accountService;

	@Autowired
	private AccountLockPolicy lockPolicy;

	@Autowired
	private AccountBalanceShardRepository shardRepository;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void 기본_전략은_LAYERED이고_두_락을_모두_잡는다() {
		assertThat(lockPolicy.strategy()).isEqualTo(AccountLockPolicy.Strategy.LAYERED);
		assertThat(lockPolicy.usesDistributedLock()).isTrue();
		assertThat(lockPolicy.usesPessimisticLock()).isTrue();
		long before = redisLockCount();

		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		accountService.credit(account.getAccountId(), AMOUNT, "KRW");

		assertThat(redisLockCount())
				.as("Redis를 안 다녀왔다면 앞 겹이 빠진 것이다")
				.isGreaterThan(before);
	}

	@Test
	void 같은_계좌에_몰려도_전부_성공하고_행_락에서_기다릴_일이_없다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();
		long conflictsBefore = counterSum("remittance.optimistic.lock.conflict");
		long lockFailuresBefore = counterSum("remittance.balance.lock.failure");

		AtomicInteger rejected = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);

		for (int i = 0; i < THREADS; i++) {
			executor.submit(() -> {
				try {
					start.await();
					accountService.credit(accountId, AMOUNT, "KRW");
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

		assertThat(rejected.get()).as("두 겹 모두 기다린다 — 거절이 나오면 결함이다").isZero();
		assertThat(accountService.getBalance(accountId).total())
				.isEqualByComparingTo(AMOUNT.multiply(BigDecimal.valueOf(THREADS)));
		assertThat(counterSum("remittance.optimistic.lock.conflict") - conflictsBefore)
				.as("행 락 뒤에서 버전 충돌이 났다면 잠그지 않고 잔액을 만진 경로가 있다")
				.isZero();
		assertThat(counterSum("remittance.balance.lock.failure") - lockFailuresBefore)
				.as("Redis가 줄을 세웠다면 행 락에서 포기할 일이 없다")
				.isZero();
	}

	/**
	 * Redis 락이 먼저 사라진 상황(연장 실패 · 장애 전환 · 폴백)을 흉내 낸다. 다른 트랜잭션이 Redis를 거치지 않고 같은 행을
	 * 바꾸는 중이다(행 락을 쥔 채 1초). 이때 들어온 입금은 Redis 락은 바로 잡지만 행 락에서 기다리고,
	 * 앞 트랜잭션이 커밋한 뒤의 값을 읽는다 — 그래서 {@code @Version} 충돌까지 가지 않는다.
	 *
	 * DISTRIBUTED였다면 옛 버전을 읽고 쓰려다 충돌해 처음부터 다시 했을 것이다. 돈은 그래도 맞지만,
	 * 막는 자리가 정상 경로(기다림)가 아니라 예외 경로(충돌 → 재시도)다.
	 */
	@Test
	void Redis_락이_없는_틈은_행_락이_기다리게_해서_막는다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();
		AccountBalanceShard shard = shardRepository.findByAccountIdAndShardNo(accountId, (short) 0).orElseThrow();
		long conflictsBefore = counterSum("remittance.optimistic.lock.conflict");

		CountDownLatch locked = new CountDownLatch(1);
		ExecutorService holder = Executors.newSingleThreadExecutor();
		holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			// UPDATE가 행 락을 잡는다. Redis는 거치지 않는다 — Redis 락이 먼저 사라진 것과 같은 모양이다.
			jdbcTemplate.update(
					"UPDATE account_balance_shards SET balance = balance + 10, version = version + 1 WHERE id = ?",
					shard.getId());
			locked.countDown();
			sleepQuietly(Duration.ofSeconds(1));
		}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).as("행을 잠그지 못했다").isTrue();

		long startedAt = System.nanoTime();
		accountService.credit(accountId, AMOUNT, "KRW");
		long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		holder.shutdown();
		assertThat(holder.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

		assertThat(elapsedMs).as("행 락에서 앞 트랜잭션을 기다렸어야 한다").isGreaterThanOrEqualTo(500);
		assertThat(counterSum("remittance.optimistic.lock.conflict") - conflictsBefore)
				.as("행 락이 기다리게 했다면 옛 버전을 읽을 일이 없다 — 충돌은 0이어야 한다")
				.isZero();
		assertThat(accountService.getBalance(accountId).total()).isEqualByComparingTo("110");
	}

	private long redisLockCount() {
		return meterRegistry.find("remittance.lock.wait").timers().stream()
				.mapToLong(timer -> timer.count())
				.sum();
	}

	private long counterSum(String name) {
		return meterRegistry.find(name).counters().stream()
				.mapToLong(counter -> (long) counter.count())
				.sum();
	}

	private static void sleepQuietly(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
