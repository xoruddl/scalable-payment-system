package com.remittance.account.external;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 상대 은행을 부르는 문. 격벽과 회로 차단기를 어떻게 두르는지가 여기 모인다.
 *
 * 왜 떼어냈나 (2026-09-13)
 * {@code bulkhead.call(() -> circuitBreaker.call(... externalBankClient.credit(...)))}가
 * Saga 입금 단계에 한 번, 확인 루프에 두 번 똑같이 있었다. 셋 중 하나에서 회로를 빠뜨려도
 * 컴파일은 된다. 부르는 쪽도 "상대에게 입금을 요청한다" 하나를 위해 협력자 셋을
 * 주입받고 있었다.
 *
 * 새 입금과 조회는 보호가 다르다
 *
 *   새 입금  격벽 + 회로  계속 실패하는 은행에는 잠시 보내지 않는다
 *   조회     격벽만       이미 보낸 돈의 결과 확인을 회로 때문에 늦추면 안 된다
 *
 * 예외는 번역하지 않고 그대로 내보낸다. 부르는 쪽이 둘을 다르게 다루기 때문이다.
 *
 *   BulkheadFullException, CallNotPermittedException  보내지 않았다
 *   ExternalCreditUnknownException                     보냈는지 모른다
 */
@Component
@RequiredArgsConstructor
public class ExternalCreditGateway {

	private final ExternalBankClient externalBankClient;
	private final ExternalCallBulkhead bulkhead;
	private final ExternalCallCircuitBreaker circuitBreaker;

	/** 새 입금을 요청한다. */
	public ExternalCreditResult credit(ExternalCreditRequest request) {
		return credit(request, () -> { });
	}

	/**
	 * 격벽과 회로가 허가한 뒤, HTTP 직전에 {@code beforeCall}을 실행하고 입금을 요청한다.
	 * 미뤄둔 건의 "보냈다" 표시가 정확히 그 자리에 와야 해서 둔다.
	 */
	public ExternalCreditResult credit(ExternalCreditRequest request, Runnable beforeCall) {
		// 격벽. 자리가 없으면 기다리지 않고 거절한다 —
		// 기다리면 스레드가 묶이는 것은 똑같아서 격벽의 의미가 사라진다.
		return bulkhead.call(() -> circuitBreaker.call(request.bankCode(), beforeCall,
				() -> send(request)));
	}

	/**
	 * 이미 보낸 입금의 결과를 묻는다.
	 *
	 * 회로로 막지 않는다. 새 입금을 지키려다 CREDIT_UNKNOWN 해소까지 늦추면 안 된다.
	 * 부하는 격벽과 확인 루프의 백오프로만 제한한다.
	 */
	public ExternalCreditResult inquire(String bankCode, UUID transferId) {
		return bulkhead.call(() -> externalBankClient.inquire(bankCode, transferId));
	}

	private ExternalCreditResult send(ExternalCreditRequest request) {
		return externalBankClient.credit(request.bankCode(), request.transferId(),
				request.accountNumber(), request.amount(), request.currency());
	}
}
