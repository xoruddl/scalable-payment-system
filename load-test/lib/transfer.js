import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
	AUTH_SECRET,
	CURRENCY,
	JSON_HEADERS,
	SETTLE_TIMEOUT_SEC,
	TRANSFER_AMOUNT,
	TRANSFER_BASE,
	VIA_GATEWAY,
} from './config.js';
import { issueToken } from './auth.js';
import { uuid } from './uuid.js';

/**
 * 게이트웨이를 통과할 때만 붙는 토큰 (Phase 4).
 *
 * <b>VU마다 만들지 않고 모듈이 한 번만 만든다.</b> 서명을 매 요청 하면 그게 부하 생성기의
 * 일이 되어, 재려는 것(게이트웨이가 더하는 지연)에 우리 CPU 시간이 섞인다.
 */
const AUTH_HEADERS = VIA_GATEWAY
	? { Authorization: `Bearer ${issueToken('load-test', AUTH_SECRET)}` }
	: {};

/**
 * 접수(202)와 종결(COMPLETED)은 다른 사건이다.
 *
 * `POST /transfers`는 송금을 <b>접수만</b> 하고 즉시 202를 준다. 이때 돈은 아직 안 움직였다.
 * 그래서 접수 속도만 재면 "초당 수천 건" 같은 숫자가 나오는데, 그건 INSERT 두 번의 속도지
 * 송금 처리량이 아니다. <b>진짜 병목은 Outbox 릴레이 → Kafka → 컨슈머 뒤에 있다.</b>
 *
 * 아래 지표를 나눠 재는 이유가 그것이다.
 */
export const settleDuration = new Trend('settle_duration', true);
export const settled = new Rate('settled');
export const settleCompleted = new Counter('settle_completed');
export const settleFailed = new Counter('settle_failed');
export const settleTimeout = new Counter('settle_timeout');

/**
 * 송금을 접수한다. 종결은 기다리지 않는다.
 *
 * `Idempotency-Key`는 <b>매번 새로 만들어야</b> 한다. 재사용하면 두 번째부터는 새 송금이 아니라
 * 최초 송금을 그대로 돌려주는 재요청 경로로 빠져서, TPS가 비현실적으로 높게 나온다.
 */
export function requestTransfer(fromAccountId, toAccountId, tags = {}) {
	return post(fromAccountId, { toAccountId }, tags);
}

/**
 * <b>상대 은행으로</b> 보낸다 (Phase 6.5).
 *
 * 계좌번호는 매번 다르게 준다. 상대 은행은 계좌를 검증하지 않지만,
 * 같은 번호로만 보내면 <b>실제와 다른 쏠림</b>이 생겨 그쪽 DB에서 엉뚱한 경합이 날 수 있다.
 */
export function requestExternalTransfer(fromAccountId, bankCode, tags = {}) {
	return post(fromAccountId, { toBankCode: bankCode, toAccountNumber: uuid().slice(0, 18) }, tags);
}

function post(fromAccountId, destination, tags) {
	const res = http.post(
		`${TRANSFER_BASE}/transfers`,
		JSON.stringify({
			fromAccountId,
			...destination,
			amount: TRANSFER_AMOUNT,
			currency: CURRENCY,
		}),
		{
			headers: { ...JSON_HEADERS, ...AUTH_HEADERS, 'Idempotency-Key': uuid() },
			tags: { name: 'accept', ...tags },
		},
	);
	check(res, { '202로 접수됨': (r) => r.status === 202 });
	return res;
}

/**
 * 종결(COMPLETED / FAILED)까지 기다리며 걸린 시간을 잰다.
 *
 * <b>부하를 거는 모든 요청에 대해 이걸 하면 안 된다.</b> 폴링 자체가 부하가 되어,
 * 요청을 늘릴수록 조회도 같이 늘어나 무엇을 재는지 알 수 없게 된다.
 * 그래서 이 함수는 <b>낮은 고정 비율로 도는 관측용 시나리오(prober)에서만</b> 쓴다.
 *
 * <p>{@code tags}는 <b>단계별로 나눠 재기 위한 것</b>이다(`capacity.js`). 도착률을 계단으로
 * 올리며 SLO가 어느 단계에서 깨지는지 보려면, 지표가 단계별로 갈라져 있어야 한다.
 * 안 넘기면 예전처럼 하나로 합쳐 잰다.
 */
export function requestAndAwaitSettle(fromAccountId, toAccountId, tags = {}) {
	return awaitSettle(() => requestTransfer(fromAccountId, toAccountId, tags), tags);
}

/** 상대 은행으로 보내고 종결까지 기다린다. */
export function requestExternalAndAwaitSettle(fromAccountId, bankCode, tags = {}) {
	return awaitSettle(() => requestExternalTransfer(fromAccountId, bankCode, tags), tags);
}

function awaitSettle(send, tags) {
	const startedAt = Date.now();
	const accepted = send();
	if (accepted.status !== 202) {
		settled.add(false, tags);
		return;
	}

	const transferId = accepted.json('transferId');
	const deadline = startedAt + SETTLE_TIMEOUT_SEC * 1000;

	while (Date.now() < deadline) {
		sleep(0.5);
		const res = http.get(`${TRANSFER_BASE}/transfers/${transferId}`, {
			headers: AUTH_HEADERS,
			tags: { name: 'poll-status', ...tags },
		});
		if (res.status !== 200) {
			continue;
		}

		const status = res.json('status');
		if (status === 'COMPLETED' || status === 'FAILED') {
			settleDuration.add(Date.now() - startedAt, tags);
			settled.add(status === 'COMPLETED', tags);
			if (status === 'COMPLETED') {
				settleCompleted.add(1);
			} else {
				settleFailed.add(1);
			}
			return;
		}
	}

	// 끝내 종결되지 않았다. 큐 어딘가에 밀려 있다는 뜻이고, 이게 진짜 천장의 신호다.
	settleTimeout.add(1);
	settled.add(false, tags);
}
