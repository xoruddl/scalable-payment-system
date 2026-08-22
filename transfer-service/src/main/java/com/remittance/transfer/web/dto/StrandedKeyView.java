package com.remittance.transfer.web.dto;

import com.remittance.transfer.domain.IdempotencyKey;

import java.time.Instant;
import java.util.UUID;

/**
 * 접수 도중 서버가 죽어 {@code IN_PROGRESS}로 남은 멱등성 키.
 *
 * <p>Step 6b 전에는 키와 시각만 실어 보냈다. 그것만으로는 <b>어느 쪽 사고인지 알 수 없어</b>
 * 대사가 "묶인 키가 있다"는 말밖에 못 했다.
 *
 * @param committedTransferId 이 키로 <b>실제로 커밋된 송금</b>이 있으면 그 ID.
 *                            <ul>
 *                              <li>있다 — 접수는 끝났고 키에 적기 직전에 죽었다.
 *                                  재요청하면 그 송금을 그대로 돌려받는다(전진 복구).</li>
 *                              <li>없다 — 접수가 커밋되지 않았다. 재요청하면 키가 풀리고 새로 접수된다.</li>
 *                            </ul>
 *                            둘은 대응이 정반대라 반드시 구분해서 봐야 한다.
 */
public record StrandedKeyView(String idempotencyKey, Instant createdAt, UUID committedTransferId) {

	public static StrandedKeyView of(IdempotencyKey key, UUID committedTransferId) {
		return new StrandedKeyView(key.getKey(), key.getCreatedAt(), committedTransferId);
	}
}
