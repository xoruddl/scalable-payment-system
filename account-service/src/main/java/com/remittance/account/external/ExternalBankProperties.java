package com.remittance.account.external;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 상대 은행들의 주소와 기다릴 시간.
 *
 * <pre>
 * remittance:
 *   external-bank:
 *     read-timeout: 3s
 *     urls:
 *       KB: http://localhost:8086
 * </pre>
 *
 * <h2>읽기 타임아웃이 이 시스템에서 가장 무거운 설정이다</h2>
 * 이 값을 넘기면 우리는 <b>결과를 모르는 채로</b> 남는다. 짧게 잡으면 멀쩡히 처리 중인 요청을
 * 자꾸 모르는 상태로 만들고, 길게 잡으면 <b>느린 상대가 우리 컨슈머 스레드를 그만큼 붙든다.</b>
 * 어느 쪽이든 대가가 있어 "안전한 기본값"이 없다 — 그래서 재보고 정해야 하는 값이다.
 */
@Component
@ConfigurationProperties(prefix = "remittance.external-bank")
public class ExternalBankProperties {

	private Duration connectTimeout = Duration.ofSeconds(1);
	private Duration readTimeout = Duration.ofSeconds(3);
	/** 은행 코드 → 주소. 여기 없는 은행으로는 보낼 수 없다. */
	private Map<String, String> urls = new HashMap<>();

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(Duration readTimeout) {
		this.readTimeout = readTimeout;
	}

	public Map<String, String> getUrls() {
		return urls;
	}

	public void setUrls(Map<String, String> urls) {
		this.urls = urls;
	}
}
