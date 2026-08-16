package com.remittance.transfer.client;

import com.remittance.transfer.client.dto.RecordTransactionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Ledger Service에 거래 내역을 기록한다.
 * Phase 1에서는 실패해도 송금 자체를 실패시키지 않고 로그만 남긴다.
 * 기록 원자성 보장(Outbox/Kafka)은 Phase 3에서 다룬다.
 */
@Component
public class LedgerClient {

	private static final Logger log = LoggerFactory.getLogger(LedgerClient.class);

	private final RestClient restClient;

	public LedgerClient(RestClient ledgerRestClient) {
		this.restClient = ledgerRestClient;
	}

	public void recordTransactions(List<RecordTransactionRequest> transactions) {
		try {
			restClient.post()
					.uri("/internal/transactions")
					.body(transactions)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientException e) {
			log.warn("원장 기록에 실패했습니다. transactions={}", transactions, e);
		}
	}
}
