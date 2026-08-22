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

export function pick(list) {
	return list[Math.floor(Math.random() * list.length)];
}
