package com.remittance.account.saga;

import com.remittance.account.external.ExternalBankClient;
import com.remittance.account.external.ExternalCreditUnknownException;
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
	private final ExternalBankClient externalBankClient;

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
	 * 상대가 <b>"그런 거래 없다"고 확인해줬다.</b> 애초에 도달하지 않은 것이다.
	 *
	 * <p><b>여기서만 다시 보낸다.</b> 타임아웃 직후에 다시 보내는 것과는 다르다 —
	 * 그때는 "안 갔다"가 추측이었고, 지금은 상대가 확인해준 사실이다.
	 *
	 * <p>다시 보내다 또 답이 없으면 그대로 둔다. 기록은 남아 있으므로 다음 주기에 또 묻는다.
	 */
	public void onConfirmedNotReceived(PendingExternalCredit credit) {
		log.warn("조회로 확인했다 - 상대에게 도달하지 않았다. 다시 보낸다 (bank={}, transferId={})",
				credit.getBankCode(), credit.getTransferId());
		try {
			externalBankClient.credit(credit.getBankCode(), credit.getTransferId(),
					credit.getToAccountNumber(), credit.getAmount(), credit.getCurrency());
		} catch (ExternalCreditUnknownException stillNoAnswer) {
			// 또 모른다. 기록이 남아 있으니 다음 주기에 다시 묻는다.
			return;
		}
		// 보냈으면 결과를 바로 쓰지 않고 다음 조회에 맡긴다 — 응답과 그쪽 장부가
		// 어긋날 이유는 없지만, <b>결론은 늘 조회로만 낸다</b>는 규칙을 하나로 유지한다.
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
