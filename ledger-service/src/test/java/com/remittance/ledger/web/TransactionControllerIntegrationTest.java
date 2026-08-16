package com.remittance.ledger.web;

import com.remittance.ledger.AbstractMongoIntegrationTest;
import com.remittance.ledger.domain.TransactionDirection;
import com.remittance.ledger.web.dto.RecordTransactionRequest;
import com.remittance.ledger.web.dto.TransactionPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@AutoConfigureWebTestClient
class TransactionControllerIntegrationTest extends AbstractMongoIntegrationTest {

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void 원장_기록후_계좌별_조회() {
		UUID accountId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();

		webTestClient.post().uri("/internal/transactions")
				.bodyValue(List.of(
						new RecordTransactionRequest(transferId, accountId, TransactionDirection.DEBIT,
								BigDecimal.valueOf(100), BigDecimal.valueOf(900)),
						new RecordTransactionRequest(transferId, accountId, TransactionDirection.CREDIT,
								BigDecimal.valueOf(50), BigDecimal.valueOf(950))))
				.exchange()
				.expectStatus().isCreated();

		webTestClient.get().uri("/accounts/" + accountId + "/transactions")
				.exchange()
				.expectStatus().isOk()
				.expectBody(TransactionPageResponse.class)
				.value(page -> {
					org.assertj.core.api.Assertions.assertThat(page.items()).hasSize(2);
					org.assertj.core.api.Assertions.assertThat(page.hasNext()).isFalse();
				});
	}

	@Test
	void 페이지네이션_cursor로_다음페이지_조회() {
		UUID accountId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();

		webTestClient.post().uri("/internal/transactions")
				.bodyValue(List.of(
						new RecordTransactionRequest(transferId, accountId, TransactionDirection.DEBIT,
								BigDecimal.valueOf(10), BigDecimal.valueOf(90)),
						new RecordTransactionRequest(transferId, accountId, TransactionDirection.DEBIT,
								BigDecimal.valueOf(20), BigDecimal.valueOf(70)),
						new RecordTransactionRequest(transferId, accountId, TransactionDirection.DEBIT,
								BigDecimal.valueOf(30), BigDecimal.valueOf(40))))
				.exchange()
				.expectStatus().isCreated();

		TransactionPageResponse firstPage = webTestClient.get()
				.uri("/accounts/" + accountId + "/transactions?size=2")
				.exchange()
				.expectStatus().isOk()
				.expectBody(TransactionPageResponse.class)
				.returnResult().getResponseBody();

		org.assertj.core.api.Assertions.assertThat(firstPage.items()).hasSize(2);
		org.assertj.core.api.Assertions.assertThat(firstPage.hasNext()).isTrue();

		webTestClient.get()
				.uri("/accounts/" + accountId + "/transactions?size=2&cursor=" + firstPage.nextCursor())
				.exchange()
				.expectStatus().isOk()
				.expectBody(TransactionPageResponse.class)
				.value(page -> {
					org.assertj.core.api.Assertions.assertThat(page.items()).hasSize(1);
					org.assertj.core.api.Assertions.assertThat(page.hasNext()).isFalse();
				});
	}

	@Test
	void 존재하지_않는_거래_조회시_404() {
		webTestClient.get().uri("/transactions/" + UUID.randomUUID())
				.exchange()
				.expectStatus().isNotFound();
	}
}
