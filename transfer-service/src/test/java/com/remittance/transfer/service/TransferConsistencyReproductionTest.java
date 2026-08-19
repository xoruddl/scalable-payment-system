package com.remittance.transfer.service;

import com.remittance.transfer.client.AccountClient;
import com.remittance.transfer.client.LedgerClient;
import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.AccountServiceException;
import com.remittance.transfer.exception.InsufficientBalanceException;
import com.remittance.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * Phase 2 Step 0 — 문제 재현 테스트 (현재는 실패한다).
 *
 * Phase 1의 송금 흐름은 두 곳에서 정합성이 깨진다.
 * 1) 원장 기록이 best-effort라, 실패해도 송금은 COMPLETED로 끝난다 → 잔액과 원장이 어긋남
 * 2) 보상(환불)을 요청 스레드 안에서 딱 한 번만 시도한다 → 그 한 번이 실패하면 출금액이 영구히 사라짐
 *
 * Step 3~4(Outbox + Choreography Saga)에서 이벤트 기반 재시도가 들어가면 green이 된다.
 */
@ExtendWith(MockitoExtension.class)
class TransferConsistencyReproductionTest {

	@Mock
	private TransferRepository transferRepository;

	@Mock
	private AccountClient accountClient;

	private final UUID fromAccountId = UUID.randomUUID();
	private final UUID toAccountId = UUID.randomUUID();
	private final BigDecimal amount = BigDecimal.valueOf(1_000);

	private void stubSaveReturnsArgument() {
		given(transferRepository.save(any(Transfer.class))).willAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void 원장_기록이_실패하면_송금을_COMPLETED로_끝내면_안_된다() {
		stubSaveReturnsArgument();
		given(accountClient.debit(eq(fromAccountId), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(4_000), "KRW", 2L));
		given(accountClient.credit(eq(toAccountId), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(toAccountId, BigDecimal.valueOf(6_000), "KRW", 2L));

		// 실제 LedgerClient를 쓰되 원장 서비스가 500을 반환하도록 만든다.
		// (LedgerClient가 예외를 삼키고 로그만 남기는 현재 동작을 그대로 태우기 위함)
		RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger-service");
		MockRestServiceServer ledgerServer = MockRestServiceServer.bindTo(builder).build();
		ledgerServer.expect(requestTo("http://ledger-service/internal/transactions"))
				.andRespond(withServerError());
		LedgerClient ledgerClient = new LedgerClient(builder.build());

		TransferService transferService = new TransferService(transferRepository, accountClient, ledgerClient);

		Transfer result = transferService.requestTransfer(fromAccountId, toAccountId, amount, "KRW", null);

		assertThat(result.getStatus())
				.as("원장 기록이 실패했는데 COMPLETED로 끝나면 잔액과 원장이 영구히 어긋난다")
				.isNotEqualTo(TransferStatus.COMPLETED);
	}

	@Test
	void 보상_환불이_한_번_실패해도_최종적으로_잔액이_복구되어야_한다() {
		stubSaveReturnsArgument();
		given(accountClient.debit(eq(fromAccountId), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(4_000), "KRW", 2L));
		// 입금 실패 → 보상(환불) 필요
		given(accountClient.credit(eq(toAccountId), any(), any(), any()))
				.willThrow(new InsufficientBalanceException(toAccountId));
		// 환불도 첫 시도는 일시적 장애로 실패, 재시도하면 성공하는 상황
		given(accountClient.credit(eq(fromAccountId), any(), any(), any()))
				.willThrow(new AccountServiceException("일시적 장애", new RuntimeException()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(5_000), "KRW", 3L));

		TransferService transferService = new TransferService(
				transferRepository, accountClient, Mockito.mock(LedgerClient.class));

		Transfer result = transferService.requestTransfer(fromAccountId, toAccountId, amount, "KRW", null);

		verify(accountClient, atLeast(2))
				.credit(eq(fromAccountId), any(), any(), any());
		assertThat(result.getFailureReason())
				.as("보상이 재시도되어 결국 성공했다면 수동 개입이 필요하다고 남기면 안 된다")
				.doesNotContain("수동 개입");
	}
}
