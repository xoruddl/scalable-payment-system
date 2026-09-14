package com.remittance.account.service;

import com.remittance.account.AbstractIntegrationTest;
import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalanceShard;
import com.remittance.account.domain.AccountType;
import com.remittance.account.lock.DistributedLock;
import com.remittance.account.repository.AccountBalanceShardRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 잔액 샤딩 — 쪼개도 돈이 틀리지 않는가, 그리고 쪼갠 것이 실제로 갈리는가.
 *
 * 빠른지는 여기서 답하지 않는다. 그건 홈서버 부하 시험의 몫이다.
 * 여기서 볼 것은 그보다 앞선 두 가지다 — 총액이 맞는가, 그리고 락이 조각별로 갈리는가.
 * 락이 안 갈리면 조각을 아무리 나눠도 거기서 다시 줄을 서므로 빨라질 수가 없다.
 *
 * 락이 둘이라 갈리는지도 두 번 본다 (Phase 6.7)
 * 기본 전략(LAYERED)은 Redis 락과 행 락을 둘 다 잡는다. Redis 쪽은 잡은 키를 세고,
 * 행 쪽은 실제로 조각 하나를 잠가 두고 입금이 비켜 가는지와 출금이 기다리는지를 본다.
 */
@SpringBootTest
class BalanceShardingTest extends AbstractIntegrationTest {

	private static final short SHARDS = 4;

	@Autowired
	private AccountService accountService;

	@Autowired
	private ShardingService shardingService;

	@Autowired
	private AccountBalanceShardRepository shardRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoSpyBean
	private DistributedLock distributedLock;

	/** 입금이 어느 조각으로 갈지를 시험에서 정하려고 감싼다. 무작위면 "잠긴 조각을 비켜 간다"를 못 잰다. */
	@MockitoSpyBean
	private ShardRouter shardRouter;

