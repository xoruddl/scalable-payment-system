import { FIXED_RATE, TREND_STATS, fixedRateStages, fixedStartRate, proberDuration, seedCount } from '../lib/config.js';
import { pick, seedAccounts } from '../lib/seed.js';
import {
	requestAndAwaitSettle,
	requestExternalAndAwaitSettle,
	requestExternalTransfer,
	requestTransfer,
} from '../lib/transfer.js';
import { summaryFor } from '../lib/summary.js';

/**
 * 시나리오 D — 내부 송금과 <b>상대 은행</b> 송금을 섞는다 (Phase 6.5).
 *
 * <b>여기서 보려는 것</b>: 느린 상대가 <b>우리 내부 송금까지 느리게 만드는가.</b>
 *
 * 상대 은행 호출은 컨슈머 스레드를 붙든다. 그 스레드는 원래 내부 송금의 입금도 처리하던
 * 것이라, 상대가 2초를 끌면 <b>남의 사정으로 우리 일이 멈춘다.</b>
 * 격벽(bulkhead)이 필요한 이유가 이것이고, 이 시나리오가 그 근거를 숫자로 만든다.
 *
 * <b>지연을 태그로 갈라 잰다.</b> 섞어놓고 하나로 재면 외부가 느린 건지 전체가 느린 건지
 * 구분할 수 없다. `kind:internal`이 나빠지는 것이 곧 피해다.
 *
 *   EXTERNAL_RATIO=0.2 RATE=30 k6 run load-test/scenarios/mixed-bank.js
 *
 * 상대 은행의 지연은 <b>k6가 아니라 그쪽 설정</b>으로 준다 (같은 부하, 다른 상대):
 *
 *   curl -X POST localhost:8086/faults -d '{"latencyMs":2000}'
 */

const SENDERS = Number(__ENV.SENDERS || 60);
/** 전체 송금 중 상대 은행으로 나가는 비율. */
const EXTERNAL_RATIO = Number(__ENV.EXTERNAL_RATIO || 0.2);
const BANK = __ENV.BANK || 'KB';

export const options = {
	summaryTrendStats: TREND_STATS,
	setupTimeout: '3m',
	scenarios: {
		load: {
			executor: 'ramping-arrival-rate',
			exec: 'fireAndForget',
			startRate: fixedStartRate(10),
			timeUnit: '1s',
			preAllocatedVUs: 100,
			maxVUs: 800,
			stages: fixedRateStages([
				{ target: 30, duration: '1m' },
				{ target: 60, duration: '1m' },
			]),
		},
		prober: {
			executor: 'constant-arrival-rate',
			exec: 'probe',
			rate: 2,
			timeUnit: '1s',
			duration: FIXED_RATE > 0 ? '2m' : proberDuration('2m'),
			preAllocatedVUs: 40,
			maxVUs: 200,
		},
	},
	thresholds: {
		'http_reqs{name:accept}': ['count>0'],
		'http_req_duration{name:accept}': ['p(95)<200'],
		// 갈라서 걸어야 부분지표가 만들어진다. 그리고 <b>내부가 이 시나리오의 주인공</b>이다.
		'settle_duration{kind:internal}': ['p(99)<5000'],
		'settle_duration{kind:external}': ['p(99)<5000'],
		settled: ['rate>0.99'],
	},
};

export function setup() {
	const senders = seedAccounts(seedCount(SENDERS));
	// 내부 송금이 받을 계좌. 충전할 필요는 없다.
	const internalReceiver = seedAccounts(1, { funded: false })[0];
	return { senders, internalReceiver };
}

/** 정해진 비율만큼 상대 은행으로 보낸다. */
function isExternal() {
	return Math.random() < EXTERNAL_RATIO;
}

export function fireAndForget(data) {
	const from = pick(data.senders);
	if (isExternal()) {
		requestExternalTransfer(from, BANK, { kind: 'external' });
	} else {
		requestTransfer(from, data.internalReceiver, { kind: 'internal' });
	}
}

export function probe(data) {
	const from = pick(data.senders);
	if (isExternal()) {
		requestExternalAndAwaitSettle(from, BANK, { kind: 'external' });
	} else {
		requestAndAwaitSettle(from, data.internalReceiver, { kind: 'internal' });
	}
}

export function handleSummary(data) {
	return summaryFor('mixed-bank', data);
}
