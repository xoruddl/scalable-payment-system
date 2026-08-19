package com.remittance.transfer.web;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 접수 자체가 실패했을 때의 멱등성 키 처리.
 *
 * <p>접수 도중 예외가 나면 우리는 <b>송금이 저장됐는지 모른다</b> — 커밋 직전에 죽었을 수도 있다.
 * 이때 키를 놓아주면 재시도가 두 번째 송금을 만들 수 있으므로, 키는 남아야 하고 재시도는 409로 막힌다.
 * 정리되지 않고 남는 키는 Step 5의 배치에서 다룬다.
 *
 * <p>Phase 2 초반에 이 지점을 잘못 짜서 키를 삭제했고, 그 결과 재시도가 이중 출금이 될 수 있었다.
 * 그때는 수동 e2e로 발견했지만, 이제 이 테스트가 잡는다.
 *
 * <p>레코더를 목으로 바꾸므로 다른 테스트와 클래스를 나눈다 (한 클래스 전체에 적용되기 때문).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferAcceptanceFailureTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	@Autowired
	private TransferRepository transferRepository;

	/** 송금 저장 + Outbox 기록이 통째로 실패하는 상황 (DB 장애 등). */
	@MockitoBean
	private TransferOutboxRecorder outboxRecorder;

	@Test
	void 접수가_예외로_끝나도_키가_남아_재시도가_막힌다() throws Exception {
		given(outboxRecorder.record(any(), any())).willThrow(new RuntimeException("DB 커밋 실패"));

		String key = UUID.randomUUID().toString();
		UUID from = UUID.randomUUID();
		String payload = objectMapper.writeValueAsString(Map.of(
				"fromAccountId", from, "toAccountId", UUID.randomUUID(),
				"amount", BigDecimal.valueOf(3_000), "currency", "KRW"));

		// 처리되지 않은 예외는 MockMvc가 그대로 던진다 (서버라면 500이 된다).
		assertThatThrownBy(() -> mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(payload)))
				.hasRootCauseMessage("DB 커밋 실패");

		assertThat(idempotencyKeyRepository.findById(key))
				.as("접수 결과를 모르는 상태에서 키를 놓아주면 안 된다")
				.isPresent();

		mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));

		assertThat(transferRepository.findAll())
				.as("두 번째 송금이 만들어지면 안 된다")
				.noneMatch(transfer -> transfer.getFromAccountId().equals(from));
	}
}
