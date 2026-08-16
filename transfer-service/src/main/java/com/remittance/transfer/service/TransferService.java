package com.remittance.transfer.service;

import com.remittance.transfer.client.AccountClient;
import com.remittance.transfer.client.LedgerClient;
import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.client.dto.RecordTransactionRequest;
import com.remittance.transfer.client.dto.TransactionDirection;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.exception.TransferNotFoundException;
import com.remittance.transfer.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1의 송금 오케스트레이션은 단일 요청 스레드 내에서 출금 → 입금을 순차 호출하고,
 * 입금 실패 시 즉시 보상(환불)하는 최소 구현이다.
 * 정식 Saga(Choreography)/이벤트 기반 보상 트랜잭션은 Phase 2에서 설계한다.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

	private static final Logger log = LoggerFactory.getLogger(TransferService.class);

	private final TransferRepository transferRepository;
	private final AccountClient accountClient;
	private final LedgerClient ledgerClient;

	public Transfer requestTransfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency,
			String memo) {
		if (fromAccountId.equals(toAccountId)) {
			throw new InvalidTransferRequestException("출금 계좌와 입금 계좌가 동일할 수 없습니다.");
		}

		Transfer transfer = transferRepository.save(
				Transfer.builder()
						.fromAccountId(fromAccountId)
						.toAccountId(toAccountId)
						.amount(amount)
						.currency(currency)
						.memo(memo)
						.build());

		AccountBalanceResponse debited = debit(transfer);
		AccountBalanceResponse credited = credit(transfer);

		if (credited != null) {
			recordLedger(transfer, debited, credited);
		}
		return transfer;
	}

	private AccountBalanceResponse debit(Transfer transfer) {
		try {
			AccountBalanceResponse response = accountClient.debit(
					transfer.getFromAccountId(), transfer.getAmount(), transfer.getCurrency(),
					transfer.getTransferId());
			transfer.markDebitCompleted();
			transferRepository.save(transfer);
			return response;
		} catch (RuntimeException e) {
			transfer.markFailed("출금 실패: " + e.getMessage());
			transferRepository.save(transfer);
			throw e;
		}
	}

	private AccountBalanceResponse credit(Transfer transfer) {
		try {
			AccountBalanceResponse response = accountClient.credit(
					transfer.getToAccountId(), transfer.getAmount(), transfer.getCurrency(),
					transfer.getTransferId());
			transfer.markCreditCompleted();
			transfer.markCompleted();
			transferRepository.save(transfer);
			return response;
		} catch (RuntimeException e) {
			compensateDebit(transfer, e);
			return null;
		}
	}

	private void compensateDebit(Transfer transfer, RuntimeException creditFailure) {
		transfer.markCompensating();
		transferRepository.save(transfer);
		try {
			accountClient.credit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getCurrency(),
					transfer.getTransferId());
			transfer.markFailed("입금 실패, 출금 보상 완료: " + creditFailure.getMessage());
		} catch (RuntimeException compensationFailure) {
			log.error("보상 트랜잭션 실패 - 수동 개입 필요 (transferId={})", transfer.getTransferId(), compensationFailure);
			transfer.markFailed("보상 실패 - 수동 개입 필요: " + creditFailure.getMessage());
		}
		transferRepository.save(transfer);
	}

	private void recordLedger(Transfer transfer, AccountBalanceResponse debited, AccountBalanceResponse credited) {
		ledgerClient.recordTransactions(List.of(
				new RecordTransactionRequest(transfer.getTransferId(), transfer.getFromAccountId(),
						TransactionDirection.DEBIT, transfer.getAmount(), debited.balance()),
				new RecordTransactionRequest(transfer.getTransferId(), transfer.getToAccountId(),
						TransactionDirection.CREDIT, transfer.getAmount(), credited.balance())
		));
	}

	public Transfer getTransfer(UUID transferId) {
		return transferRepository.findByTransferId(transferId)
				.orElseThrow(() -> new TransferNotFoundException(transferId));
	}
}