	private UUID shardedAccount() {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.BUSINESS);
		shardingService.widen(account.getAccountId(), SHARDS);
		return account.getAccountId();
	}

	private List<AccountBalanceShard> shardsOf(UUID accountId) {
		return shardRepository.findByAccountIdOrderByShardNoAsc(accountId);
	}

	@Test
	void 쪼개도_총액은_그대로다() {
		UUID accountId = shardedAccount();
		accountService.credit(accountId, BigDecimal.valueOf(1_000), "KRW");

		// 조각을 더 늘려도 돈이 생기지 않는다 — 넣을 자리를 늘리는 것이지 옮기는 게 아니다.
		shardingService.widen(accountId, (short) 8);

		assertThat(accountService.getBalance(accountId).total()).isEqualByComparingTo("1000");
		assertThat(shardsOf(accountId)).hasSize(8);
	}

	@Test
	void 조각은_줄일_수_없다() {
		UUID accountId = shardedAccount();

		// 없어지는 조각의 돈을 옮기는 절차가 없다. 조용히 받아주면 그 잔액이 조회에서 사라진다.
		assertThat(catchThrowable(() -> shardingService.widen(accountId, (short) 2)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 입금이_여러_조각으로_흩어진다() {
		UUID accountId = shardedAccount();

		for (int i = 0; i < 100; i++) {
			accountService.credit(accountId, BigDecimal.valueOf(10), "KRW");
		}

		// 무작위로 고르므로 100번이면 네 조각이 전부 쓰였을 확률이 사실상 1이다
		// (전부 안 쓰일 확률은 대략 4 × (3/4)^100 ≈ 1e-12).
		assertThat(shardsOf(accountId))
				.as("흩어지지 않으면 쪼갠 의미가 없다")
				.allSatisfy(shard -> assertThat(shard.getBalance()).isGreaterThan(BigDecimal.ZERO));
		assertThat(accountService.getBalance(accountId).total()).isEqualByComparingTo("1000");
	}

	@Test
	void 입금은_조각별로_다른_락을_잡는다() {
		UUID accountId = shardedAccount();

		for (int i = 0; i < 100; i++) {
			accountService.credit(accountId, BigDecimal.valueOf(10), "KRW");
		}

		// 이게 쪼개는 이유의 전부다. 락 키가 계좌 하나면 조각을 나눠도 거기서 다시 줄을 선다.
		assertThat(lockedKeysFor(accountId))
				.as("입금이 조각별 락으로 갈려야 나란히 갈 수 있다")
				.hasSize(SHARDS)
				.allSatisfy(key -> assertThat(key).startsWith("lock:account:" + accountId + ":s"));
	}

	@Test
	void 출금은_조각을_전부_잠근다() {
		UUID accountId = shardedAccount();
		for (int i = 0; i < 20; i++) {
			accountService.credit(accountId, BigDecimal.valueOf(100), "KRW");
		}
		clearLockKeys();

		accountService.debit(accountId, BigDecimal.valueOf(1_500), "KRW");

		// 합을 보고 여러 조각에서 빼므로 그 사이에 어느 조각도 움직이면 안 된다.
		// 쪼갠 이득이 출금에는 없다는 뜻이고, 이게 대가다.
		assertThat(lockedKeysFor(accountId)).hasSize(SHARDS);
	}

	/**
	 * 행 락도 조각별로 갈려야 한다. Redis 키가 갈려도 행 락이 계좌 단위면 트랜잭션 안에서
	 * 다시 줄을 선다. 0번 조각을 다른 트랜잭션이 쥐고 있어도 1번에 넣는 입금은 기다리지 않아야 한다.
	 */
	@Test
	void 입금은_잠긴_조각의_행을_비켜_간다() throws Exception {
		UUID accountId = shardedAccount();
		willReturn((short) 1).given(shardRouter).pickForCredit(accountId);

		long elapsedMs;
		try (RowLockHolder ignored = holdRowLock(accountId, (short) 0)) {
			long startedAt = System.nanoTime();
			accountService.credit(accountId, BigDecimal.valueOf(10), "KRW");
			elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		}

		assertThat(elapsedMs)
				.as("0번 조각의 행 락을 기다렸다면 대기 상한 3초 가까이 걸린다")
				.isLessThan(2_000);
		assertThat(shardsOf(accountId).get(1).getBalance()).isEqualByComparingTo("10");
	}

	/**
	 * 출금은 합을 보므로 조각 하나라도 행이 잠겨 있으면 들어가지 못하고, 대기 상한 3초를 넘기면
	 * 포기한다. 잠근 쪽이 Redis를 거치지 않았으므로 출금은 Redis 락은 잡는다 — 막는 것은 행 락이다.
	 */
	@Test
	void 출금은_조각의_행이_하나라도_잠겨_있으면_기다린다() throws Exception {
		UUID accountId = shardedAccount();
		for (int i = 0; i < 8; i++) {
			accountService.credit(accountId, BigDecimal.valueOf(100), "KRW");
		}

		try (RowLockHolder ignored = holdRowLock(accountId, (short) (SHARDS - 1))) {
			assertThatThrownBy(() -> accountService.debit(accountId, BigDecimal.valueOf(100), "KRW"))
					.as("마지막 조각의 행이 잠겨 있으면 출금은 거기서 막혀야 한다")
					.isInstanceOf(PessimisticLockingFailureException.class);
		}

		assertThat(accountService.getBalance(accountId).total())
				.as("포기한 출금은 아무것도 움직이지 않았어야 한다")
				.isEqualByComparingTo("800");
	}

	@Test
	void 한_조각에는_모자라도_합이_되면_출금된다() {
		UUID accountId = shardedAccount();
		for (int i = 0; i < 8; i++) {
			accountService.credit(accountId, BigDecimal.valueOf(100), "KRW");
		}

		// 조각 하나만 보고 거절하면 "잔액이 있는데 실패하는" 일이 생긴다.
		accountService.debit(accountId, BigDecimal.valueOf(700), "KRW");

		assertThat(accountService.getBalance(accountId).total()).isEqualByComparingTo("100");
		assertThat(shardsOf(accountId)).allSatisfy(
				shard -> assertThat(shard.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
	}

	@Test
	void 동시에_입금해도_한_푼도_안_틀린다() throws Exception {
		UUID accountId = shardedAccount();
		int threads = 24;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						start.await();
						accountService.credit(accountId, BigDecimal.valueOf(100), "KRW");
					} catch (Exception ignored) {
						// 실패는 여기서 세지 않는다. 아래에서 잔액으로 확인한다.
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
		}

		// 조각을 나눠도 합은 반드시 맞아야 한다. 여기가 틀리면 샤딩은 쓸 수 없다.
		assertThat(accountService.getBalance(accountId).total()).isEqualByComparingTo("2400");
	}

	private Set<String> lockedKeysFor(UUID accountId) {
		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(distributedLock, atLeastOnce())
				.executeWithLock(keys.capture(), any(Duration.class), any(Duration.class), any());
		return keys.getAllValues().stream()
				.filter(key -> key.startsWith("lock:account:" + accountId))
				.collect(Collectors.toSet());
	}

	private void clearLockKeys() {
		org.mockito.Mockito.clearInvocations(distributedLock);
	}

	/** 다른 트랜잭션이 Redis를 거치지 않고 조각 하나의 행을 잠그고 쥐고 있다. 닫으면 커밋해 놓는다. */
	private RowLockHolder holdRowLock(UUID accountId, short shardNo) throws InterruptedException {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService holder = Executors.newSingleThreadExecutor();
		holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			shardRepository.findForUpdateByAccountIdAndShardNo(accountId, shardNo);
			locked.countDown();
			awaitQuietly(release);
		}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).as("조각을 잠그지 못했다").isTrue();
		return new RowLockHolder(release, holder);
	}

	private record RowLockHolder(CountDownLatch release, ExecutorService holder) implements AutoCloseable {

		@Override
		public void close() throws InterruptedException {
			release.countDown();
			holder.shutdown();
			holder.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static Throwable catchThrowable(Runnable runnable) {
		try {
			runnable.run();
			return null;
		} catch (Throwable throwable) {
			return throwable;
		}
	}
}
