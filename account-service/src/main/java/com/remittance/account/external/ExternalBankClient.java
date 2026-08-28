package com.remittance.account.external;

import com.remittance.account.exception.UnknownBankException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 상대 은행에 <b>HTTP로</b> 입금을 요청한다.
 *
 * <h2>왜 HTTP인가 (Phase 6.5)</h2>
 * Kafka로 하면 지금 구조와 잘 어울리지만, <b>브로커가 재전송을 책임져 불확실성이 사라진다.</b>
 * "보냈는데 결과를 모른다"가 생기려면 <b>응답을 기다리다 포기하는</b> 구조여야 한다.
 * 외부 조직과 토픽을 공유하는 것도 현실적이지 않다.
 *
 * <h2>멱등성이 우리 손을 떠난다</h2>
 * 지금까지 중복은 우리 DB의 unique 제약이 막았다. 상대 은행에는 <b>우리가 제약을 걸 수 없다.</b>
 * 송금 ID를 키로 상대가 막아준다고 <b>믿어야</b> 하고, 그 믿음 자체가 계약이다.
 * 그래서 같은 요청을 다시 보내는 것이 안전하다 — 상대가 약속을 지키는 한.
 */
@Component
public class ExternalBankClient {

	private static final Logger log = LoggerFactory.getLogger(ExternalBankClient.class);

	private final ExternalBankProperties properties;
	/** 은행마다 하나씩. 커넥션 풀을 매번 새로 만들지 않기 위해서다. */
	private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

	public ExternalBankClient(ExternalBankProperties properties) {
		this.properties = properties;
	}

	/**
	 * 입금을 요청한다.
	 *
	 * <p>여기서 <b>예외가 나가면 처리되지 않은 것으로 본다</b> — 컨슈머가 재시도한다.
	 * 그런데 타임아웃도 예외로 나간다. <b>타임아웃은 처리됐을 수도 있다.</b>
	 * 재시도가 안전한 이유는 오직 <b>상대가 멱등하기 때문</b>이고, 재시도를 다 쓰고도
	 * 답을 못 받으면 그때는 "모르는 상태"로 남는다 (Step 2b).
	 */
	public ExternalCreditResult credit(String bankCode, UUID transferId, String accountNumber,
			BigDecimal amount, String currency) {
		CreditResponse response;
		try {
			response = clientFor(bankCode).post()
					.uri("/transfers/{transferId}/credit", transferId)
					.body(new CreditRequest(accountNumber, amount, currency))
					.retrieve()
					.body(CreditResponse.class);
		} catch (RestClientException failed) {
			// 답이 없다. <b>처리됐는지 안 됐는지 알 수 없다.</b>
			// 5xx와 달리 "안 됐다"고 말할 수 없으므로 다시 보내면 안 된다 — 조회해야 한다.
			if (isNoAnswer(failed)) {
				throw new ExternalCreditUnknownException(bankCode, transferId, failed);
			}
			// 5xx처럼 상대가 "안 했다"고 말해준 것이다. 그대로 올려 재시도에 맡긴다.
			throw failed;
		}

		if (response == null) {
			throw new IllegalStateException("상대 은행이 본문 없는 응답을 줬다 (bank=%s, transferId=%s)"
					.formatted(bankCode, transferId));
		}
		log.debug("상대 은행 응답 (bank={}, transferId={}, status={})", bankCode, transferId, response.status());
		return new ExternalCreditResult(response.status(), response.reason());
	}

	/**
	 * 결과를 <b>물어본다.</b> 재시도가 아니라 조회다 — 이 차이가 이 Phase의 전부다.
	 *
	 * <p>없는 거래면 404가 오고, 그건 <b>"우리 요청이 도달하지 않았다"</b>는 뜻이다.
	 * 답이 없으면 여전히 모르는 상태이므로 예외로 나가 다음 주기에 다시 묻는다.
	 */
	public ExternalCreditResult inquire(String bankCode, UUID transferId) {
		try {
			CreditResponse response = clientFor(bankCode).get()
					.uri("/transfers/{transferId}", transferId)
					.retrieve()
					.onStatus(HttpStatusCode::is4xxClientError, (request, res) -> {
						// 404는 오류가 아니라 <b>답</b>이다. 예외로 바꾸면 "모른다"와 구분이 사라진다.
					})
					.body(CreditResponse.class);
			return response == null
					? new ExternalCreditResult(ExternalCreditStatus.NOT_FOUND, null)
					: new ExternalCreditResult(response.status(), response.reason());
		} catch (RestClientException failed) {
			if (isNoAnswer(failed)) {
				throw new ExternalCreditUnknownException(bankCode, transferId, failed);
			}
			throw failed;
		}
	}

	/**
	 * 이 실패가 <b>"답을 못 받은 것"</b>인가.
	 *
	 * <h2>예외 타입 하나로 판별하면 안 된다 ★</h2>
	 * 처음에는 {@link ResourceAccessException}만 잡았다. <b>홈서버에서 진짜로 돌려보니
	 * 타임아웃이 그 타입으로 오지 않았다</b> —
	 * {@code RestClientException: Error while extracting response ...}였다.
	 * 응답 헤더는 받았는데 <b>본문을 읽다가 끊긴</b> 경우라 다른 자리에서 감싸진다.
	 *
	 * <p>그대로 뒀으면 "모르는 상태"가 만들어지지 않고 <b>메시지가 DLT로 죽었다.</b>
	 * 돈은 나갔을 수 있는데 아무도 확인하지 않는, 이 Phase가 없애려던 바로 그 상태다.
	 * 목으로 만든 테스트는 이걸 못 잡는다 — 진짜 소켓이 끊겨봐야 나온다.
	 *
	 * <p>그래서 타입이 아니라 <b>원인 사슬에 I/O 실패가 있는지</b>를 본다
	 * ({@code KafkaErrorHandlingConfig}의 경합 판별과 같은 방식이다).
	 * {@code static}인 이유는 <b>테스트에서 직접 부를 수 있게</b> 하기 위해서다.
	 */
	static boolean isNoAnswer(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof IOException) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	private RestClient clientFor(String bankCode) {
		return clients.computeIfAbsent(bankCode, code -> {
			String url = properties.getUrls().get(code);
			if (url == null) {
				// 주소를 모르는 은행으로 보낼 수는 없다. 조용히 실패하면 돈이 어디로 갔는지
				// 아무도 모르게 되므로, 접수 자체가 실패해야 한다.
				throw new UnknownBankException(code, properties.getUrls().keySet().stream()
						.sorted().collect(Collectors.joining(", ")));
			}
			// Boot 4.1에서는 RestClient.Builder 빈이 기본 제공되지 않는다(별도 모듈로 분리됨).
			// 여기서 필요한 건 baseUrl과 타임아웃뿐이라 직접 만든다 —
			// reconciliation-service의 RestClientConfig와 같은 이유다.
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(properties.getConnectTimeout());
			requestFactory.setReadTimeout(properties.getReadTimeout());
			return RestClient.builder().baseUrl(url).requestFactory(requestFactory).build();
		});
	}

	private record CreditRequest(String accountNumber, BigDecimal amount, String currency) {
	}

	private record CreditResponse(UUID transferId, ExternalCreditStatus status, String reason) {
	}
}
