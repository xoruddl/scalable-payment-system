package com.remittance.account.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * 잔액 변경을 무엇으로 지킬 것인가를 고르는 스위치 (Phase 6 Step 1).
 *
 * 왜 스위치인가
 * ROADMAP의 예상은 이렇다 — "충돌이 적은 계좌는 분산 락을 빼면 빨라지고,
 * 핫 계좌는 오히려 나빠진다." 맞는지 보려면 같은 부하로 양쪽을 재야 하는데,
 * 코드를 고쳐가며 재면 빌드가 달라져 비교가 흐려진다. 같은 jar를 프로퍼티만 바꿔 띄운다.
 *
 * 그리고 이 예상이 맞으면 결론은 "어느 쪽이 낫다"가 아니라 "계좌 성격에 따라 다르다"가
 * 된다. 그때 이 클래스가 계좌별로 전략을 고르는 자리가 된다 — 지금은 전체에 하나를 쓴다.
 *
 * {@code /actuator/info}에 싣는 이유
 * 2026-08-22에 낡은 jar로 baseline을 재고 전부 버린 적이 있다. 그 뒤로 측정 전에
 * "떠 있는 게 어느 커밋인가"를 반드시 확인한다. 전략도 같다 — 어느 쪽을 재고 있는지
 * 물어볼 수 없으면, 나중에 그 숫자가 무엇이었는지 말할 수 없다.
 */
@Component
public class AccountLockPolicy {

	private static final Logger log = LoggerFactory.getLogger(AccountLockPolicy.class);

	public enum Strategy {
		/**
		 * 분산 락(Redis)으로 계좌 단위 직렬화 + 낙관적 락은 최후 안전망.
		 * 임계 구역이 JPA 트랜잭션 전체라 보유 시간이 길다(2026-08-23 실측 p50 38ms).
		 */
		DISTRIBUTED,

		/**
		 * 분산 락 없이 낙관적 락 + 재시도만. DB 행 락은 UPDATE부터 커밋까지만 잡으므로
		 * 경합 구간이 훨씬 짧다. 대신 충돌하면 일을 처음부터 다시 한다 —
		 * 같은 계좌에 몰릴수록 헛일이 늘어난다.
		 *
		 * 2026-08-23 측정에서 핫 계좌에 168건이 갇혔다(재시도 소진 → DLT).
		 * 용량 구간에서도 종결 p99가 7.0초로 SLO를 넘겼다. 비교용으로만 남긴다.
		 */
		OPTIMISTIC,

		/**
		 * 분산 락 없이 DB 행 락({@code SELECT ... FOR UPDATE})으로 직렬화한다.
		 *
		 * 기다린다는 점은 DISTRIBUTED와 같다. 다른 것은 기다리는 자리다 —
		 * Redis가 아니라 DB이고, 기다리는 동안 커넥션을 쥔다. 그래서 풀(30)이
		 * 다시 병목이 될 수 있다. 이것이 이 전략의 유일한 열린 질문이고,
		 * 그래서 판정 지표 1순위가 처리량이 아니라 커넥션 pending이다.
		 *
		 * 얻는 것은 속도가 아니라 단순함이다. 잔액을 지키는 장치가 셋(Redis 락 ·
		 * 낙관적 락 · 재시도)에서 하나로 줄고, Redis가 잔액 경로에서 빠진다.
		 * 잔액은 account_db 한 곳에 있으므로 원래 분산 락이 필요한 모양이 아니었다
		 * (D-004 ⑤에 열린 질문으로 적어둔 그것).
		 *
		 * 낙관적 락은 여기서도 끄지 않는다. 행 락이 걸린 뒤에는 충돌이 날 수 없으므로
		 * {@code @Version}은 이제 방어선이 아니라 탐지기가 된다 —
		 * 충돌이 한 건이라도 세어지면 잠그지 않고 잔액을 만진 경로가 있다는 뜻이다.
		 */
		PESSIMISTIC
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

	/** 잔액 조각을 읽을 때 행 락을 함께 잡을 것인가. {@code BalanceShards}가 묻는다. */
	public boolean usesPessimisticLock() {
		return strategy == Strategy.PESSIMISTIC;
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
