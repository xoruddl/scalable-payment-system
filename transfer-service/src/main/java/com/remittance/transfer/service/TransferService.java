package com.remittance.transfer.service;

import com.remittance.transfer.client.AccountClient;
import com.remittance.transfer.client.LedgerClient;
import com.remittance.transfer.client.dto.AccountBalanceResponse;
import com.remittance.transfer.client.dto.RecordTransactionRequest;
import com.remittance.transfer.client.dto.TransactionDirection;
import com.remittance.transfer.domain.IdempotencyKey;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.IdempotencyConflictException;
import com.remittance.transfer.exception.IdempotencyInProgressException;
import com.remittance.transfer.exception.InvalidTransferRequestException;
import com.remittance.transfer.exception.TransferNotFoundException;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1의 송금 오케스트레이션은 단일 요청 스레드 내에서 출금 → 입금을 순차 호출하고,
 * 입금 실패 시 즉시 보상(환불)하는 최소 구현이다.
 * 정식 Saga(Choreography)/이벤트 기반 보상 트랜잭션은 Phase 2 Step 4에서 다룬다.
 *
 * <p>Phase 2 Step 1에서 진입점에 멱등성 처리를 추가했다.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

	private static final Logger log = LoggerFactory.getLogger(TransferService.class);

	private final TransferRepository transferRepository;
	private final AccountClient accountClient;
	private final LedgerClient ledgerClient;
	private final IdempotencyService idempotencyService;

	/**
	 * 송금 요청의 공개 진입점. 같은 Idempotency-Key로 다시 들어온 요청은 재처리하지 않고
	 * 최초 처리 결과를 그대로 돌려준다.
	 */
	public Transfer requestTransfer(String idempotencyKey, CreateTransferRequest request) {
		// 요청 자체가 잘못된 경우는 키를 소모하지 않는다 (처리를 시작한 적이 없으므로).
		validate(request);

		String requestHash = idempotencyService.hash(request);
		try {
			idempotencyService.reserve(idempotencyKey, requestHash);
		} catch (DataIntegrityViolationException alreadyReserved) {
			// 같은 키가 이미 존재한다 = 재요청이거나 동시에 들어온 중복 요청
			return replay(idempotencyKey, requestHash);
		}

		// 송금 레코드를 먼저 만들어 참조를 잡아둔다. Saga가 예외를 던지더라도 어떤 송금이었는지
		// 키에 기록해야 하기 때문이다. (기록하지 못하고 키를 놓아주면, 출금이 실제로는 성공했는데
		// 응답만 유실된 경우 재시도가 이중 출금이 된다.)
		Transfer transfer = createTransfer(request);
		try {
			runSaga(transfer);
		} catch (RuntimeException e) {
			idempotencyService.fail(idempotencyKey, transfer.getTransferId());
			throw e;
		}
		markKeyTerminal(idempotencyKey, transfer);
		return transfer;
	}

	/** 송금의 최종 상태를 키에도 반영한다. 어느 쪽이든 terminal이므로 재요청은 저장된 결과를 받는다. */
	private void markKeyTerminal(String idempotencyKey, Transfer transfer) {
		if (transfer.getStatus() == TransferStatus.FAILED) {
			idempotencyService.fail(idempotencyKey, transfer.getTransferId());
		} else {
			idempotencyService.complete(idempotencyKey, transfer.getTransferId());
		}
	}

	private void validate(CreateTransferRequest request) {
		if (request.fromAccountId().equals(request.toAccountId())) {
			throw new InvalidTransferRequestException("출금 계좌와 입금 계좌가 동일할 수 없습니다.");
		}
	}

	/**
	 * 이미 사용된 키로 들어온 요청을 처리한다.
	 * payload가 다르면 충돌, 아직 처리 중이면 409, 끝났으면 저장된 결과를 돌려준다.
	 */
	private Transfer replay(String idempotencyKey, String requestHash) {
		IdempotencyKey existing = idempotencyService.find(idempotencyKey)
				.orElseThrow(() -> new IdempotencyInProgressException(idempotencyKey));

		if (!existing.matches(requestHash)) {
			throw new IdempotencyConflictException(idempotencyKey);
		}
		if (!existing.isTerminal() || existing.getTransferId() == null) {
			throw new IdempotencyInProgressException(idempotencyKey);
		}

		log.info("멱등 재요청 - 저장된 결과를 반환한다 (key={}, transferId={})",
				idempotencyKey, existing.getTransferId());
		return transferRepository.findByTransferId(existing.getTransferId())
				.orElseThrow(() -> new TransferNotFoundException(existing.getTransferId()));
	}

	/**
	 * 실제 송금 처리(Saga 본체). 멱등성 판정을 통과한 요청만 여기에 도달한다.
	 * Step 4에서 이 동기 흐름이 이벤트 기반으로 교체된다.
	 */
	public Transfer executeTransfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency,
			String memo) {
		Transfer transfer = transferRepository.save(
				Transfer.builder()
						.fromAccountId(fromAccountId)
						.toAccountId(toAccountId)
						.amount(amount)
						.currency(currency)
						.memo(memo)
						.build());
		runSaga(transfer);
		return transfer;
	}

	private Transfer createTransfer(CreateTransferRequest request) {
		return transferRepository.save(
				Transfer.builder()
						.fromAccountId(request.fromAccountId())
						.toAccountId(request.toAccountId())
						.amount(request.amount())
						.currency(request.currency())
						.memo(request.memo())
						.build());
	}

	private void runSaga(Transfer transfer) {
		AccountBalanceResponse debited = debit(transfer);
		AccountBalanceResponse credited = credit(transfer);

		if (credited != null) {
			recordLedger(transfer, debited, credited);
		}
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
