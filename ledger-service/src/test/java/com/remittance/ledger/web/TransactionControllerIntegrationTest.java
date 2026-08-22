package com.remittance.ledger.web;

import com.remittance.ledger.AbstractIntegrationTest;
import com.remittance.ledger.domain.BalanceChangeReason;
import com.remittance.ledger.domain.TransactionDirection;
import com.remittance.ledger.messaging.AccountEvents;
import com.remittance.ledger.service.TransactionService;
import com.remittance.ledger.web.dto.TransactionPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 API. Step 5a에서 원장 기록 경로가 REST에서 이벤트로 바뀌었으므로,
 * 여기서는 서비스로 직접 원장을 채운 뒤 <b>조회 쪽만</b> 본다.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class TransactionControllerIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private TransactionService transactionService;

	private void seedEntry(UUID accountId, UUID transferId, BigDecimal amount, BigDecimal balanceAfter) {
		transactionService.record(new AccountEvents.BalanceChanged(
				UUID.randomUUID(), accountId, BalanceChangeReason.TRANSFER_DEBIT,
				TransactionDirection.DEBIT, amount, balanceAfter, "KRW", transferId, Instant.now())).block();
	}

	@Test
	void 원장_기록후_계좌별_조회() {
		UUID accountId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();

		seedEntry(accountId, transferId, BigDecimal.valueOf(100), BigDecimal.valueOf(900));
		seedEntry(accountId, transferId, BigDecimal.valueOf(50), BigDecimal.valueOf(850));

		webTestClient.get().uri("/accounts/" + accountId + "/transactions")
				.exchange()
				.expectStatus().isOk()
				.expectBody(TransactionPageResponse.class)
				.value(page -> {
					assertThat(page.items()).hasSize(2);
					assertThat(page.hasNext()).isFalse();
				});
	}

	@Test
	void 페이지네이션_cursor로_다음페이지_조회() {
		UUID accountId = UUID.randomUUID();

		seedEntry(accountId, UUID.randomUUID(), BigDecimal.valueOf(10), BigDecimal.valueOf(90));
		seedEntry(accountId, UUID.randomUUID(), BigDecimal.valueOf(20), BigDecimal.valueOf(70));
		seedEntry(accountId, UUID.randomUUID(), BigDecimal.valueOf(30), BigDecimal.valueOf(40));

		TransactionPageResponse firstPage = webTestClient.get()
				.uri("/accounts/" + accountId + "/transactions?size=2")
				.exchange()
				.expectStatus().isOk()
				.expectBody(TransactionPageResponse.class)
				.returnResult().getResponseBody();

		assertThat(firstPage.items()).hasSize(2);
		assertThat(firstPage.hasNext()).isTrue();

		webTestClient.get()
				.uri("/accounts/" + accountId + "/transactions?size=2&cursor=" + firstPage.nextCursor())
				.exchange()
				.expectStatus().isOk()
				.expectBody(TransactionPageResponse.class)
				.value(page -> {
					assertThat(page.items()).hasSize(1);
					assertThat(page.hasNext()).isFalse();
				});
	}

	@Test
	void 존재하지_않는_거래_조회시_404() {
		webTestClient.get().uri("/transactions/" + UUID.randomUUID())
				.exchange()
				.expectStatus().isNotFound();
	}
}
