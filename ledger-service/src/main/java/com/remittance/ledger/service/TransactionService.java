package com.remittance.ledger.service;

import com.remittance.ledger.domain.Transaction;
import com.remittance.ledger.exception.TransactionNotFoundException;
import com.remittance.ledger.repository.TransactionRepository;
import com.remittance.ledger.web.dto.RecordTransactionRequest;
import com.remittance.ledger.web.dto.TransactionPageResponse;
import com.remittance.ledger.web.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

	private final TransactionRepository transactionRepository;
	private final ReactiveMongoTemplate mongoTemplate;

	public Mono<Void> recordTransactions(List<RecordTransactionRequest> requests) {
		Instant recordedAt = Instant.now();
		List<Transaction> transactions = requests.stream()
				.map(req -> Transaction.builder()
						.transactionId(UUID.randomUUID())
						.transferId(req.transferId())
						.accountId(req.accountId())
						.direction(req.direction())
						.amount(req.amount())
						.balanceAfter(req.balanceAfter())
						.recordedAt(recordedAt)
						.build())
				.toList();
		return transactionRepository.saveAll(transactions).then();
	}

	public Mono<TransactionResponse> getTransaction(UUID transactionId) {
		return transactionRepository.findByTransactionId(transactionId)
				.map(TransactionResponse::from)
				.switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)));
	}

	public Mono<TransactionPageResponse> getTransactionsByAccount(UUID accountId, String cursor, int size,
			Instant from, Instant to) {
		Criteria criteria = Criteria.where("accountId").is(accountId);
		if (from != null) {
			criteria = criteria.and("recordedAt").gte(from);
		}
		if (to != null) {
			criteria = criteria.and("recordedAt").lte(to);
		}
		if (cursor != null) {
			TransactionCursor decoded = TransactionCursor.decode(cursor);
			criteria = criteria.orOperator(
					Criteria.where("recordedAt").lt(decoded.recordedAt()),
					Criteria.where("recordedAt").is(decoded.recordedAt())
							.and("transactionId").lt(decoded.transactionId()));
		}

		Query query = Query.query(criteria)
				.with(Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("transactionId")))
				.limit(size + 1);

		return mongoTemplate.find(query, Transaction.class)
				.collectList()
				.map(transactions -> toPageResponse(transactions, size));
	}

	private TransactionPageResponse toPageResponse(List<Transaction> transactions, int size) {
		boolean hasNext = transactions.size() > size;
		List<Transaction> page = hasNext ? transactions.subList(0, size) : transactions;
		String nextCursor = hasNext
				? new TransactionCursor(page.get(page.size() - 1).getRecordedAt(),
						page.get(page.size() - 1).getTransactionId()).encode()
				: null;
		List<TransactionResponse> items = page.stream().map(TransactionResponse::from).toList();
		return new TransactionPageResponse(items, nextCursor, hasNext);
	}
}
