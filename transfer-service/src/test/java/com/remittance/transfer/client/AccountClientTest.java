package com.remittance.transfer.client;

import com.remittance.transfer.exception.AccountNotFoundException;
import com.remittance.transfer.exception.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientTest {

	private MockRestServiceServer server;
	private AccountClient accountClient;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://account-service");
		server = MockRestServiceServer.bindTo(builder).build();
		accountClient = new AccountClient(builder.build());
	}

	@Test
	void 정상_응답을_파싱한다() {
		UUID accountId = UUID.randomUUID();
		server.expect(requestTo("http://account-service/internal/accounts/" + accountId + "/debit"))
				.andExpect(method(org.springframework.http.HttpMethod.POST))
				.andRespond(withSuccess(
						"{\"accountId\":\"" + accountId + "\",\"balance\":700,\"currency\":\"KRW\",\"version\":2}",
						MediaType.APPLICATION_JSON));

		var response = accountClient.debit(accountId, BigDecimal.valueOf(300), "KRW", UUID.randomUUID());

		assertThat(response.balance()).isEqualByComparingTo("700");
	}

	@Test
	void ACCOUNT_NOT_FOUND_코드는_전용_예외로_변환된다() {
		UUID accountId = UUID.randomUUID();
		server.expect(requestTo("http://account-service/internal/accounts/" + accountId + "/debit"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.body("{\"code\":\"ACCOUNT_NOT_FOUND\",\"message\":\"not found\",\"traceId\":\"t1\"}")
						.contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> accountClient.debit(accountId, BigDecimal.TEN, "KRW", UUID.randomUUID()))
				.isInstanceOf(AccountNotFoundException.class);
	}

	@Test
	void INSUFFICIENT_BALANCE_코드는_전용_예외로_변환된다() {
		UUID accountId = UUID.randomUUID();
		server.expect(requestTo("http://account-service/internal/accounts/" + accountId + "/debit"))
				.andRespond(withStatus(HttpStatus.CONFLICT)
						.body("{\"code\":\"INSUFFICIENT_BALANCE\",\"message\":\"no money\",\"traceId\":\"t1\"}")
						.contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> accountClient.debit(accountId, BigDecimal.TEN, "KRW", UUID.randomUUID()))
				.isInstanceOf(InsufficientBalanceException.class);
	}
}
