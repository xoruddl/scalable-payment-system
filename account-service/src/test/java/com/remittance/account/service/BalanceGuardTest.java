package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.lock.AccountLockPolicy;
import com.remittance.account.lock.DistributedLock;
import com.remittance.account.messaging.AccountEvents;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 낙관적 락 재시도 검증. 원래 {@code AccountServiceTest}에 있었는데
 * 검증 대상이 {@link BalanceGuard}로 옮겨가면서 따라왔다 (2026-09-11).
 *
 * 이 재시도는 입출금 API만의 것이 아니다 — Saga 단계와 개시 잔액 이월도 같은 문을 지난다.
 * 계좌 서비스 테스트에 남겨두면 "입출금 API의 재시도"처럼 읽혀서 범위를 오해하게 된다.
 *
 * 행 락 자체는 여기서 보지 않는다. 락은 {@link BalanceShards}가 조각을 읽으면서 잡으므로
 * 진짜 InnoDB가 필요하다 — {@code BalanceShardingTest}와 {@code LayeredLockStrategyTest}의 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class BalanceGuardTest {

	@Mock
	private DistributedLock distributedLock;

	@Mock
	private AccountLockPolicy lockPolicy;

	@Mock
	private ShardRouter shardRouter;

	/**
	 * 메트릭은 목이 아니라 진짜 레지스트리를 쓴다. 목으로 두면 "increment()가 불렸다"까지만
	 * 확인하게 되는데, 정작 알고 싶은 건 어떤 태그로 몇이 찍혔나이다.
	 */
	@Spy
	private MeterRegistry meterRegistry = new SimpleMeterRegistry();

	@InjectMocks
	private BalanceGuard balanceGuard;

	private double conflictCount(String outcome) {
		return meterRegistry.find("remittance.optimistic.lock.conflict")
				.tag("entity", "account").tag("outcome", outcome)
				.counters().stream().mapToDouble(counter -> counter.count()).sum();
	}

	private double counterSum(String name) {
		return meterRegistry.find(name).counters().stream().mapToDouble(counter -> counter.count()).sum();
	}

	/**
	 * 분산 락 전략으로 두되, 락 자체는 여기서 검증 대상이 아니므로 그냥 통과시켜
	 * 원래 동작을 실행하게 한다.
	 *
	 * 전략을 명시하는 이유: 기본값을 안 정해두면 목이 {@code false}를 돌려주어
	 * 낙관적 락 경로로 새는데, 그러면 이 클래스의 재시도 검증들이 무엇을 재는지 흐려진다.
	 */
	@SuppressWarnings("unchecked")
	private void passThroughLock() {
		given(lockPolicy.usesDistributedLock()).willReturn(true);
		given(distributedLock.executeWithLock(any(), any(), any(), any()))
				.willAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(3)).get());
	}

	/** 앞의 {@code failures}번은 낙관적 락 충돌로 실패하고 그다음에 성공하는 잔액 변경. */
	private BalanceGuard.ShardedAction<String> failingTimes(int failures, AtomicInteger attempts) {
		return shardNo -> {
			if (attempts.getAndIncrement() < failures) {
				throw new ObjectOptimisticLockingFailureException(Account.class, UUID.randomUUID());
			}
			return "성공";
		};
	}

	@Test
	void 낙관적_락_충돌시_재조회_후_재시도한다() {
		passThroughLock();
		AtomicInteger attempts = new AtomicInteger();

		String result = balanceGuard.guarded(UUID.randomUUID(),
				AccountEvents.TransactionDirection.CREDIT, failingTimes(2, attempts));

		assertThat(result).isEqualTo("성공");
		assertThat(attempts).hasValue(3);
		// 충돌이 두 번 났고 둘 다 재시도로 넘겼다. 이 값이 0에서 뜨기 시작하면
		// 첫 겹이 막지 못한 경합이 실제로 있다는 뜻이다 (Phase 5 Step 2).
		assertThat(conflictCount("retried")).isEqualTo(2);
		assertThat(conflictCount("exhausted")).isZero();
	}

	@Test
	void 재시도를_모두_소진하면_예외() {
		passThroughLock();
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> balanceGuard.guarded(UUID.randomUUID(),
				AccountEvents.TransactionDirection.CREDIT, failingTimes(Integer.MAX_VALUE, attempts)))
				.isInstanceOf(ConcurrentUpdateException.class);

		assertThat(attempts).hasValue(5);
		// 마지막 한 번은 성격이 다르다 — 재시도로 넘긴 게 아니라 요청이 실패한 것이다.
		assertThat(conflictCount("exhausted")).isEqualTo(1);
		assertThat(conflictCount("retried")).isEqualTo(4);
	}

	/**
	 * 행 락을 못 잡은 것은 여기서 재시도하지 않는다. 트랜잭션이 이미 락 대기로 3초를 썼고,
	 * 다시 하는 것은 호출부의 몫이다(컨슈머는 횟수 제한 없이, REST는 409로 돌려준다).
	 * 여기서 할 일은 세는 것뿐이다 — Redis 락의 타임아웃 지표와 짝을 이룬다.
	 */
	@Test
	void 행_락을_못_잡으면_세고_그대로_던진다() {
		passThroughLock();
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> balanceGuard.guarded(UUID.randomUUID(),
				AccountEvents.TransactionDirection.CREDIT, shardNo -> {
					attempts.incrementAndGet();
					throw new CannotAcquireLockException("Lock wait timeout exceeded");
				}))
				.isInstanceOf(CannotAcquireLockException.class);

		assertThat(attempts).as("여기서 다시 하면 락 대기 3초를 한 번 더 쓴다").hasValue(1);
		assertThat(counterSum("remittance.balance.lock.failure")).isEqualTo(1);
	}
}
