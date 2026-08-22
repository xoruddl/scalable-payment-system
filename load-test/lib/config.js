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
