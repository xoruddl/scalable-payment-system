package com.remittance.account.outbox;

import com.remittance.account.domain.Account;
import com.remittance.account.messaging.AccountEvents;
import com.remittance.account.support.Timestamps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 잔액 변경을 <b>분개장에 남기라고 알리는</b> 이벤트를 Outbox에 적는다.
 *
 * <p>잔액이 바뀌는 경로는 둘이다 — 입출금 API와 송금 Saga. 둘 다 여기를 거쳐야 한다.
 * <b>한 경로라도 빠지면 "원장 합 = 잔액"이 깨지고, 그 순간 정합성 대사는 의미가 없어진다.</b>
 * (Step 5a 전에는 송금만 원장에 남아서 실제로 성립하지 않았다.)
 *
 * <p>이 메서드는 반드시 <b>잔액을 바꾼 트랜잭션 안에서</b> 호출되어야 한다. 밖에서 부르면
 * 잔액만 바뀌고 기록이 유실되거나 그 반대가 생긴다 — Outbox를 쓰는 이유가 사라진다.
 */
@Component
@RequiredArgsConstructor
public class BalanceJournal {

	private static final String AGGREGATE_TYPE = "Account";

	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	/**
	 * @param transferId 송금 때문에 움직였으면 그 ID, 아니면 {@code null}
	 */
	public void record(Account account, AccountEvents.BalanceChangeReason reason,
			AccountEvents.TransactionDirection direction, BigDecimal amount, UUID transferId) {
		AccountEvents.BalanceChanged body = new AccountEvents.BalanceChanged(
				// 여기서 한 번 만들어 Outbox에 고정된다. 재전송이 와도 같은 값이라 원장이 멱등해진다.
				UUID.randomUUID(),
				account.getAccountId(),
				reason,
				direction,
				amount,
				account.getBalance(),
				account.getCurrency(),
				transferId,
				Timestamps.now());

		outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType(AGGREGATE_TYPE)
				// 계좌 단위로 순서를 지켜야 잔액 추이가 뒤섞이지 않는다 (Saga 이벤트는 송금 ID를 쓴다).
				.aggregateId(account.getAccountId())
				.eventType(AccountEvents.BALANCE_CHANGED)
				.payload(objectMapper.writeValueAsString(body))
				.build());
	}
}
