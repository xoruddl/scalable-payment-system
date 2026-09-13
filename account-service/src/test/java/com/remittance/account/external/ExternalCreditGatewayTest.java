package com.remittance.account.external;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 새 입금과 조회가 서로 다른 보호를 받는가.
 *
 * 둘은 같은 문을 지나지만 회로는 새 입금에만 걸린다. 조회까지 회로에 막히면
 * 계속 실패하는 은행에서 CREDIT_UNKNOWN이 영영 풀리지 않는다 — 결과를 모르는 돈이
 * 회로가 닫힐 때까지 모르는 채로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCreditGatewayTest {

	private static final String BANK = "KB";

	@Mock
	private ExternalBankClient externalBankClient;

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final ExternalCallCircuitBreaker circuitBreaker =
			new ExternalCallCircuitBreaker(5, Duration.ofSeconds(30), meters);

	private ExternalCreditGateway gateway() {
		return new ExternalCreditGateway(externalBankClient, new ExternalCallBulkhead(2, meters), circuitBreaker);
	}

	@Test
	void 새_입금은_요청을_그대로_상대에게_보낸다() {
		UUID transferId = UUID.randomUUID();
		ExternalCreditResult accepted = new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null);
		given(externalBankClient.credit(BANK, transferId, "110-222", BigDecimal.TEN, "KRW"))
				.willReturn(accepted);

		ExternalCreditResult result = gateway().credit(
				new ExternalCreditRequest(BANK, transferId, "110-222", BigDecimal.TEN, "KRW"));

		assertThat(result).isSameAs(accepted);
	}

	@Test
	void 회로가_열리면_새_입금은_상대를_부르지_않는다() {
		circuitBreaker.circuit(BANK).transitionToOpenState();

		assertThatThrownBy(() -> gateway().credit(
				new ExternalCreditRequest(BANK, UUID.randomUUID(), "110-222", BigDecimal.TEN, "KRW")))
				.isInstanceOf(CallNotPermittedException.class);
		verifyNoInteractions(externalBankClient);
	}

	@Test
	void 회로가_열려도_조회는_나간다() {
		UUID transferId = UUID.randomUUID();
		ExternalCreditResult accepted = new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null);
		given(externalBankClient.inquire(BANK, transferId)).willReturn(accepted);
		circuitBreaker.circuit(BANK).transitionToOpenState();

		ExternalCreditResult result = gateway().inquire(BANK, transferId);

		assertThat(result).as("이미 보낸 돈의 결과 확인은 회로로 막지 않는다").isSameAs(accepted);
		verify(externalBankClient).inquire(BANK, transferId);
	}
}
