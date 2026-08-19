package com.remittance.ledger.service;

import com.remittance.ledger.domain.Transaction;
import com.remittance.ledger.exception.TransactionNotFoundException;
import com.remittance.ledger.repository.TransactionRepository;
import com.remittance.ledger.support.Timestamps;
import com.remittance.ledger.web.dto.RecordTransactionRequest;
import com.remittance.ledger.web.dto.TransactionPageResponse;
import com.remittance.ledger.web.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

	private final TransactionRepository transactionRepository;
	private final ReactiveMongoTemplate mongoTemplate;

	public Mono<Void> recordTransactions(List<RecordTransactionRequest> requests) {
		return recordTransactions(requests, Timestamps.now());
	}

	/**
	 * 원장에 거래를 남긴다. <b>같은 거래를 여러 번 기록해도 결과가 같다.</b>
	 *
	 * <p>이벤트는 at-least-once라 {@code transfer.credited}가 두 번 올 수 있는데,
	 * 원장에 같은 거래가 두 줄 생기면 잔액과 원장 합계가 어긋난다.
	 * 그래서 문서 ID를 랜덤이 아니라 <b>거래의 자연키</b>(송금 + 계좌 + 방향)에서 만든다.
	 * 재수신은 같은 _id에 같은 내용을 덮어쓰는 것으로 끝난다 — 중복 판별 테이블이 필요 없다.
	 *
	 * @param recordedAt 기록 시각. 재수신 때도 같은 값이어야 완전히 멱등하므로,
	 *                   이벤트로 들어온 경우 이벤트의 발생 시각을 그대로 넘긴다.
	 */
	public Mono<Void> recordTransactions(List<RecordTransactionRequest> requests, Instant recordedAt) {
		List<Transaction> transactions = requests.stream()
				.map(req -> Transaction.builder()
						.id(naturalKey(req))
						.transactionId(deterministicTransactionId(req))
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

	/** 하나의 송금에서 한 계좌는 한 방향으로 한 번만 기록된다. */
	private String naturalKey(RecordTransactionRequest req) {
		return req.transferId() + ":" + req.accountId() + ":" + req.direction();
	}

	/**
	 * {@code transactionId}에는 unique 인덱스가 걸려 있다. 재수신 때 랜덤 값을 새로 만들면
	 * 같은 _id를 덮어쓰면서 인덱스 값만 바뀌어, 조회 API가 돌려주던 ID가 슬쩍 달라진다.
	 * 자연키에서 유도해 항상 같은 값이 나오게 한다.
	 */
	private UUID deterministicTransactionId(RecordTransactionRequest req) {
		return UUID.nameUUIDFromBytes(naturalKey(req).getBytes(StandardCharsets.UTF_8));
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
