package com.remittance.account.service;

import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.lock.AccountLockPolicy;
import com.remittance.account.lock.DistributedLock;
import com.remittance.account.messaging.AccountEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 잔액을 바꾸는 모든 경로가 거쳐야 하는 동시성 방어.
 *
 * 입출금 API, Kafka 컨슈머로 들어오는 Saga 단계, 개시 잔액 이월 — 셋이 같은 계좌를 동시에
 * 건드릴 수 있다. 방어가 한 경로에만 있으면 없는 것과 같으므로 전부 이 문으로 들어온다.
 *
 * 왜 {@link AccountService}에서 떼어냈나 (2026-09-11)
 * 원래 이 코드는 AccountService 안에 있었다. 그래서 Saga가 계좌 응용 서비스를 통째로
 * 주입받았는데, Saga가 알아야 할 것은 "잔액을 안전하게 바꾸는 법"이지 "계좌를 만들고
 * 조회하는 법"이 아니다. {@code guarded}가 {@code public}이었던 이유도 오직 바깥
 * 호출자 때문이었다 — 원래 여기 살 코드가 아니라는 신호다.
 *
 * 떼면서 락 전략 스위치(Phase 6.7)가 한 클래스로 모였다. 무엇을 재는 실험인지가
 * 주석이 아니라 타입에 드러난다. 대신 **클래스가 하나 늘었다** — 잔액 변경 한 건을
 * 따라가려면 이제 파일 두 개를 연다. 그게 이 분리의 대가다.
 *
 * 두 겹으로 지킨다
 *   1. 첫 겹 — {@link AccountLockPolicy}가 고른다. 정상 경로를 계좌 단위로 직렬화해
 *       애초에 충돌이 생기지 않게 한다. 무엇이 정말로 도움이 되는지를 숫자로 확인하려고
 *       스위치로 두었다(Phase 6 Step 1, Phase 6.7). 셋 중 하나다.
 *
 *       DISTRIBUTED  Redis 락으로 기다린다 (지금 기본값)
 *       PESSIMISTIC  DB 행 락으로 기다린다 — 기다리는 자리만 다르다
 *       OPTIMISTIC   안 기다리고 부딪히면 다시 한다 — 핫 계좌에서 무너진다(측정됨)
 *
 *   2. 둘째 겹 — 낙관적 락(@Version) + 재시도. 못 끈다. 다만 뜻이 전략마다 다르다 —
 *       DISTRIBUTED에서는 TTL로 락이 풀린 경우를 잡는 마지막 방어선이고,
 *       PESSIMISTIC에서는 충돌이 날 수 없으므로 0이어야 정상인 탐지기가 된다.
 *
 * 락은 변경하는 계좌 하나에만 건다. 범위를 넓히면 데드락과 처리량 저하로 이어진다.
 */
@Component
@RequiredArgsConstructor
public class BalanceGuard {

	private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

	/** 잔액 변경 한 건이 걸리는 시간보다 넉넉해야 한다 (자동 갱신이 없으므로). */
	private static final Duration LOCK_TTL = Duration.ofSeconds(3);
	/** 같은 계좌에 요청이 몰렸을 때 기다려보는 시간. */
	private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(3);

	/** 조각을 전부 다룬다는 표시. 출금과 조회가 쓴다. */
	public static final short ALL_SHARDS = -1;

	private final DistributedLock distributedLock;
	private final AccountLockPolicy lockPolicy;
	private final ShardRouter shardRouter;
	private final MeterRegistry meterRegistry;

	/** 잔액을 바꾸는 일. 어느 조각을 다룰지는 락을 잡으면서 정해지므로 인자로 받는다. */
	@FunctionalInterface
	public interface ShardedAction<T> {
		T run(short shardNo);
	}

	/**
	 * 조각을 전부 잠그고 실행한다. 잔액을 바꾸지는 않지만 합을 보고 판단하는 일
	 * (개시 잔액 이월)이 쓴다 — 판단하는 사이에 어느 조각이든 움직이면 안 된다.
	 */
	public <T> T guardedWhole(UUID accountId, Supplier<T> action) {
		return guarded(accountId, AccountEvents.TransactionDirection.DEBIT, shardNo -> action.get());
	}

