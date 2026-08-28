package com.remittance.reconciliation.client;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

	/** 상대 은행에 보냈는데 이 시간을 넘겨 결론이 안 난 건들 (Phase 6.5). */
	public List<UnknownExternalCredit> unknownExternalCredits(Duration olderThan) {
		return accountRestClient.get()
				.uri(uriBuilder -> uriBuilder.path("/internal/reconciliation/unknown-external-credits")
						.queryParam("olderThanSeconds", olderThan.toSeconds()).build())
				.retrieve()
				.body(new ParameterizedTypeReference<List<UnknownExternalCredit>>() {
				});
	}

	public record Balance(UUID accountId, BigDecimal balance, String currency) {
	}

	/**
	 * @param inquiries 몇 번 물어봤나. <b>많이 물어봤는데도 아직 여기 있다</b>는 것이
	 *                  사람이 나서야 한다는 신호라, 건수와 함께 적어 보낸다.
	 */
	public record UnknownExternalCredit(UUID transferId, String bankCode, BigDecimal amount,
			String currency, int inquiries, Instant createdAt) {
	}

	public record BalancePage(List<Balance> items, Long nextCursor, boolean hasNext) {
	}
}
