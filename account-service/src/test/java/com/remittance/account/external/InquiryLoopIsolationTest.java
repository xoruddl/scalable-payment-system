package com.remittance.account.external;

import com.remittance.account.saga.ExternalCreditResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * <b>한 건의 실패가 뒤에 선 건들을 막으면 안 된다</b> (Phase 6.5 확인 루프).
 *
 * <h2>왜 이 테스트가 있나</h2>
 * 확인 루프가 잡는 예외는 "답이 없다"와 "우리 격벽이 막았다" 둘뿐이었다. 그런데 상대의
 * <b>5xx는 그 둘 중 어느 것도 아니다</b> — {@code isNoAnswer}가 false로 거르므로 그대로 올라온다.
 *
 * <p>그러면 두 가지가 같이 나빠진다.
 * <ol>
 *   <li>그 tick의 <b>남은 건들이 통째로 건너뛰어진다</b></li>
 *   <li>실패한 건은 {@code nextInquiryAt}이 그대로라 다음 tick에도 <b>맨 앞에 다시 선다</b> —
 *       조회는 그 시각 오름차순이므로, 같은 실패가 반복되면 뒤의 것들은 <b>영영</b> 조회되지 않는다</li>
 * </ol>
 *
 * <p>모르는 돈을 확인하려고 만든 루프가 <b>한 건 때문에 아무도 확인하지 못하는 루프</b>가 된다.
 */
@ExtendWith(MockitoExtension.class)
class InquiryLoopIsolationTest {

	private static final String BANK = "KB";

	@Mock
	private PendingExternalCreditRepository repository;

	@Mock
	private ExternalBankClient externalBankClient;

	@Mock
	private ExternalCreditResolver resolver;

	private ExternalCreditProber prober() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		ExternalCreditProber prober = new ExternalCreditProber(repository, externalBankClient,
				resolver, new ExternalCallBulkhead(8, meters),
				new ExternalCallCircuitBreaker(5, Duration.ofSeconds(30), meters), meters);
		// @Value 필드는 스프링 없이는 채워지지 않는다. 운영 기본값과 같은 값을 넣는다.
		ReflectionTestUtils.setField(prober, "baseBackoff", Duration.ofSeconds(2));
		ReflectionTestUtils.setField(prober, "maxBackoff", Duration.ofMinutes(1));
		ReflectionTestUtils.setField(prober, "stuckAfter", Duration.ofMinutes(5));
		return prober;
	}

	@Test
	void 한_건이_예상_못_한_실패로_끝나도_다음_건은_확인한다() {
		PendingExternalCredit 터지는건 = sent();
		PendingExternalCredit 뒤에선건 = sent();
		given(repository.findByNextInquiryAtBeforeOrderByNextInquiryAtAsc(any(Instant.class), any(Limit.class)))
				.willReturn(List.of(터지는건, 뒤에선건));
		willThrow(serverError()).given(externalBankClient).inquire(BANK, 터지는건.getTransferId());
		given(externalBankClient.inquire(BANK, 뒤에선건.getTransferId()))
				.willReturn(new ExternalCreditResult(ExternalCreditStatus.ACCEPTED, null));

		prober().inquirePending();

		// 앞에서 터졌다고 뒤가 통째로 건너뛰어지면, 이미 들어간 돈을 계속 모르는 채로 둔다.
		verify(externalBankClient).inquire(BANK, 뒤에선건.getTransferId());
		verify(resolver).onConfirmedAccepted(뒤에선건);
	}

	@Test
	void 실패한_건은_뒤로_밀려_줄_맨_앞을_계속_차지하지_않는다() {
		PendingExternalCredit 터지는건 = sent();
		Instant 밀리기_전 = 터지는건.getNextInquiryAt();
		given(repository.findByNextInquiryAtBeforeOrderByNextInquiryAtAsc(any(Instant.class), any(Limit.class)))
				.willReturn(List.of(터지는건));
		willThrow(serverError()).given(externalBankClient).inquire(eq(BANK), any(UUID.class));

		prober().inquirePending();

		// 안 밀면 다음 tick에도 이 건이 맨 앞이다 — 그 뒤는 영영 차례가 오지 않는다.
		assertThat(터지는건.getNextInquiryAt())
				.as("결론을 못 냈어도 간격은 늘어나야 한다")
				.isAfter(밀리기_전);
		assertThat(터지는건.getInquiries()).isEqualTo(1);
		verify(repository).save(터지는건);
	}

	/** 상대가 답은 줬는데 그게 5xx다. "답이 없다"도 아니고 결론도 아니다. */
	private static HttpServerErrorException serverError() {
		return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
				"Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);
	}

	/** 보냈는데 답을 못 받아 조회를 기다리는 건. */
	private static PendingExternalCredit sent() {
		return new PendingExternalCredit(UUID.randomUUID(), BANK, "110-1234", UUID.randomUUID(),
				new BigDecimal("10000"), "KRW", new BigDecimal("90000"), true);
	}
}
