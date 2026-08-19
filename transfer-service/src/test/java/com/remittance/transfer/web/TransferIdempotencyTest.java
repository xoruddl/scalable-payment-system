package com.remittance.transfer.web;

import com.remittance.transfer.client.AccountClient;
import com.remittance.transfer.client.LedgerClient;
import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.domain.IdempotencyStatus;
import com.remittance.transfer.exception.AccountServiceException;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Step 1 — 멱등성 처리 검증.
 * 중복 요청 자체의 재현은 {@code TransferIdempotencyReproductionTest}가 담당하고,
 * 여기서는 그 외 분기(다른 payload, 헤더 누락, 응답 동일성)를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferIdempotencyTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	@MockitoBean
	private AccountClient accountClient;

	@MockitoBean
	private LedgerClient ledgerClient;

	private void stubAccountSuccess() {
		given(accountClient.debit(any(), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(UUID.randomUUID(), BigDecimal.valueOf(7_000), "KRW", 1L));
		given(accountClient.credit(any(), any(), any(), any()))
				.willReturn(new AccountBalanceResponse(UUID.randomUUID(), BigDecimal.valueOf(3_000), "KRW", 1L));
	}

	private String body(UUID from, UUID to, int amount) {
		return objectMapper.writeValueAsString(Map.of(
				"fromAccountId", from, "toAccountId", to,
				"amount", BigDecimal.valueOf(amount), "currency", "KRW"));
	}

	@Test
	void 재요청은_최초와_완전히_동일한_응답을_돌려준다() throws Exception {
		stubAccountSuccess();
		String key = UUID.randomUUID().toString();
		String payload = body(UUID.randomUUID(), UUID.randomUUID(), 3_000);

		String first = mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		String second = mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
	}

	@Test
	void 같은_키로_다른_payload를_보내면_422() throws Exception {
		stubAccountSuccess();
		String key = UUID.randomUUID().toString();

		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID(), UUID.randomUUID(), 3_000)))
				.andExpect(status().isAccepted());

		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID(), UUID.randomUUID(), 9_999)))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void 금액_표기만_다른_같은_요청은_같은_요청으로_본다() throws Exception {
		stubAccountSuccess();
		String key = UUID.randomUUID().toString();
		UUID from = UUID.randomUUID();
		UUID to = UUID.randomUUID();

		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to
								+ "\",\"amount\":3000,\"currency\":\"KRW\"}"))
				.andExpect(status().isAccepted());

		// 3000 과 3000.00 은 같은 송금이므로 422가 아니라 최초 결과가 반환되어야 한다
		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to
								+ "\",\"amount\":3000.00,\"currency\":\"KRW\"}"))
				.andExpect(status().isAccepted());
	}

	@Test
	void Idempotency_Key_헤더가_없으면_400() throws Exception {
		mockMvc.perform(post("/transfers")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID(), UUID.randomUUID(), 3_000)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_HEADER"));
	}

	/**
	 * 출금 호출이 타임아웃 등으로 예외를 던졌을 때, 실제로는 출금이 성공했을 수 있다.
	 * 이때 키를 놓아주면 재시도가 이중 출금이 되므로, 실패해도 키는 반드시 남아야 한다.
	 */
	@Test
	void 출금이_예외로_끝나도_키가_남아_재시도가_재실행되지_않는다() throws Exception {
		given(accountClient.debit(any(), any(), any(), any()))
				.willThrow(new AccountServiceException("타임아웃", new RuntimeException()));

		String key = UUID.randomUUID().toString();
		String payload = body(UUID.randomUUID(), UUID.randomUUID(), 3_000);

		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isServiceUnavailable());

		assertThat(idempotencyKeyRepository.findById(key))
				.as("실패한 요청의 키도 남아 있어야 한다")
				.get()
				.satisfies(saved -> {
					assertThat(saved.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
					assertThat(saved.getTransferId()).isNotNull();
				});

		// 재시도는 출금을 다시 호출하지 않고 저장된 결과를 돌려준다
		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isAccepted());

		verify(accountClient, times(1)).debit(any(), any(), any(), any());
	}

	@Test
	void 처리가_끝나면_키가_COMPLETED로_기록된다() throws Exception {
		stubAccountSuccess();
		String key = UUID.randomUUID().toString();

		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID(), UUID.randomUUID(), 3_000)))
				.andExpect(status().isAccepted());

		assertThat(idempotencyKeyRepository.findById(key))
				.get()
				.satisfies(saved -> {
					assertThat(saved.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
					assertThat(saved.getTransferId()).isNotNull();
				});
	}
}
