// 서비스 주소. Gateway(Phase 4)가 생기기 전이라 각 서비스를 직접 때린다.
export const ACCOUNT_URL = __ENV.ACCOUNT_URL || 'http://localhost:8081';
export const TRANSFER_URL = __ENV.TRANSFER_URL || 'http://localhost:8082';
export const LEDGER_URL = __ENV.LEDGER_URL || 'http://localhost:8083';

export const CURRENCY = 'KRW';

/** 송금 한 건의 금액. 작게 잡아야 잔액이 먼저 마르지 않는다. */
export const TRANSFER_AMOUNT = __ENV.TRANSFER_AMOUNT || '100';

/** 시드 계좌에 넣어둘 돈. 부하 × 시간보다 넉넉해야 한다. */
export const SEED_BALANCE = __ENV.SEED_BALANCE || '1000000000';

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** 종결까지 이만큼 기다려도 안 끝나면 사실상 실패로 본다. */
export const SETTLE_TIMEOUT_SEC = Number(__ENV.SETTLE_TIMEOUT_SEC || 60);

/**
 * Smoke 모드 — <b>테스트의 테스트</b>.
 *
 * 4분짜리를 다 돌리고 나서 스크립트 오타를 발견하면 그만큼 날린다.
 * 먼저 20초로 돌려 시드·요청·폴링·요약이 다 도는지 확인한 뒤 본 측정을 한다.
 *
 *   SMOKE=1 k6 run load-test/scenarios/spread.js
 */
export const SMOKE = __ENV.SMOKE === '1' || __ENV.SMOKE === 'true';

/** smoke일 때는 아주 낮은 부하로 짧게. 아니면 원래 계단 그대로. */
export function loadStages(stages) {
	return SMOKE ? [{ target: 5, duration: '20s' }] : stages;
}

/**
 * 고정 도착률로 짧게 돌린다 — <b>변경이 도움이 됐는지</b>를 볼 때 쓴다.
 *
 *   RATE=30 k6 run load-test/scenarios/hot-account.js
 *
 * <b>왜 필요한가</b>: 기본 계단은 초당 400건까지 밀어 올린다. 그건 <b>천장을 찾는 포화 시험</b>인데,
 * 이 시스템 용량은 40 TPS다. 감당 못 하는 10배를 4분간 부으면 적체가 2만 건 쌓이고
 * <b>그게 빠지기를 10분 넘게 기다려야</b> 한다. 그 10분은 아무것도 알려주지 않는다 —
 * 이미 아는 사실(포화된다)을 다시 확인할 뿐이다.
 *
 * <p>용량 근처로 걸면 적체가 안 쌓여 <b>드레인이 수십 초로 줄고</b>, 락 대기·보유·충돌·갇힘 같은
 * <b>알고 싶은 값은 그대로 나온다.</b> 게다가 그게 실제로 서비스하는 구간이다.
 *
 * <p><b>주의</b>: 이렇게 잰 값은 포화 상태에서 잰 값과 <b>나란히 놓으면 안 된다.</b>
 * 조건이 다르다. 기록에 도착률을 함께 남길 것.
 */
export function fixedRateStages(stages, duration = '2m') {
	if (SMOKE) {
		return [{ target: 5, duration: '20s' }];
	}
	const rate = Number(__ENV.RATE || 0);
	return rate > 0 ? [{ target: rate, duration }] : stages;
}

/** 고정 도착률 모드인가. 요약에 "무슨 조건으로 쟀는지"를 남기기 위해 필요하다. */
export const FIXED_RATE = Number(__ENV.RATE || 0);

/**
 * `ramping-arrival-rate`의 시작 도착률.
 *
 * <p><b>이게 없으면 RATE는 고정 도착률이 아니라 램프가 된다.</b> 단계가
 * {@code [{target: RATE}]} 하나뿐이어도 시작이 10이면 10 → RATE로 2분간 올라가므로,
 * 실제 평균은 {@code (10 + RATE) / 2}다. 2026-08-24에 이걸 모르고
 * <b>"RATE=60을 견딘다"고 적었는데 실제로는 평균 35였다.</b>
 *
 * <p>시작을 목표와 같게 두면 램프 구간이 없어져 <b>진짜 고정</b>이 된다.
 */
export function fixedStartRate(fallback) {
	return FIXED_RATE > 0 ? FIXED_RATE : fallback;
}

export function proberDuration(duration) {
	return SMOKE ? '20s' : duration;
}

/** smoke일 때는 시드 계좌도 최소한만 — 계좌 만드는 데 시간을 쓸 이유가 없다. */
export function seedCount(count) {
	return SMOKE ? Math.min(count, 4) : count;
}

/**
 * k6의 기본 요약은 p99를 계산하지 않는다(avg/min/med/max/p90/p95까지).
 *
 * <b>평균과 p95만 보면 꼬리를 놓친다.</b> 트래픽이 많으면 "100명 중 1명"이 하루 수만 명이다.
 * 모든 시나리오가 이 설정을 공유해야 baseline과 재측정을 같은 잣대로 비교할 수 있다.
 */
export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];
