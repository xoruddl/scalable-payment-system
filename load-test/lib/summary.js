/**
 * 측정 결과를 파일로 남긴다.
 *
 * Phase 5의 목적은 이 테스트를 <b>통과시키는 게 아니라 지금 값을 기록하는 것</b>이다.
 * Phase 6에서 병목을 뚫은 뒤 <b>같은 시나리오로 다시 재서 비교</b>해야 하므로,
 * 화면에 스쳐 지나가게 두면 안 된다.
 */
export function summaryFor(name, data) {
	const stamp = new Date().toISOString().replace(/[:.]/g, '-');
	const path = `load-test/results/${name}-${stamp}.json`;
	return {
		stdout: textSummary(name, data),
		[path]: JSON.stringify(data, null, 2),
	};
}

/**
 * @param fallback 지표가 아예 없을 때 보여줄 값.
 *                 <b>카운터는 '0'을 줘야 한다</b> — 한 건도 안 일어나면 k6가 지표 자체를
 *                 만들지 않는데, 그걸 '—'로 찍으면 "실패가 0건"인지 "못 쟀는지" 구분이 안 된다.
 */
function metric(data, key, stat, fallback = '—') {
	const m = data.metrics[key];
	if (!m || m.values[stat] === undefined) {
		return fallback;
	}
	const v = m.values[stat];
	return typeof v === 'number' ? v.toFixed(2) : String(v);
}

function textSummary(name, data) {
	// 조회 시나리오처럼 송금을 하지 않는 경우엔 종결 지표가 아예 없다.
	// 빈 칸으로 채운 섹션을 보여주면 "못 쟀나?"로 읽혀서 아예 뺀다.
	const hasSettle = data.metrics.settle_duration !== undefined;

	// 어떤 조건으로 쟀는지를 숫자 바로 옆에 남긴다. 포화 시험(계단)과 고정 도착률은
	// 값의 뜻이 달라서, 조건을 안 적어두면 나중에 나란히 놓게 된다.
	const rate = Number(__ENV.RATE || 0);
	const smoke = __ENV.SMOKE === '1' || __ENV.SMOKE === 'true';
	let condition;
	if (smoke) {
		condition = 'SMOKE — 배선 확인용. 이 숫자는 성능 값이 아니다';
	} else if (rate > 0) {
		condition = `고정 도착률 ${rate} TPS — 용량 근처. 포화 시험 값과 나란히 두지 말 것`;
	} else {
		condition = '계단 부하 (최대 400 TPS) — 천장을 찾는 포화 시험';
	}
	// 받는 계좌를 쪼갰는지는 핫 계좌 숫자를 읽는 데 반드시 필요하다.
	// 쪼갠 값과 안 쪼갠 값을 나란히 두면 그냥 틀린 비교가 된다.
	const shards = Number(__ENV.SHARDS || 1);
	if (shards > 1) {
		condition += ` · 받는 계좌 ${shards}조각`;
	}

	const lines = [
		'',
		`  === ${name} ===`,
		`  ${condition}`,
		'',
		hasSettle
			? '  접수 (POST /transfers — 여기만 보면 시스템이 멀쩡해 보인다)'
			: '  요청',
		`    처리량        ${metric(data, 'http_reqs', 'rate')} req/s`,
		`    p95           ${metric(data, 'http_req_duration', 'p(95)')} ms`,
		`    p99           ${metric(data, 'http_req_duration', 'p(99)')} ms`,
		`    실패율        ${metric(data, 'http_req_failed', 'rate')}`,
	];

	if (hasSettle) {
		lines.push(
			'',
			'  종결 (COMPLETED까지 — 진짜 지표)',
			`    p95           ${metric(data, 'settle_duration', 'p(95)')} ms`,
			`    p99           ${metric(data, 'settle_duration', 'p(99)')} ms`,
			`    종결 성공률   ${metric(data, 'settled', 'rate')}`,
			`    COMPLETED     ${metric(data, 'settle_completed', 'count', '0')}`,
			`    FAILED        ${metric(data, 'settle_failed', 'count', '0')}`,
			`    시간초과      ${metric(data, 'settle_timeout', 'count', '0')}  ← 큐에 밀려 있다는 뜻`,
		);
	}

	lines.push('');
	return lines.join('\n') + '\n';
}
