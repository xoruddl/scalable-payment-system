import { TREND_STATS, SMOKE } from '../lib/config.js';
import { pick, seedAccounts } from '../lib/seed.js';
import { requestAndAwaitSettle, requestTransfer } from '../lib/transfer.js';

/**
 * 용량 측정 — <b>SLO를 지키면서 견디는 최대 도착률</b>을 찾는다 (`docs/SLO.md`).
 *
 * <h2>spread.js와 무엇이 다른가</h2>
 * `spread.js`는 초당 400건까지 밀어 올린다. 그건 <b>천장을 찾는 포화 시험</b>이다.
 * 감당 못 하는 부하를 부어놓고 잰 지연은 시스템 지연이 아니라 <b>대기열 길이</b>다.
 * 실제로 2026-08-23 측정에서 종결 p95가 32.8초였는데, 그건 시스템이 32초 걸린다는 뜻이 아니라
 * <b>능력의 5배를 부었다</b>는 뜻이었다.
 *
 * <p>여기서는 반대로 간다. <b>낮은 데서 시작해 한 단계씩 올리며 SLO가 깨지는 지점</b>을 찾는다.
 * 마지막으로 통과한 단계가 이 시스템의 용량이다.
 *
 * <h2>단계 사이를 왜 비우나</h2>
 * 앞 단계에서 밀린 것이 남아 있으면 <b>다음 단계는 자기 부하에 남의 적체까지 처리</b>하게 된다.
 * 그러면 뒤 단계일수록 불리해져서, 진짜 한계보다 낮게 나온다.
 *
 *   k6 run load-test/scenarios/capacity.js
 *   SMOKE=1 k6 run load-test/scenarios/capacity.js     # 배선만 20초로 확인
 */

/** 도착률 계단. 이 근처에서 깨질 것으로 보고 촘촘하게 잡았다. */
const STEPS = SMOKE ? [10] : [20, 40, 60, 80, 100, 120];

/** 한 단계를 재는 시간. 짧으면 워밍업과 순간적인 튐이 결과를 흔든다. */
const LOAD_SEC = SMOKE ? 20 : 120;

/** 다음 단계 전에 큐를 비우는 시간. */
const DRAIN_SEC = SMOKE ? 10 : 60;

/**
 * 종결을 끝까지 따라가는 관측용 비율. 부하와 분리해야 폴링이 측정을 오염시키지 않는다.
 * 2건/s × 120초 = 단계마다 240건 — p99를 말하기엔 적지만 <b>깨지는 지점을 찾기엔 충분</b>하다.
 */
const PROBE_RATE = 2;

const ACCOUNTS = Number(__ENV.ACCOUNTS || 60);

/** `docs/SLO.md`의 목표. 여기 숫자를 바꾸면 문서도 함께 고쳐야 한다. */
const SLO = {
	acceptP99Ms: 500,
	settleP99Ms: 5000,
	acceptErrorRate: 0.001,
};

const scenarios = {};
const thresholds = {};

STEPS.forEach((rate, index) => {
	const startTime = index * (LOAD_SEC + DRAIN_SEC);
	const step = String(rate);

	scenarios[`load_${rate}`] = {
		executor: 'constant-arrival-rate',
		exec: 'fireAndForget',
		rate,
		timeUnit: '1s',
		duration: `${LOAD_SEC}s`,
		startTime: `${startTime}s`,
		preAllocatedVUs: Math.max(10, rate),
		maxVUs: Math.max(50, rate * 5),
		tags: { step },
	};

	scenarios[`probe_${rate}`] = {
		executor: 'constant-arrival-rate',
		exec: 'probe',
		rate: PROBE_RATE,
		timeUnit: '1s',
		duration: `${LOAD_SEC}s`,
		startTime: `${startTime}s`,
		preAllocatedVUs: 20,
		maxVUs: 200,
		tags: { step },
	};

	// k6는 임계값에 적힌 하위 지표만 만들어 준다. 단계별로 보려면 여기에 선언해야 한다.
	thresholds[`http_req_duration{name:accept,step:${step}}`] = [`p(99)<${SLO.acceptP99Ms}`];
	thresholds[`settle_duration{step:${step}}`] = [`p(99)<${SLO.settleP99Ms}`];
	thresholds[`http_req_failed{name:accept,step:${step}}`] = [`rate<${SLO.acceptErrorRate}`];
	thresholds[`settled{step:${step}}`] = ['rate>0.99'];
});

