package com.remittance.account.web;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String createAccount(String currency) throws Exception {
		String body = objectMapper.writeValueAsString(Map.of("ownerId", UUID.randomUUID(), "currency", currency));
		String response = mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("accountId").asText();
	}

	@Test
	void 계좌_생성후_조회() throws Exception {
		String accountId = createAccount("KRW");

		mockMvc.perform(get("/accounts/" + accountId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(accountId));
	}

	@Test
	void 존재하지_않는_계좌_조회시_404() throws Exception {
		mockMvc.perform(get("/accounts/" + UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	@Test
	void 내부_입금_출금_후_잔액조회() throws Exception {
		String accountId = createAccount("KRW");

		mockMvc.perform(post("/internal/accounts/" + accountId + "/credit")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("amount", BigDecimal.valueOf(1000), "currency", "KRW"))))
				.andExpect(status().isOk());

		mockMvc.perform(post("/internal/accounts/" + accountId + "/debit")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("amount", BigDecimal.valueOf(300), "currency", "KRW"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(700));

		mockMvc.perform(get("/accounts/" + accountId + "/balance"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(700));
	}

	@Test
	void 잔액_부족시_출금은_409() throws Exception {
		String accountId = createAccount("KRW");

		mockMvc.perform(post("/internal/accounts/" + accountId + "/debit")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("amount", BigDecimal.valueOf(100), "currency", "KRW"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
	}

	@Test
	void 통화_불일치시_400() throws Exception {
		String accountId = createAccount("KRW");

		mockMvc.perform(post("/internal/accounts/" + accountId + "/credit")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of("amount", BigDecimal.valueOf(100), "currency", "USD"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
	}
}
