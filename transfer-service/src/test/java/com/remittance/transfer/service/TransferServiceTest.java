package com.remittance.transfer.service;

import com.remittance.transfer.client.AccountClient;
import com.remittance.transfer.client.LedgerClient;
import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.client.dto.RecordTransactionRequest;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.InsufficientBalanceException;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

	@Mock
	private TransferRepository transferRepository;

	@Mock
	private AccountClient accountClient;

	@Mock
	private LedgerClient ledgerClient;

	@Mock
	private IdempotencyService idempotencyService;

	private TransferService transferService;

	private final UUID fromAccountId = UUID.randomUUID();
	private final UUID toAccountId = UUID.randomUUID();
	// Transfer가 금액을 scale 2로 정규화하므로, 스텁도 같은 표현이어야 매칭된다
	// (BigDecimal.equals()는 scale까지 비교한다)
	private final BigDecimal amount = new BigDecimal("1000.00");

	private void setUp() {
		transferService = new TransferService(transferRepository, accountClient, ledgerClient, idempotencyService);
	}

	private void stubSaveReturnsArgument() {
		given(transferRepository.save(any(Transfer.class))).willAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void 출금_입금_모두_성공하면_완료되고_원장에_기록된다() {
		setUp();
		stubSaveReturnsArgument();
		given(accountClient.debit(eq(fromAccountId), eq(amount), eq("KRW"), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(4000), "KRW", 2L));
		given(accountClient.credit(eq(toAccountId), eq(amount), eq("KRW"), any()))
				.willReturn(new AccountBalanceResponse(toAccountId, BigDecimal.valueOf(6000), "KRW", 2L));

		Transfer result = transferService.executeTransfer(fromAccountId, toAccountId, amount, "KRW", null);

		assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
		verify(ledgerClient).recordTransactions(List.of(
				new RecordTransactionRequest(result.getTransferId(), fromAccountId,
						com.remittance.transfer.client.dto.TransactionDirection.DEBIT, amount, BigDecimal.valueOf(4000)),
				new RecordTransactionRequest(result.getTransferId(), toAccountId,
						com.remittance.transfer.client.dto.TransactionDirection.CREDIT, amount, BigDecimal.valueOf(6000))
		));
	}

	@Test
	void 출금_실패시_실패로_저장되고_예외가_전파된다() {
		setUp();
		stubSaveReturnsArgument();
		given(accountClient.debit(eq(fromAccountId), eq(amount), eq("KRW"), any()))
				.willThrow(new InsufficientBalanceException(fromAccountId));

		assertThatThrownBy(() -> transferService.executeTransfer(fromAccountId, toAccountId, amount, "KRW", null))
				.isInstanceOf(InsufficientBalanceException.class);

		verify(accountClient, never()).credit(eq(toAccountId), any(), any(), any());
		verify(ledgerClient, never()).recordTransactions(any());
	}

	@Test
	void 입금_실패시_출금을_보상하고_실패로_종결한다() {
		setUp();
		stubSaveReturnsArgument();
		given(accountClient.debit(eq(fromAccountId), eq(amount), eq("KRW"), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(4000), "KRW", 2L));
		given(accountClient.credit(eq(toAccountId), eq(amount), eq("KRW"), any()))
				.willThrow(new InsufficientBalanceException(toAccountId));
		given(accountClient.credit(eq(fromAccountId), eq(amount), eq("KRW"), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(5000), "KRW", 3L));

		Transfer result = transferService.executeTransfer(fromAccountId, toAccountId, amount, "KRW", null);

		assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
		assertThat(result.getFailureReason()).contains("보상 완료");
		verify(accountClient, times(1)).credit(eq(fromAccountId), eq(amount), eq("KRW"), any());
		verify(ledgerClient, never()).recordTransactions(any());
	}

	@Test
	void 입금_실패후_보상마저_실패하면_수동개입_메시지를_남긴다() {
		setUp();
		stubSaveReturnsArgument();
		given(accountClient.debit(eq(fromAccountId), eq(amount), eq("KRW"), any()))
				.willReturn(new AccountBalanceResponse(fromAccountId, BigDecimal.valueOf(4000), "KRW", 2L));
		given(accountClient.credit(eq(toAccountId), eq(amount), eq("KRW"), any()))
				.willThrow(new InsufficientBalanceException(toAccountId));
		given(accountClient.credit(eq(fromAccountId), eq(amount), eq("KRW"), any()))
				.willThrow(new RuntimeException("account service down"));

		Transfer result = transferService.executeTransfer(fromAccountId, toAccountId, amount, "KRW", null);

		assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
		assertThat(result.getFailureReason()).contains("보상 실패");
	}

	@Test
	void 출금_입금_계좌가_같으면_멱등성_키를_소모하지_않고_즉시_예외() {
		setUp();
		CreateTransferRequest request =
				new CreateTransferRequest(fromAccountId, fromAccountId, amount, "KRW", null);

		assertThatThrownBy(() -> transferService.requestTransfer("key-1", request))
				.isInstanceOf(InvalidTransferRequestException.class);

		verify(transferRepository, never()).save(any());
		verify(idempotencyService, never()).reserve(any(), any());
	}
}
