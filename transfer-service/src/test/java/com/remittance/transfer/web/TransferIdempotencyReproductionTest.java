package com.remittance.transfer.web;

import com.remittance.transfer.repository.TransferRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Step 0에서 문제를 드러내기 위해 쓴 테스트.
 *
 * {@code TransferController}가 Idempotency-Key 헤더를 받기만 하고 쓰지 않기 때문에,
 * 같은 키로 재요청하면 송금이 그대로 한 번 더 접수된다 (= 이중 송금).
 * 네트워크 타임아웃 후 클라이언트가 재시도하는 흔한 상황에서 돈이 두 번 빠지는 시나리오.
 *
 * Step 1에서 멱등성 키 처리를 붙여 green이 되었고, 이후 회귀를 막는 역할로 남아 있다.
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

	@Test
	void 동일한_Idempotency_Key로_두_번_요청하면_송금은_한_번만_처리되어야_한다() throws Exception {
		UUID fromAccountId = UUID.randomUUID();
		UUID toAccountId = UUID.randomUUID();

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
	}
}
