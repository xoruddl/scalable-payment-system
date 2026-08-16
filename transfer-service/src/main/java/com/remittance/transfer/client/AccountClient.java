package com.remittance.transfer.client;

import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.client.dto.AdjustBalanceRequest;
import com.remittance.transfer.client.dto.ErrorResponse;
import com.remittance.transfer.exception.AccountNotActiveException;
import com.remittance.transfer.exception.AccountNotFoundException;
import com.remittance.transfer.exception.AccountServiceException;
import com.remittance.transfer.exception.CurrencyMismatchException;
import com.remittance.transfer.exception.InsufficientBalanceException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Account Service의 internal API를 호출하는 동기 클라이언트.
 * Phase 2에서 이 호출 실패 지점에 Saga 보상 트랜잭션이 얹힌다.
 */
@Component
public class AccountClient {

	private final RestClient restClient;

	public AccountClient(RestClient accountRestClient) {
		this.restClient = accountRestClient;
	}

	public AccountBalanceResponse debit(UUID accountId, BigDecimal amount, String currency, UUID transferId) {
		return adjustBalance(accountId, "debit", amount, currency, transferId);
	}

	public AccountBalanceResponse credit(UUID accountId, BigDecimal amount, String currency, UUID transferId) {
		return adjustBalance(accountId, "credit", amount, currency, transferId);
	}

	private AccountBalanceResponse adjustBalance(UUID accountId, String operation, BigDecimal amount,
			String currency, UUID transferId) {
		try {
			return restClient.post()
					.uri("/internal/accounts/{accountId}/{operation}", accountId, operation)
					.body(new AdjustBalanceRequest(amount, currency, transferId))
					.retrieve()
					.body(AccountBalanceResponse.class);
		} catch (RestClientResponseException e) {
			throw translate(accountId, e);
		} catch (RestClientException e) {
			throw new AccountServiceException("Account Service 호출에 실패했습니다: " + accountId, e);
		}
	}

	private RuntimeException translate(UUID accountId, RestClientResponseException e) {
		String code = extractErrorCode(e);
		return switch (code) {
			case "ACCOUNT_NOT_FOUND" -> new AccountNotFoundException(accountId);
			case "INSUFFICIENT_BALANCE" -> new InsufficientBalanceException(accountId);
			case "ACCOUNT_NOT_ACTIVE" -> new AccountNotActiveException(accountId);
			case "CURRENCY_MISMATCH" -> new CurrencyMismatchException(accountId);
			default -> new AccountServiceException(
					"Account Service가 예상치 못한 오류를 반환했습니다 (accountId=" + accountId + ", status="
							+ e.getStatusCode() + ")", e);
		};
	}

	private String extractErrorCode(RestClientResponseException e) {
		try {
			return e.getResponseBodyAs(ErrorResponse.class).code();
		} catch (Exception parseError) {
			return "UNKNOWN";
		}
	}
}
