import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

/**
 * 게이트웨이를 통과하려면 토큰이 필요하다 (Phase 4).
 *
 * <b>발급 서비스를 만들지 않았으므로</b> 부하 스크립트가 직접 서명한다.
 * 게이트웨이가 HS256 대칭키로 검증하니 같은 비밀을 알면 만들 수 있다 —
 * 그게 대칭키의 성질이고, 여기서는 그 성질을 이용하는 것이다.
 *
 * <p>⚠️ 이건 <b>측정용</b>이다. 실제라면 토큰은 발급자에게서 받아야 하고,
 * 부하 생성기가 서명할 수 있다는 것 자체가 운영에서는 사고다.
 */
export function issueToken(subject, secret, ttlSeconds = 7200) {
	const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
	const payload = b64url(
		JSON.stringify({ sub: subject, exp: Math.floor(Date.now() / 1000) + ttlSeconds }),
	);
	const signingInput = `${header}.${payload}`;
	const signature = crypto.hmac('sha256', secret, signingInput, 'base64rawurl');
	return `${signingInput}.${signature}`;
}

function b64url(text) {
	return encoding.b64encode(text, 'rawurl');
}
