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

	public record StrandedKey(String idempotencyKey, Instant createdAt) {
	}
}
