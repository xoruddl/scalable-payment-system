package com.remittance.ledger.service;

import com.remittance.ledger.domain.BalanceChangeReason;
import com.remittance.ledger.domain.Transaction;
import com.remittance.ledger.messaging.AccountEvents;
import com.remittance.ledger.exception.TransactionNotFoundException;
import com.remittance.ledger.repository.TransactionRepository;
import com.remittance.ledger.web.dto.TransactionPageResponse;
import com.remittance.ledger.web.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

	private final TransactionRepository transactionRepository;
	private final ReactiveMongoTemplate mongoTemplate;

	/**
	 * 잔액 변경 하나를 원장에 남긴다. <b>같은 변경을 여러 번 받아도 결과가 같다.</b>
	 *
	 * <p>이벤트는 at-least-once라 같은 변경이 두 번 올 수 있는데, 원장에 같은 줄이 두 번 생기면
	 * 그 즉시 계좌 잔액과 어긋난다. 그래서 문서 ID를 랜덤이 아니라 <b>발행하는 쪽이 고정해 보낸
	 * 분개 항목 ID</b>로 삼는다. 재수신은 같은 _id에 같은 내용을 덮어쓰는 것으로 끝난다 —
	 * 중복 판별 테이블이 필요 없다.
	 *
	 * <p>Step 5a 전에는 "송금 + 계좌 + 방향"이라는 자연키를 썼다. 원장이 송금만 기록하던 시절엔
	 * 그걸로 충분했지만, 이제 <b>송금과 무관한 변경도 들어오고</b> 같은 송금에서 같은 계좌가
	 * 두 번 움직일 수도 있어(출금 → 환불) 자연키가 더는 성립하지 않는다.
	 *
	 * <p>기록 시각도 발행 시각을 그대로 쓴다. 수신할 때마다 새로 찍으면 재수신 때 값이 달라져
	 * 멱등하지 않다.
	 */
	public Mono<Transaction> record(AccountEvents.BalanceChanged event) {
		return transactionRepository.save(Transaction.builder()
				.id(event.entryId().toString())
				.transactionId(event.entryId())
				.transferId(event.transferId())
				.accountId(event.accountId())
				.reason(event.reason())
				.direction(event.direction())
				.amount(event.amount())
				.balanceAfter(event.balanceAfter())
				.recordedAt(event.occurredAt())
				.build());
	}

	/**
	 * 이 송금의 원장 기록이 <b>끝났는지</b>. 출금 줄과 입금 줄이 모두 있어야 끝난 것이다.
	 *
	 * <p>두 줄은 계좌가 달라 서로 다른 파티션으로 오므로 <b>도착 순서가 보장되지 않는다.</b>
	 * "입금 줄을 적었으니 끝"이라고 볼 수 없어, 매번 둘 다 있는지 확인한다.
	 */
	public Mono<Boolean> isTransferFullyRecorded(UUID transferId) {
		return transactionRepository.findByTransferId(transferId)
				.map(Transaction::getReason)
				.filter(BalanceChangeReason::isTransferLeg)
				.distinct()
				.count()
				.map(legs -> legs == 2);
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
