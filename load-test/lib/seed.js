import http from 'k6/http';
import { ACCOUNT_URL, CURRENCY, JSON_HEADERS, SEED_BALANCE } from './config.js';
import { uuid } from './uuid.js';

/**
 * 부하를 걸기 전에 계좌를 만들고 돈을 넣어둔다.
 *
 * <b>이걸 빠뜨리면 전부 "잔액이 부족합니다"로 실패하고, 그걸 "시스템이 느리다"로 오해하게 된다.</b>
 * 부하 테스트에서 가장 흔한 함정이다.
 *
 * 입금은 계좌당 한 번만 한다 — 여러 번 나눠 넣으면 그만큼 분개가 쌓여
 * 나중에 원장 조회 시나리오의 페이지 크기에 영향을 준다.
 */
export function createAccount() {
	const res = http.post(
		`${ACCOUNT_URL}/accounts`,
		JSON.stringify({ ownerId: uuid(), currency: CURRENCY, accountType: 'PERSONAL' }),
		{ headers: JSON_HEADERS, tags: { name: 'seed:create-account' } },
	);
	if (res.status !== 201 && res.status !== 200) {
		throw new Error(`계좌 생성 실패 (${res.status}): ${res.body}`);
	}
	return res.json('accountId');
}

export function fund(accountId, amount = SEED_BALANCE) {
	const res = http.post(
		`${ACCOUNT_URL}/internal/accounts/${accountId}/credit`,
		JSON.stringify({ amount: String(amount), currency: CURRENCY }),
		{ headers: JSON_HEADERS, tags: { name: 'seed:fund' } },
	);
	if (res.status !== 200) {
		throw new Error(`충전 실패 (${res.status}): ${res.body}`);
	}
}

/** @return 만들어진 계좌 ID 배열 */
export function seedAccounts(count, { funded = true } = {}) {
	const accounts = [];
	for (let i = 0; i < count; i++) {
		const id = createAccount();
		if (funded) {
			fund(id);
		}
		accounts.push(id);
	}
	return accounts;
}

/**
 * 계좌 하나를 <b>N조각으로 쓰게</b> 한다 (Phase 6 잔액 샤딩).
 *
 * 입금이 한 계좌로 몰릴 때 그 계좌의 잔액 행 하나가 상한이 된다. 조각을 늘리면
 * 입금이 서로 다른 행·서로 다른 락으로 갈린다. 1이면 아무 일도 하지 않는다 —
 * <b>같은 스크립트로 쪼갠 것과 안 쪼갠 것을 나란히 재기 위해서다.</b>
 */
export function shard(accountId, shardCount) {
	if (!shardCount || shardCount <= 1) {
		return 1;
	}
	const res = http.post(
		`${ACCOUNT_URL}/internal/accounts/${accountId}/shards`,
		JSON.stringify({ shardCount }),
		{ headers: JSON_HEADERS, tags: { name: 'seed:shard' } },
	);
	if (res.status !== 200) {
		throw new Error(`샤딩 실패 (${res.status}): ${res.body}`);
	}
	return res.json('shardCount');
}

export function pick(list) {
	return list[Math.floor(Math.random() * list.length)];
}
