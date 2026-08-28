package com.remittance.account.saga;

import com.remittance.account.external.PendingExternalCredit;
import com.remittance.account.external.PendingExternalCredits;
import com.remittance.account.messaging.TransferEvents;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 조회로 <b>결론이 난</b> 건을 흐름에 되돌려 놓는다 (Phase 6.5 Step 2b).
 *
 * <p>{@code ExternalCreditProber}가 물어보고, 결론을 여기서 처리한다. 나눠 둔 이유는
 * <b>Saga를 아는 코드와 상대 은행을 아는 코드를 섞지 않기 위해서</b>다.
 * 조회는 "상대에게 묻는 일"이고, 여기서 하는 것은 "우리 흐름을 이어붙이는 일"이다.
 */
@Component
@RequiredArgsConstructor
public class ExternalCreditResolver {

	private static final Logger log = LoggerFactory.getLogger(ExternalCreditResolver.class);

	private final TransferSagaService transferSagaService;
	private final PendingExternalCredits pendingExternalCredits;

	/** 들어갔다. 정산 계좌에 적고 흐름을 원래대로 이어붙인다. */
	public void onConfirmedAccepted(PendingExternalCredit credit) {
		log.info("조회로 확인했다 - 상대가 받았다 (bank={}, transferId={}, 시도={}회)",
				credit.getBankCode(), credit.getTransferId(), credit.getInquiries());
		transferSagaService.onExternalCreditAccepted(toDebited(credit));
		pendingExternalCredits.resolve(credit.getTransferId());
	}

	/** 거절됐다. 출금은 이미 나갔으니 되돌려야 한다. */
	public void onConfirmedRejected(PendingExternalCredit credit, String reason) {
		log.warn("조회로 확인했다 - 상대가 거절했다 (bank={}, transferId={}, reason={})",
				credit.getBankCode(), credit.getTransferId(), reason);
		transferSagaService.onExternalCreditRejected(toDebited(credit), reason);
		pendingExternalCredits.resolve(credit.getTransferId());
	}

	/**
	 * 기록해둔 것에서 {@code Debited} 이벤트를 되살린다.
	 *
	 * <p>이 값들을 통째로 저장해둔 이유가 이것이다 — 확인이 몇 분 뒤에 날 수도 있는데,
	 * 그때 Kafka 메시지는 이미 없다.
	 */
	private TransferEvents.Debited toDebited(PendingExternalCredit credit) {
		return new TransferEvents.Debited(
				credit.getTransferId(), credit.getFromAccountId(), null,
				credit.getBankCode(), credit.getToAccountNumber(),
				credit.getAmount(), credit.getCurrency(), credit.getFromBalanceAfter(), Instant.now());
	}
}
