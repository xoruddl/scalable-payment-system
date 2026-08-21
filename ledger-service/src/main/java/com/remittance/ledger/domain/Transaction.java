package com.remittance.ledger.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 원장에 남는 <b>분개 한 줄</b> — 잔액이 한 번 움직인 사실이다.
 *
 * <p>송금 한 건은 출금·입금 두 줄로 남고, 보상까지 갔다면 환불 한 줄이 더 붙는다.
 * 송금과 무관한 입출금도 한 줄씩 남는다({@code transferId}가 없다).
 *
 * <p>이렇게 <b>모든 잔액 변경을 빠짐없이</b> 남겨야 "원장 합 = 계좌 잔액"이 성립하고,
 * 그래야 정합성 대사가 의미를 갖는다 (Step 5a에서 바꾼 이유).
 */
@Document(collection = "transactions")
@CompoundIndex(name = "accountId_recordedAt", def = "{'accountId': 1, 'recordedAt': -1}")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

	/**
	 * 발행하는 쪽이 만들어 보낸 분개 항목 ID를 그대로 쓴다. 같은 이벤트가 다시 와도 같은 문서를
	 * 덮어쓸 뿐이라 줄이 늘지 않는다 — 원장에 같은 줄이 두 번 생기면 그 즉시 잔액과 어긋난다.
	 */
	@Id
	private String id;

	@Indexed(unique = true)
	private UUID transactionId;

	/** 송금 때문에 움직인 경우에만 채워진다. 입출금 API로 움직였으면 비어 있다. */
	@Indexed
	private UUID transferId;

	@Indexed
	private UUID accountId;

	private BalanceChangeReason reason;

	private TransactionDirection direction;

	private BigDecimal amount;

	private BigDecimal balanceAfter;

	private Instant recordedAt;
}
