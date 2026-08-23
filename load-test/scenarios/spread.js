import { FIXED_RATE, TREND_STATS, fixedRateStages, proberDuration, seedCount } from '../lib/config.js';
import { pick, seedAccounts } from '../lib/seed.js';
import { requestAndAwaitSettle, requestTransfer } from '../lib/transfer.js';
import { summaryFor } from '../lib/summary.js';

/**
 * 시나리오 A — 골고루 보내기
 *
 * 계좌를 넓게 흩어서 <b>계좌 락 경합을 최소화</b>한다. 그러면 락이 아니라
 * <b>파이프라인이 먼저 막힌다</b> — Outbox 릴레이가 500ms마다 100건이라 초당 200 이벤트인데,
 * 송금 한 건이 account에서만 분개 2개 + 단계 이벤트 2개를 만든다.
 *
 * <b>검증할 가설: 약 50 TPS 근처에서 천장이 보인다.</b>
 *
 *   k6 run load-test/scenarios/spread.js
 */

const ACCOUNTS = Number(__ENV.ACCOUNTS || 60);

export const options = {
	summaryTrendStats: TREND_STATS,
	setupTimeout: '3m',
	scenarios: {
		// 부하를 거는 쪽. 접수만 하고 종결은 기다리지 않는다.
		load: {
			executor: 'ramping-arrival-rate',
			exec: 'fireAndForget',
			startRate: 10,
			timeUnit: '1s',
			preAllocatedVUs: 50,
			maxVUs: 600,
			// 기본은 천장을 찾는 계단. RATE를 주면 그 도착률로 2분만 돈다 (lib/config.js 참고).
			stages: fixedRateStages([
				{ target: 50, duration: '1m' },
				{ target: 100, duration: '1m' },
				{ target: 200, duration: '1m' },
				{ target: 400, duration: '1m' },
			]),
		},
		// 재는 쪽. 초당 1건만 보내고 끝까지 따라가 종결 지연을 잰다.
		// 부하와 분리해야 폴링이 측정을 오염시키지 않는다.
		prober: {
			executor: 'constant-arrival-rate',
			exec: 'probe',
			rate: 1,
			timeUnit: '1s',
			duration: FIXED_RATE > 0 ? '2m' : proberDuration('4m'),
			preAllocatedVUs: 20,
			maxVUs: 100,
		},
	},
	thresholds: {
		// 접수는 INSERT 두 번이라 빨라야 한다.
		'http_req_duration{name:accept}': ['p(95)<200'],
		// 진짜 지표. 이게 무너지는 지점이 천장이다.
		settle_duration: ['p(95)<5000'],
		settled: ['rate>0.99'],
	},
};

export function setup() {
	// 계좌가 적으면 같은 계좌에 요청이 몰려 락 경합이 생긴다 — 그건 hot-account 시나리오의 몫이다.
	return { accounts: seedAccounts(seedCount(ACCOUNTS)) };
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
	return summaryFor('spread', data);
}
