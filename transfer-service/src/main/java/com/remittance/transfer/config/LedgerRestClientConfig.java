package com.remittance.transfer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LedgerRestClientConfig {

	@Bean
	public RestClient ledgerRestClient(@Value("${services.ledger.base-url}") String baseUrl) {
		return RestClient.builder().baseUrl(baseUrl).build();
	}
}
