package com.remittance.ledger.repository;

import com.remittance.ledger.domain.Transaction;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TransactionRepository extends ReactiveMongoRepository<Transaction, String> {

	Mono<Transaction> findByTransactionId(UUID transactionId);
}
