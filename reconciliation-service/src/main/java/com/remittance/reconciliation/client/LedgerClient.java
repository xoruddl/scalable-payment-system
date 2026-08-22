package com.remittance.reconciliation.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Ledger Service에 "이 계좌들의 원장 합"을 물어본다. */
@Component
@RequiredArgsConstructor
public class LedgerClient {

	private final RestClient ledgerRestClient;

	public Map<UUID, BigDecimal> balancesOf(List<UUID> accountIds) {
		List<Balance> balances = ledgerRestClient.post()
				.uri("/internal/reconciliation/balances")
				.body(accountIds)
				.retrieve()
				.body(new org.springframework.core.ParameterizedTypeReference<List<Balance>>() {
				});
		return balances == null ? Map.of() : balances.stream()
				.collect(Collectors.toMap(Balance::accountId, Balance::balance, (a, b) -> a));
	}

	public record Balance(UUID accountId, BigDecimal balance) {
	}

	/** 조회 결과에 없는 계좌는 원장이 비어 있다는 뜻이라 0으로 본다. */
	public static Function<UUID, BigDecimal> asLookup(Map<UUID, BigDecimal> balances) {
		return accountId -> balances.getOrDefault(accountId, BigDecimal.ZERO);
	}
}
