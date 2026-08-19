package com.remittance.transfer.web;

import com.remittance.transfer.domain.IdempotencyStatus;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Step 1 — 멱등성 처리 검증.
 * 중복 요청 자체의 재현은 {@code TransferIdempotencyReproductionTest}가 담당하고,
 * 여기서는 그 외 분기(다른 payload, 헤더 누락, 응답 동일성)를 확인한다.
 *
 * <p>Step 4a 이후 이 진입점은 송금을 <b>접수</b>만 한다. 따라서 여기서 말하는 "완료"는
 * 송금이 끝났다는 뜻이 아니라 접수가 끝났다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferIdempotencyTest extends com.remittance.transfer.AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	private String body(UUID from, UUID to, int amount) {
		return objectMapper.writeValueAsString(Map.of(
				"fromAccountId", from, "toAccountId", to,
				"amount", BigDecimal.valueOf(amount), "currency", "KRW"));
	}

	@Test
	void 재요청은_최초와_완전히_동일한_응답을_돌려준다() throws Exception {
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

	@Test
	void 접수가_끝나면_키가_COMPLETED로_기록된다() throws Exception {
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
