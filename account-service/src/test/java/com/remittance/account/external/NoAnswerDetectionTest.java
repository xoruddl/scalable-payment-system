package com.remittance.account.external;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "답을 못 받았다"를 <b>예외 타입 하나로 판별하면 안 된다</b> (Phase 6.5 Step 2b).
 *
 * <h2>왜 이 테스트가 있나 — 실제로 당했다</h2>
 * 처음에는 {@link ResourceAccessException}만 잡았다. 단위 테스트도 통과했고
 * 통합 테스트도 통과했다 — <b>거기서는 클라이언트가 목이었기 때문이다.</b>
 *
 * <p>홈서버에서 진짜로 돌려보니 타임아웃이 그 타입으로 오지 않았다.
 * 응답 헤더는 받았는데 <b>본문을 읽다가 끊겨서</b>
 * {@code RestClientException: Error while extracting response ...}로 왔다.
 *
 * <p>그 결과 "모르는 상태"가 만들어지지 않고 <b>메시지가 DLT로 죽었다.</b>
 * 돈은 나갔을 수 있는데 아무도 확인하지 않는, 이 Phase가 없애려던 바로 그 상태다.
 *
 * <p>그래서 타입이 아니라 <b>원인 사슬</b>을 본다. 여기 적은 세 모양은
 * <b>실제로 관측된 것</b>과 그 이웃들이다.
 */
class NoAnswerDetectionTest {

	@Test
	void 본문을_읽다_끊긴_경우도_답을_못_받은_것이다() {
		// 홈서버에서 실제로 나온 모양이다. 이걸 놓쳐서 DLT로 죽었다.
		RestClientException observed = new RestClientException(
				"Error while extracting response for type [CreditResponse] and content type [application/octet-stream]",
				new SocketTimeoutException("Read timed out"));

		assertThat(ExternalBankClient.isNoAnswer(observed)).isTrue();
	}

	@Test
	void 연결_단계에서_끊긴_경우도_답을_못_받은_것이다() {
		ResourceAccessException connectFailed =
				new ResourceAccessException("I/O error", new SocketTimeoutException("connect timed out"));

		assertThat(ExternalBankClient.isNoAnswer(connectFailed)).isTrue();
	}

	@Test
	void 오백번대는_답을_받은_것이다() {
		// 상대가 "안 했다"고 말해준 것이라 그대로 다시 보내면 된다.
		// 이걸 "모른다"로 분류하면 재시도로 풀릴 건이 확인 루프에 쌓인다.
		HttpServerErrorException serverError =
				HttpServerErrorException.create(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
						"Service Unavailable", org.springframework.http.HttpHeaders.EMPTY,
						new byte[0], null);

		assertThat(ExternalBankClient.isNoAnswer(serverError)).isFalse();
	}

	@Test
	void 원인이_스스로를_가리켜도_무한루프에_빠지지_않는다() {
		RuntimeException selfReferencing = new RuntimeException("이상한 예외") {
			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertThat(ExternalBankClient.isNoAnswer(selfReferencing)).isFalse();
	}
}
