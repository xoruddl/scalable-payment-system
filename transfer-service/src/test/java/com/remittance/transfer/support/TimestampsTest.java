package com.remittance.transfer.support;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL DATETIME(6) 가 담을 수 있는 정밀도로 잘려 나오는지 확인한다.
 *
 * <p>OS 시계의 정밀도에 의존하지 않도록, "잘린 값인가"만 본다.
 * macOS의 {@code Instant.now()}는 마이크로초까지만 주므로 실제 값을 비교하면
 * 검증이 되지 않는다 (이 버그가 CI에서만 드러난 이유이기도 하다).
 */
class TimestampsTest {

	@RepeatedTest(50)
	void 마이크로초보다_정밀한_자리는_남지_않는다() {
		Instant now = Timestamps.now();

		assertThat(now.getNano() % 1000).isZero();
	}

	@RepeatedTest(50)
	void 잘라낸_뒤에도_현재_시각에서_크게_벗어나지_않는다() {
		Instant before = Instant.now().minusSeconds(1);

		Instant now = Timestamps.now();

		assertThat(now).isBetween(before, Instant.now().plusSeconds(1));
	}
}
