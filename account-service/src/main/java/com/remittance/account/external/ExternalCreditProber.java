package com.remittance.account.external;

import com.remittance.account.saga.ExternalCreditResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 답을 못 받은 입금을 <b>물어봐서</b> 결론짓는다 (Phase 6.5 Step 2b).
 *
 * <h2>재시도가 아니라 조회다 ★</h2>
 * 이 Phase의 전부가 이 한 문장이다. 타임아웃이 났을 때 <b>같은 입금 요청을 다시 보내는 것</b>은
 * "안 갔다"를 전제로 하는데, 우리는 그걸 모른다. 상대가 멱등하니 다시 보내도 안전하긴 하지만
 * 그건 <b>남의 약속에 한 번 더 기대는 것</b>이고, 답이 계속 없으면 영영 알 수 없다.
 *
 * <p>조회는 다르다. <b>그쪽 장부에 무엇이 적혔는지</b> 알려준다. 우리가 알아야 하는 건 그거다.
 *
 * <table>
 *   <tr><th>조회 결과</th><th>뜻</th><th>한다</th></tr>
 *   <tr><td>ACCEPTED</td><td>들어갔다</td><td>정산 계좌에 적고 종결</td></tr>
 *   <tr><td>REJECTED</td><td>거절됐다</td><td>보상(환불)으로 넘긴다</td></tr>
 *   <tr><td>NOT_FOUND</td><td><b>애초에 도달하지 않았다</b></td><td>다시 보낸다 — 이때는 안전하다</td></tr>
 *   <tr><td>답 없음</td><td>여전히 모른다</td><td>간격을 늘려 다음에 다시</td></tr>
 * </table>
 *
 * <p>{@code NOT_FOUND}에서만 재전송하는 것이 핵심이다. <b>"안 갔다"를 상대가 확인해줬을 때만</b>
 * 다시 보낸다.
 *
 * <h2>⚠️ 인스턴스가 여럿이면 중복 실행된다</h2>
 * {@code @Scheduled}라 replica마다 돈다. 같은 건을 두 인스턴스가 동시에 확인해도
 * <b>돈이 틀리지는 않는다</b> — 조회는 읽기이고, 해소는 멱등한 이벤트 발행이다.
 * 다만 상대 은행을 쓸데없이 두 번 두드린다. ShedLock이 필요한 자리로 ROADMAP에 있다.
 */
@Component
@RequiredArgsConstructor
public class ExternalCreditProber {

	private static final Logger log = LoggerFactory.getLogger(ExternalCreditProber.class);

	/** 한 번에 너무 많이 물어보면 상대를 두드리는 꼴이 된다. */
	private static final int BATCH = 50;

	private final PendingExternalCreditRepository repository;
	private final ExternalBankClient externalBankClient;
	private final ExternalCreditResolver resolver;
	private final MeterRegistry meterRegistry;

	/** 다시 묻기까지의 첫 간격. 이후 지수적으로 늘린다. */
	@Value("${remittance.external-bank.inquiry.base-backoff:2s}")
	private Duration baseBackoff;

	/** 아무리 늘려도 이보다 오래 기다리지는 않는다 — 풀렸는데 한참 모르면 그것도 문제다. */
	@Value("${remittance.external-bank.inquiry.max-backoff:1m}")
	private Duration maxBackoff;

	/** 이만큼 지나도 안 풀리면 사람이 봐야 한다. */
	@Value("${remittance.external-bank.inquiry.stuck-after:5m}")
	private Duration stuckAfter;

	@Scheduled(fixedDelayString = "${remittance.external-bank.inquiry.interval-ms:1000}")
	public void inquirePending() {
		List<PendingExternalCredit> pending = repository
				.findByNextInquiryAtBeforeOrderByNextInquiryAtAsc(
						com.remittance.account.support.Timestamps.now(), Limit.of(BATCH));
		for (PendingExternalCredit credit : pending) {
			inquireOne(credit);
		}
	}

	@Transactional
	public void inquireOne(PendingExternalCredit credit) {
		ExternalCreditResult result;
		try {
			result = externalBankClient.inquire(credit.getBankCode(), credit.getTransferId());
		} catch (ExternalCreditUnknownException stillNoAnswer) {
			// 조회에도 답이 없다. 여전히 모른다 — 간격만 늘리고 다음에 다시 묻는다.
			pushBack(credit);
			return;
		}

		switch (result.status()) {
			case ACCEPTED -> {
				outcomes("accepted").increment();
				resolver.onConfirmedAccepted(credit);
			}
			case REJECTED -> {
				outcomes("rejected").increment();
				resolver.onConfirmedRejected(credit, result.reason());
			}
			// 상대가 "그런 거래 없다"고 확인해줬다. 이때만 다시 보내는 것이 안전하다.
			case NOT_FOUND -> {
				outcomes("not_found").increment();
				resolver.onConfirmedNotReceived(credit);
			}
		}
	}

	private void pushBack(PendingExternalCredit credit) {
		credit.backOff(baseBackoff, maxBackoff);
		repository.save(credit);
		if (credit.isStuckFor(stuckAfter)) {
			// 오래 안 풀리는 건은 조용히 두면 안 된다. 고객 돈이 어디 있는지 모르는 상태다.
			log.error("상대 은행 결과를 오래 확인하지 못하고 있다 - 사람이 봐야 한다 "
					+ "(bank={}, transferId={}, 시도={}회)",
					credit.getBankCode(), credit.getTransferId(), credit.getInquiries());
		}
	}

	/** 결과가 한 번도 없어도 0으로 보이게 미리 만들어 둔다. */
	@PostConstruct
	void 결과_카운터를_미리_만든다() {
		outcomes("accepted");
		outcomes("rejected");
		outcomes("not_found");
	}

	private Counter outcomes(String outcome) {
		return Counter.builder("remittance.external.credit.inquiry")
				.description("상대 은행 조회로 결론이 난 건수")
				.tag("outcome", outcome)
				.register(meterRegistry);
	}
}
