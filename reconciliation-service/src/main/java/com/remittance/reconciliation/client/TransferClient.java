package com.remittance.reconciliation.client;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transfer Service에서 "끝나지 못한 것들"을 읽어온다. */
@Component
@RequiredArgsConstructor
public class TransferClient {

	private final RestClient transferRestClient;

	public List<UnsettledTransfer> unsettledTransfers(Duration olderThan) {
		return transferRestClient.get()
				.uri(uriBuilder -> uriBuilder.path("/internal/reconciliation/unsettled-transfers")
						.queryParam("olderThanSeconds", olderThan.toSeconds()).build())
				.retrieve()
				.body(new ParameterizedTypeReference<List<UnsettledTransfer>>() {
				});
	}

	public List<StrandedKey> strandedKeys(Duration olderThan) {
		return transferRestClient.get()
				.uri(uriBuilder -> uriBuilder.path("/internal/reconciliation/stranded-keys")
						.queryParam("olderThanSeconds", olderThan.toSeconds()).build())
				.retrieve()
				.body(new ParameterizedTypeReference<List<StrandedKey>>() {
				});
	}

	public record UnsettledTransfer(UUID transferId, String status, Instant requestedAt) {
	}

	/**
	 * @param committedTransferId 이 키로 실제로 커밋된 송금이 있으면 그 ID, 없으면 {@code null}.
	 *                            <b>대응이 정반대로 갈리는 값</b>이라 반드시 함께 본다 —
	 *                            있으면 재요청이 그 송금을 돌려받고, 없으면 키가 풀리고 새로 접수된다.
	 */
	public record StrandedKey(String idempotencyKey, Instant createdAt, UUID committedTransferId) {
	}
}
