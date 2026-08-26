package com.remittance.account.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * 잔액 변경을 <b>무엇으로 지킬 것인가</b>를 고르는 스위치 (Phase 6 Step 1).
 *
 * <h2>왜 스위치인가</h2>
 * ROADMAP의 예상은 이렇다 — <b>"충돌이 적은 계좌는 분산 락을 빼면 빨라지고,
 * 핫 계좌는 오히려 나빠진다."</b> 맞는지 보려면 <b>같은 부하로 양쪽을 재야</b> 하는데,
 * 코드를 고쳐가며 재면 빌드가 달라져 비교가 흐려진다. <b>같은 jar를 프로퍼티만 바꿔 띄운다.</b>
 *
 * <p>그리고 이 예상이 맞으면 결론은 "어느 쪽이 낫다"가 아니라 <b>"계좌 성격에 따라 다르다"</b>가
 * 된다. 그때 이 클래스가 <b>계좌별로 전략을 고르는 자리</b>가 된다 — 지금은 전체에 하나를 쓴다.
 *
 * <h2>{@code /actuator/info}에 싣는 이유</h2>
 * 2026-08-22에 <b>낡은 jar로 baseline을 재고 전부 버린 적이 있다.</b> 그 뒤로 측정 전에
 * "떠 있는 게 어느 커밋인가"를 반드시 확인한다. 전략도 같다 — <b>어느 쪽을 재고 있는지
 * 물어볼 수 없으면, 나중에 그 숫자가 무엇이었는지 말할 수 없다.</b>
 */
@Component
public class AccountLockPolicy {

	private static final Logger log = LoggerFactory.getLogger(AccountLockPolicy.class);

	public enum Strategy {
		/**
		 * 분산 락(Redis)으로 계좌 단위 <b>직렬화</b> + 낙관적 락은 최후 안전망.
		 * 임계 구역이 JPA 트랜잭션 전체라 <b>보유 시간이 길다</b>(2026-08-23 실측 p50 38ms).
		 */
		DISTRIBUTED,

		/**
		 * 분산 락 없이 <b>낙관적 락 + 재시도</b>만. DB 행 락은 UPDATE부터 커밋까지만 잡으므로
		 * 경합 구간이 훨씬 짧다. 대신 <b>충돌하면 일을 처음부터 다시</b> 한다 —
		 * 같은 계좌에 몰릴수록 헛일이 늘어난다.
		 */
		OPTIMISTIC
	}

	private final Strategy strategy;

	public AccountLockPolicy(@Value("${account.lock.strategy:DISTRIBUTED}") Strategy strategy) {
		this.strategy = strategy;
		log.info("잔액 변경 보호 전략: {}", strategy);
	}

	public Strategy strategy() {
		return strategy;
	}

	public boolean usesDistributedLock() {
		return strategy == Strategy.DISTRIBUTED;
	}

	/** {@code /actuator/info}로 "지금 어느 전략으로 떠 있나"를 물어볼 수 있게 한다. */
	@Component
	static class Contributor implements InfoContributor {

		private final AccountLockPolicy policy;

		Contributor(AccountLockPolicy policy) {
			this.policy = policy;
		}

		@Override
		public void contribute(Info.Builder builder) {
			builder.withDetail("accountLockStrategy", policy.strategy().name());
		}
	}
}
