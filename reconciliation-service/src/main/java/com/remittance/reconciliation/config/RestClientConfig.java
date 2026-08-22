package com.remittance.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 다른 서비스를 읽어오는 HTTP 클라이언트.
 *
 * <p><b>타임아웃을 반드시 건다.</b> 대사는 스케줄러 스레드에서 도는데, 응답 없는 서비스에
 * 무한정 매달리면 그 스레드가 잠기고 <b>대사 자체가 조용히 멈춘다</b> — 어긋남을 찾으라고 만든 것이
 * 어긋난 줄도 모르는 상태가 된다. 못 읽으면 차라리 회차를 실패로 남기는 편이 낫다.
 */
@Configuration
@ConfigurationProperties(prefix = "reconciliation.clients")
public class RestClientConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	private String accountUrl = "http://localhost:8081";
	private String transferUrl = "http://localhost:8082";
	private String ledgerUrl = "http://localhost:8083";

	public void setAccountUrl(String accountUrl) {
		this.accountUrl = accountUrl;
	}

	public void setTransferUrl(String transferUrl) {
		this.transferUrl = transferUrl;
	}

	public void setLedgerUrl(String ledgerUrl) {
		this.ledgerUrl = ledgerUrl;
	}

	@Bean
	RestClient accountRestClient() {
		return client(accountUrl);
	}

	@Bean
	RestClient transferRestClient() {
		return client(transferUrl);
	}

	@Bean
	RestClient ledgerRestClient() {
		return client(ledgerUrl);
	}

	/**
	 * Boot가 주는 {@code RestClient.Builder}를 주입받지 않고 직접 만든다 —
	 * Boot 4.1에서는 그 빈이 기본 제공되지 않는다(별도 모듈로 분리됨).
	 * 여기서 필요한 건 baseUrl과 타임아웃뿐이라 정적 팩토리로 충분하다.
	 */
	private RestClient client(String baseUrl) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}
}
