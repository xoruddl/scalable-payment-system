package com.remittance.account.external;

import com.remittance.account.messaging.TransferEvents;
import com.remittance.account.outbox.OutboxEvent;
import com.remittance.account.outbox.OutboxEventRepository;
import com.remittance.account.support.Timestamps;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * "보냈는데 답을 못 받은" 입금을 <b>기록하고 지운다.</b> 확인은 {@link ExternalCreditProber}가 한다.
 *
 * <p>기록과 이벤트 발행이 <b>한 트랜잭션</b>이어야 한다. 기록만 남고 이벤트가 유실되면
 * 송금은 {@code DEBIT_COMPLETED}인 채로 남아 <b>단순히 느린 건과 구분되지 않고</b>,
 * 이벤트만 나가고 기록이 없으면 <b>아무도 그 건을 확인하지 않는다.</b>
 */
@Component
@RequiredArgsConstructor
public class PendingExternalCredits {

	private static final Logger log = LoggerFactory.getLogger(PendingExternalCredits.class);

	private static final String AGGREGATE_TYPE = "Transfer";

	private final PendingExternalCreditRepository repository;
	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	/**
	 * 이 건은 <b>모르는 상태</b>다 — 기록하고 알린다.
	 *
	 * <p>같은 이벤트가 재배달되면 여기 또 들어올 수 있다. 이미 있으면 덮어쓰지 않는다 —
	 * 덮어쓰면 {@code nextInquiryAt}이 되돌아가 확인이 처음부터 다시 시작된다.
	 */
	@Transactional
	public void remember(TransferEvents.Debited event) {
		if (repository.existsById(event.transferId())) {
			log.debug("이미 확인 대기 중인 건이다 (transferId={})", event.transferId());
			return;
		}
		repository.save(new PendingExternalCredit(
				event.transferId(), event.toBankCode(), event.toAccountNumber(),
				event.fromAccountId(), event.amount(), event.currency(), event.fromBalanceAfter()));

		announceUnknown(event);
		log.warn("상대 은행이 답하지 않아 결과를 모른다 - 조회로 확인한다 "
				+ "(bank={}, transferId={}, amount={})",
				event.toBankCode(), event.transferId(), event.amount());
	}

	/** 확인이 끝났다. 더 물어볼 이유가 없다. */
	@Transactional
	public void resolve(UUID transferId) {
		repository.deleteById(transferId);
	}

	private void announceUnknown(TransferEvents.Debited event) {
		TransferEvents.CreditUnknown body = new TransferEvents.CreditUnknown(
				event.transferId(), event.fromAccountId(), event.toBankCode(),
				event.toAccountNumber(), event.amount(), event.currency(), Timestamps.now());
		outboxEventRepository.save(OutboxEvent.builder()
				.aggregateType(AGGREGATE_TYPE)
				.aggregateId(event.transferId())
				.eventType(TransferEvents.CREDIT_UNKNOWN)
				.payload(objectMapper.writeValueAsString(body))
				.build());
	}

	/**
	 * 확인을 못 한 건이 몇 개나 쌓여 있나.
	 *
	 * <p><b>이 값이 0에서 뜨면 그래프에서 튄다.</b> 돈이 나갔을 수도 있는 건이 늘어나는 중이라는
	 * 뜻이라, 지연이나 처리량보다 먼저 봐야 하는 숫자다. 0일 때도 시계열이 있어야
	 * "0건"과 "수집이 안 됨"이 구분된다.
	 */
	@PostConstruct
	void 미해소_건수를_지표로_낸다() {
		Gauge.builder("remittance.external.credit.unknown", repository, PendingExternalCreditRepository::count)
				.description("상대 은행이 답하지 않아 결과를 모르는 입금 건수")
				.register(meterRegistry);
	}
}
