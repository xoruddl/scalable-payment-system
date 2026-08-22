package com.remittance.notification.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 저장소가 표현할 수 있는 정밀도로 잘라낸 현재 시각.
 *
 * <p>{@code Instant.now()}를 그대로 넣으면 <b>메모리상의 값과 저장된 값이 달라진다.</b>
 * MySQL의 {@code DATETIME(6)}은 마이크로초까지만 담는데, Linux의 {@code Instant.now()}는
 * 나노초까지 주기 때문이다. macOS에서는 애초에 마이크로초라 드러나지 않아,
 * <b>로컬은 통과하고 CI에서만 실패</b>하는 형태로 나타난다 (transfer-service에서 겪음).
 */
public final class Timestamps {

	private Timestamps() {
	}

	public static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
