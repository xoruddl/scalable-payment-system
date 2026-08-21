import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { CURRENCY, JSON_HEADERS, SETTLE_TIMEOUT_SEC, TRANSFER_AMOUNT, TRANSFER_URL } from './config.js';
import { uuid } from './uuid.js';

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
export function requestTransfer(fromAccountId, toAccountId) {
	const res = http.post(
		`${TRANSFER_URL}/transfers`,
		JSON.stringify({
			fromAccountId,
			toAccountId,
			amount: TRANSFER_AMOUNT,
			currency: CURRENCY,
		}),
		{
			headers: { ...JSON_HEADERS, 'Idempotency-Key': uuid() },
			tags: { name: 'accept' },
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
 */
export function requestAndAwaitSettle(fromAccountId, toAccountId) {
	const startedAt = Date.now();
	const accepted = requestTransfer(fromAccountId, toAccountId);
	if (accepted.status !== 202) {
		settled.add(false);
		return;
	}

	const transferId = accepted.json('transferId');
	const deadline = startedAt + SETTLE_TIMEOUT_SEC * 1000;

	while (Date.now() < deadline) {
		sleep(0.5);
		const res = http.get(`${TRANSFER_URL}/transfers/${transferId}`, {
			tags: { name: 'poll-status' },
		});
		if (res.status !== 200) {
			continue;
		}

		const status = res.json('status');
		if (status === 'COMPLETED' || status === 'FAILED') {
			settleDuration.add(Date.now() - startedAt);
			settled.add(status === 'COMPLETED');
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
	settled.add(false);
}
