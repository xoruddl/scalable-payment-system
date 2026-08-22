import http from 'k6/http';
import { check, sleep } from 'k6';
import { LEDGER_URL, TREND_STATS, loadStages, seedCount } from '../lib/config.js';
import { fund, pick, seedAccounts } from '../lib/seed.js';
import { summaryFor } from '../lib/summary.js';

/**
 * 시나리오 C — 거래내역 조회 폭주
 *
 * 원장(ledger)은 조회 트래픽이 몰리는 곳이라 WebFlux + MongoDB로 만들었다.
 * <b>그 선택이 실제로 값을 하는지</b> 확인하는 시나리오다.
 *
 * 쓰기 시나리오(A·B)와 달리 여기서는 <b>동기 응답이 곧 결과</b>다 —
 * 조회는 접수/종결이 나뉘지 않으므로 `http_req_duration`이 그대로 답이다.
 *
 *   k6 run load-test/scenarios/read-heavy.js
 */

const ACCOUNTS = Number(__ENV.ACCOUNTS || 20);
/** 계좌당 분개를 몇 줄 만들어 둘지. 조회할 게 있어야 의미가 있다. */
const ENTRIES_PER_ACCOUNT = Number(__ENV.ENTRIES_PER_ACCOUNT || 10);

export const options = {
	summaryTrendStats: TREND_STATS,
	setupTimeout: '5m',
	scenarios: {
		read: {
			executor: 'ramping-arrival-rate',
			startRate: 50,
			timeUnit: '1s',
			preAllocatedVUs: 50,
			maxVUs: 800,
			stages: loadStages([
				{ target: 200, duration: '1m' },
				{ target: 500, duration: '1m' },
				{ target: 1000, duration: '1m' },
			]),
		},
	},
	thresholds: {
		'http_req_duration{name:list-transactions}': ['p(95)<300'],
		http_req_failed: ['rate<0.01'],
	},
};

export function setup() {
	const accounts = seedAccounts(seedCount(ACCOUNTS), { funded: false });
	// 입금 한 번이 분개 한 줄이 된다. 조회할 내역을 이렇게 만든다.
	for (const id of accounts) {
		for (let i = 0; i < ENTRIES_PER_ACCOUNT; i++) {
			fund(id, '1000');
		}
	}
	// Outbox 릴레이(500ms) → Kafka → 원장 기록까지 시간이 걸린다.
	// 여기서 안 기다리면 빈 목록을 조회하게 되어 무엇을 쟀는지 알 수 없다.
	waitForLedger(accounts[0], ENTRIES_PER_ACCOUNT);
	return { accounts };
}

/**
 * 원장이 시드 분개를 다 받을 때까지 기다린다.
 *
 * 응답 필드는 <b>{@code items}</b>다. {@code transactions}로 읽으면 항상 빈 배열이 나와서
 * "원장이 아직 안 왔다"로 오해하게 된다 — 이 저장소에서 실제로 한 번 겪은 실수라
 * ({@code PROGRESS.md} 참고) 여기 못 박아 둔다.
 */
function waitForLedger(accountId, expected) {
	for (let i = 0; i < 60; i++) {
		const res = http.get(`${LEDGER_URL}/accounts/${accountId}/transactions?size=50`, {
			tags: { name: 'seed:wait-ledger' },
		});
		if (res.status === 200 && (res.json('items') || []).length >= expected) {
			return;
		}
		sleep(1);
	}
	throw new Error('원장이 시드 분개를 다 받지 못했다 — 서비스가 다 떠 있는지 확인하라');
}

export default function (data) {
	const res = http.get(`${LEDGER_URL}/accounts/${pick(data.accounts)}/transactions?size=20`, {
		tags: { name: 'list-transactions' },
	});
	check(res, { '200으로 조회됨': (r) => r.status === 200 });
}

export function handleSummary(data) {
	return summaryFor('read-heavy', data);
}
