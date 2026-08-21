package com.remittance.reconciliation.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Account Service의 대사 전용 조회 API를 읽는다. */
@Component
@RequiredArgsConstructor
public class AccountClient {

	private final RestClient accountRestClient;

	public BalancePage balances(Long cursor, int size) {
		return accountRestClient.get()
				.uri(uriBuilder -> uriBuilder.path("/internal/reconciliation/balances")
						.queryParam("size", size)
						.queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
						.build())
				.retrieve()
				.body(BalancePage.class);
	}

	public record Balance(UUID accountId, BigDecimal balance, String currency) {
	}

	public record BalancePage(List<Balance> items, Long nextCursor, boolean hasNext) {
	}
}
