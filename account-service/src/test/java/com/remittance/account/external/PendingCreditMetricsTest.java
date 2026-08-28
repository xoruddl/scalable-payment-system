package com.remittance.account.external;

import com.remittance.account.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * <b>"보냈는데 모른다"와 "보내지도 못했다"를 한 숫자로 세면 안 된다</b> (Phase 6.5).
 *
 * <h2>왜 이 테스트가 있나 — 실제로 부풀려 보고했다</h2>
 * 2026-08-28 재측정에서 회로가 열렸을 때 {@code credit.unknown} 게이지가 <b>320</b>이었다.
 * 그런데 진짜로 모르는 건은 <b>1건</b>이고 나머지 319건은 회로에 막혀 <b>아예 보내지 않은 것</b>이었다.
 * 게이지가 표의 행 수를 셌기 때문이다.
 *
 * <p>차단된 건은 돈이 나갈 수가 없다. <b>사고가 아니라 밀린 일</b>이다. 둘을 섞으면
 * "고객 돈이 어디 있는지 모르는 건이 320건"이라고 읽히고, 그건 사실이 아니다 —
 * 그리고 회로 차단기를 넣을수록 이 거짓말은 커진다.
 */
@ExtendWith(MockitoExtension.class)
class PendingCreditMetricsTest {

	@Mock
	private PendingExternalCreditRepository repository;

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Test
	void 보낸_것과_못_보낸_것을_갈라서_센다() {
		// 재측정에서 실제로 나온 숫자 그대로다.
		given(repository.countBySentTrue()).willReturn(1L);
		given(repository.countBySentFalse()).willReturn(319L);
		SimpleMeterRegistry meters = gaugesOn();

		assertThat(meters.get("remittance.external.credit.unknown").gauge().value())
				.as("돈이 나갔을 수 있는 건 - 사람이 봐야 하는 숫자")
				.isEqualTo(1);
		assertThat(meters.get("remittance.external.credit.unsent").gauge().value())
				.as("돈은 안 나갔다 - 상대가 살아나면 빠진다")
				.isEqualTo(319);
	}

	@Test
	void 한_건도_없을_때도_0으로_보인다() {
		given(repository.countBySentTrue()).willReturn(0L);
		given(repository.countBySentFalse()).willReturn(0L);
		SimpleMeterRegistry meters = gaugesOn();

		// "0건"과 "수집이 안 됨"이 구분돼야 한다.
		assertThat(meters.get("remittance.external.credit.unknown").gauge().value()).isZero();
		assertThat(meters.get("remittance.external.credit.unsent").gauge().value()).isZero();
	}

	private SimpleMeterRegistry gaugesOn() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		new PendingExternalCredits(repository, outboxEventRepository, new ObjectMapper(), meters)
				.미해소_건수를_지표로_낸다();
		return meters;
	}
}