export const options = {
	summaryTrendStats: TREND_STATS,
	setupTimeout: '3m',
	// 한 단계가 깨져도 멈추지 않는다. 어디서부터 얼마나 나빠지는지가 알고 싶은 것이다.
	thresholds,
	scenarios,
};

export function setup() {
	return { accounts: seedAccounts(ACCOUNTS) };
}

function pickPair(accounts) {
	const from = pick(accounts);
	let to = pick(accounts);
	while (to === from) {
		to = pick(accounts);
	}
	return [from, to];
}

export function fireAndForget(data) {
	const [from, to] = pickPair(data.accounts);
	requestTransfer(from, to);
}

export function probe(data) {
	const [from, to] = pickPair(data.accounts);
	requestAndAwaitSettle(from, to);
}

export function handleSummary(data) {
	const stamp = new Date().toISOString().replace(/[:.]/g, '-');
	return {
		stdout: report(data),
		[`load-test/results/capacity-${stamp}.json`]: JSON.stringify(data, null, 2),
	};
}

function value(data, key, stat) {
	const metric = data.metrics[key];
	return metric && metric.values[stat] !== undefined ? metric.values[stat] : null;
}

function show(v, unit) {
	return v === null ? '—' : `${v.toFixed(unit === '%' ? 2 : 0)}${unit}`;
}

function report(data) {
	const lines = [
		'',
		'  === 용량 측정 (docs/SLO.md) ===',
		'',
		`  목표: 접수 p99 < ${SLO.acceptP99Ms}ms · 종결 p99 < ${SLO.settleP99Ms / 1000}초 · 접수 오류율 < ${SLO.acceptErrorRate * 100}%`,
		'',
		'   도착률    접수 p99    종결 p99    종결 성공률   접수 오류율   판정',
		'  ─────────────────────────────────────────────────────────────────────',
	];

	let capacity = null;
	for (const rate of STEPS) {
		const step = String(rate);
		const acceptP99 = value(data, `http_req_duration{name:accept,step:${step}}`, 'p(99)');
		const settleP99 = value(data, `settle_duration{step:${step}}`, 'p(99)');
		const settledRate = value(data, `settled{step:${step}}`, 'rate');
		const errorRate = value(data, `http_req_failed{name:accept,step:${step}}`, 'rate');

		// 못 잰 값이 있으면 통과라고 말할 수 없다. '—'를 통과로 세면 목표가 무의미해진다.
		const measured = acceptP99 !== null && settleP99 !== null && settledRate !== null;
		const pass = measured
			&& acceptP99 < SLO.acceptP99Ms
			&& settleP99 < SLO.settleP99Ms
			&& settledRate > 0.99
			&& (errorRate === null || errorRate < SLO.acceptErrorRate);

		if (pass) {
			capacity = rate;
		}
		lines.push(
			`  ${String(rate).padStart(5)} TPS  ${show(acceptP99, 'ms').padStart(9)}  ${show(settleP99, 'ms').padStart(10)}`
			+ `  ${show(settledRate === null ? null : settledRate * 100, '%').padStart(11)}`
			+ `  ${show(errorRate === null ? null : errorRate * 100, '%').padStart(11)}   ${pass ? '통과' : '깨짐'}`,
		);
	}

	lines.push(
		'  ─────────────────────────────────────────────────────────────────────',
		'',
		capacity === null
			? '  용량: 가장 낮은 단계부터 깨졌다. 계단을 더 낮게 잡아 다시 재야 한다.'
			: capacity === STEPS[STEPS.length - 1]
				? `  용량: >= ${capacity} TPS — 마지막 단계까지 통과했다. 계단을 더 높여야 한계가 보인다.`
				: `  용량: ${capacity} TPS  (다음 단계에서 깨졌다)`,
		'',
	);
	return lines.join('\n');
}
