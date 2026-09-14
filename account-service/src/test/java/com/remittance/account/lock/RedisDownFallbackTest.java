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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis가 없어도 송금이 멈추지 않는가 (Phase 6.7, D-006).
 *
 * Redis 주소를 아무도 듣지 않는 포트로 바꿔 띄운다 — Redis가 통째로 죽은 것과 같은 모양이다.
 * 이때 기대하는 것은 셋이다.
 *   - 서비스가 뜬다 — Redis에 기동 때 붙지 않는다(lazy)
 *   - 입금이 된다 — 분산 락을 못 잡으면 행 락만으로 진행한다(폴백)
 *   - 몰려도 돈이 안 틀린다 — 줄 세우기가 빠져도 행 락이 직렬화한다
 *
 * 어떻게 "Redis가 없다"를 만드나
 * 처음에는 베이스가 넣는 Redis 주소(host · port)를 하위 클래스의 {@code @DynamicPropertySource}로
 * 덮어쓰려 했는데 먹지 않았다 — 진짜 Redis로 돌아 폴백이 0건이었고, 아래 단언이 그걸 잡았다.
 * 그래서 베이스가 건드리지 않는 키로 간다. Sentinel 주소 셋을 아무도 듣지 않는 포트로 준다 —
 * "Sentinel이 모두 죽었다"와 같고, 설정만으로 Sentinel 모드를 고르는지도 여기서 함께 확인된다.
 */
@SpringBootTest(properties = {
		"spring.data.redis.sentinel.master=remittance",
		"spring.data.redis.sentinel.nodes=127.0.0.1:1,127.0.0.1:2,127.0.0.1:3"
})
class RedisDownFallbackTest extends AbstractIntegrationTest {

	private static final int THREADS = 20;
	private static final BigDecimal AMOUNT = BigDecimal.valueOf(100);

	@Autowired
	private AccountService accountService;

	@Autowired
	private MeterRegistry meterRegistry;

	private double fallbacks() {
		return meterRegistry.find("remittance.balance.lock.fallback").counters().stream()
				.mapToDouble(counter -> counter.count())
				.sum();
	}

	private double optimisticConflicts() {
		return meterRegistry.find("remittance.optimistic.lock.conflict").counters().stream()
				.mapToDouble(counter -> counter.count())
				.sum();
	}

	@Test
	void Redis가_없어도_뜨고_입금은_행_락만으로_된다() {
		double before = fallbacks();
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);

		accountService.credit(account.getAccountId(), AMOUNT, "KRW");

		assertThat(accountService.getBalance(account.getAccountId()).total()).isEqualByComparingTo(AMOUNT);
		assertThat(fallbacks() - before)
				.as("Redis 없이 돌았다면 폴백이 세어져야 한다 — 0이면 진짜 Redis로 돈 것이다")
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	void Redis가_없어도_몰린_입금이_한_푼도_안_틀린다() throws Exception {
		Account account = accountService.createAccount(UUID.randomUUID(), "KRW", AccountType.PERSONAL);
		UUID accountId = account.getAccountId();
		double conflictsBefore = optimisticConflicts();

		AtomicInteger failed = new AtomicInteger();
		// 실패했다면 무엇 때문인지 단언 메시지에 싣는다 — 개수만 세면 원인을 다시 재현해야 한다.
		Set<String> causes = ConcurrentHashMap.newKeySet();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREADS);
		for (int i = 0; i < THREADS; i++) {
			executor.submit(() -> {
				try {
					start.await();
					accountService.credit(accountId, AMOUNT, "KRW");
				} catch (Exception e) {
					failed.incrementAndGet();
					causes.add(e.getClass().getName() + ": " + e.getMessage());
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(60, TimeUnit.SECONDS)).as("시간 안에 끝나야 한다").isTrue();
		executor.shutdownNow();

		assertThat(failed.get()).as("Redis가 없다고 송금이 실패하면 안 된다 — 원인: %s", causes).isZero();
		assertThat(accountService.getBalance(accountId).total())
				.isEqualByComparingTo(AMOUNT.multiply(BigDecimal.valueOf(THREADS)));
		assertThat(optimisticConflicts() - conflictsBefore)
				.as("행 락이 줄을 세웠다면 버전 충돌은 없어야 한다")
				.isZero();
	}
}
