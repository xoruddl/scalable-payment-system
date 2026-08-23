import { FIXED_RATE, TREND_STATS, fixedRateStages, proberDuration, seedCount } from '../lib/config.js';
import { pick, seedAccounts } from '../lib/seed.js';
import { requestAndAwaitSettle, requestTransfer } from '../lib/transfer.js';
import { summaryFor } from '../lib/summary.js';

/**
 * 시나리오 B — 핫 계좌 (hot account)
 *
 * 정산 계좌·가맹점 대표 계좌처럼 <b>입금이 한 계좌로 몰리는</b> 경우다.
 * 받는 계좌를 하나로 고정하면 그 계좌의 분산 락이 모든 입금을 <b>완전히 직렬화</b>한다.
 *
 * <b>여기서 봐야 할 것</b>: 접수(202)는 여전히 빠릅니다. HTTP 에러도 거의 안 납니다.
 * 락 경합은 <b>비동기 파이프라인 뒤에서 벌어지기 때문</b>에 `settle_duration`이 길어지고
 * `settled`가 떨어지는 것으로만 드러납니다.
 *
 * <b>이게 "202는 성공이 아니다"의 가장 선명한 사례입니다.</b>
 * 접수 지표만 보고 있으면 시스템이 멀쩡해 보입니다.
 *
 * 그리고 이 병목은 <b>서버를 늘려도 안 풀립니다</b> — 병목이 계좌 하나에 있기 때문입니다.
 *
 *   k6 run load-test/scenarios/hot-account.js
 */

const SENDERS = Number(__ENV.SENDERS || 60);

export const options = {
	summaryTrendStats: TREND_STATS,
	setupTimeout: '3m',
	scenarios: {
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
		// 접수는 spread와 똑같이 빠를 것이다 — 그게 함정이라는 걸 보여주려고 같은 기준을 둔다.
		'http_req_duration{name:accept}': ['p(95)<200'],
		// 이쪽이 무너진다. baseline에서는 통과하지 못하는 게 정상이다.
		settle_duration: ['p(95)<5000'],
		settled: ['rate>0.99'],
	},
};

export function setup() {
	const senders = seedAccounts(seedCount(SENDERS));
	// 돈이 몰릴 계좌. 받기만 하므로 충전할 필요가 없다.
	const hotAccount = seedAccounts(1, { funded: false })[0];
	return { senders, hotAccount };
}

export function fireAndForget(data) {
	requestTransfer(pick(data.senders), data.hotAccount);
}

export function probe(data) {
	requestAndAwaitSettle(pick(data.senders), data.hotAccount);
}

export function handleSummary(data) {
	return summaryFor('hot-account', data);
}