	public <T> T guarded(UUID accountId, AccountEvents.TransactionDirection direction, ShardedAction<T> action) {
		boolean credit = direction == AccountEvents.TransactionDirection.CREDIT;
		// 어느 조각을 만질지는 락을 잡기 전에 정해져야 한다. 락 키에 그 번호가 들어가야
		// 조각별로 갈리기 때문이다. 계좌 하나에 락 하나면 조각을 나눠도 거기서 다시 줄을 선다.
		short shardNo = credit ? shardRouter.pickForCredit(accountId) : ALL_SHARDS;
		Supplier<T> guardedAction = () -> withOptimisticRetry(accountId, () -> action.run(shardNo));

		if (!lockPolicy.usesDistributedLock()) {
			// 여기로 오는 전략이 둘이고, 둘은 정반대다.
			//   OPTIMISTIC  — 아무것도 안 잠그고 부딪히면 처음부터 다시 한다.
			//   PESSIMISTIC — BalanceShards가 읽으면서 행 락을 잡는다. 즉 락이 사라진 게
			//                 아니라 트랜잭션 안으로 들어갔다. 여기서 할 일이 없을 뿐이다.
			return guardedAction.get();
		}
		return withLocks(lockKeys(accountId, shardNo), guardedAction);
	}

	/**
	 * 잡아야 할 락 키들.
	 *
	 *   - 입금 — 고른 조각 하나. 여기가 나란히 갈 수 있는 지점이다.
	 *   - 출금 — 전부. 합을 보고 여러 조각에서 빼므로 그 사이에 조각이
	 *       움직이면 안 된다. 쪼갠 이득이 출금에는 없다는 뜻이고, 이게 대가다.
	 *
	 * 조각이 하나인 계좌(대부분)는 두 경우가 같은 키 하나로 떨어진다 —
	 * 쪼개지 않은 계좌의 동작은 전과 완전히 같다.
	 */
	private List<String> lockKeys(UUID accountId, short shardNo) {
		if (shardNo != ALL_SHARDS) {
			return List.of(shardKey(accountId, shardNo));
		}
		short count = shardRouter.shardCount(accountId);
		List<String> keys = new ArrayList<>(count);
		for (short no = 0; no < count; no++) {
			keys.add(shardKey(accountId, no));
		}
		return keys;
	}

	private static String shardKey(UUID accountId, short shardNo) {
		return "lock:account:" + accountId + ":s" + shardNo;
	}

	/**
	 * 여러 락을 번호 순서대로 겹쳐 잡는다. 순서를 고정하는 것이 핵심이다 —
	 * 두 출금이 서로 반대 순서로 조각을 잡으면 교착에 빠진다.
	 * (락에 TTL 3초가 있어 영영 멈추지는 않지만, 3초씩 헛되이 버리게 된다.)
	 */
	private <T> T withLocks(List<String> keys, Supplier<T> action) {
		Supplier<T> nested = action;
		for (int i = keys.size() - 1; i >= 0; i--) {
			String key = keys.get(i);
			Supplier<T> inner = nested;
			nested = () -> distributedLock.executeWithLock(key, LOCK_TTL, LOCK_WAIT_TIMEOUT, inner);
		}
		return nested.get();
	}

	/**
	 * 충돌을 센다 (Phase 5 Step 2). 이 값이 0에서 뜨기 시작하면 분산 락이
	 * 막지 못한 경합이 실제로 있다는 뜻이다 — 락은 이 서비스 안에서만 유효하고,
	 * 낙관적 락은 그 바깥까지 막는 최후 안전망이라 둘의 차이가 여기 드러난다.
	 *
	 * {@code outcome=retried}는 다시 읽어 넘긴 것이고, {@code exhausted}는 끝내 포기한 것이다.
	 * retried가 늘어나는 건 견딜 만하지만 exhausted는 요청이 실패했다는 뜻이라 성격이 다르다.
	 */
	private <T> T withOptimisticRetry(UUID accountId, Supplier<T> action) {
		for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
			try {
				return action.get();
			} catch (ObjectOptimisticLockingFailureException e) {
				if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
					conflicts("exhausted").increment();
					throw new ConcurrentUpdateException(accountId);
				}
				conflicts("retried").increment();
			}
		}
		throw new ConcurrentUpdateException(accountId);
	}

	/**
	 * 충돌이 한 번도 없어도 0으로 보이게 미리 만들어 둔다 (Phase 5 Step 2).
	 *
	 * 카운터는 처음 증가할 때 생긴다. 그대로 두면 충돌이 없는 동안 시계열 자체가 없어서
	 * 화면에서 "충돌 0건"과 "수집이 안 되고 있다"가 똑같이 빈 칸으로 보인다.
	 * 정작 이 지표는 평소에 0인 게 정상이라, 0을 그릴 수 있어야 값어치가 있다.
	 */
	@PostConstruct
	void 충돌_카운터를_미리_만든다() {
		conflicts("retried");
		conflicts("exhausted");
	}

	private Counter conflicts(String outcome) {
		return Counter.builder("remittance.optimistic.lock.conflict")
				.description("낙관적 락 충돌 횟수")
				.tag("entity", "account")
				.tag("outcome", outcome)
				.register(meterRegistry);
	}
}
