package com.remittance.transfer.web;

import com.remittance.transfer.client.AccountClient;
import com.remittance.transfer.client.LedgerClient;
import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Step 0 — 문제 재현 테스트 (현재는 실패한다).
 *
 * {@code TransferController}가 Idempotency-Key 헤더를 받기만 하고 쓰지 않기 때문에,
 * 같은 키로 재요청하면 송금이 그대로 한 번 더 실행된다 (= 이중 송금).
 * 네트워크 타임아웃 후 클라이언트가 재시도하는 흔한 상황에서 돈이 두 번 빠지는 시나리오.
 *
 * Step 1에서 멱등성 키 처리를 붙이면 green이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferIdempotencyReproductionTest extends com.remittance.transfer.AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TransferRepository transferRepository;

	@MockitoBean
	private AccountClient accountClient;

	@MockitoBean
	private LedgerClient ledgerClient;

	@Test
	void 동일한_Idempotency_Key로_두_번_요청하면_송금은_한_번만_처리되어야_한다() throws Exception {
		UUID fromAccountId = UUID.randomUUID();
		UUID toAccountId = UUID.randomUUID();

		given(accountClient.debit(any(), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(7_000), "KRW", 1L));
		given(accountClient.credit(any(), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(toAccountId, BigDecimal.valueOf(3_000), "KRW", 1L));

		String idempotencyKey = UUID.randomUUID().toString();
		String body = objectMapper.writeValueAsString(Map.of(
				"fromAccountId", fromAccountId,
				"toAccountId", toAccountId,
				"amount", BigDecimal.valueOf(3_000),
				"currency", "KRW"));

		mockMvc.perform(post("/transfers")
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted());

		// 클라이언트가 응답을 못 받아 동일 키로 재시도한 상황
		mockMvc.perform(post("/transfers")
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isAccepted());

		long createdTransfers = transferRepository.findAll().stream()
				.filter(transfer -> transfer.getFromAccountId().equals(fromAccountId))
				.count();

		assertThat(createdTransfers)
				.as("동일 Idempotency-Key 재요청은 새 송금을 만들지 않아야 한다")
				.isEqualTo(1);
		verify(accountClient, times(1)).debit(eq(fromAccountId), any(), any(), any());
	}
}
