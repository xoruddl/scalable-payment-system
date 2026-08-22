package com.remittance.ledger.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 저장소가 표현할 수 있는 정밀도로 잘라낸 현재 시각.
 *
 * <p>{@code Instant.now()}를 그대로 도큐먼트에 넣으면 <b>메모리상의 값과 저장된 값이 달라진다.</b>
 * MongoDB의 BSON Date는 <b>밀리초(3자리)</b>까지만 담기 때문이다.
 * MySQL(6자리)보다도 거칠어서, 다른 서비스보다 더 많이 잘린다.
 *
 * <p>이 차이는 OS에 따라 드러나기도 하고 숨기도 한다. macOS의 {@code Instant.now()}는
 * 마이크로초까지만 주지만 Linux는 나노초까지 주므로, 어느 쪽이든 밀리초보다는 정밀하다.
 * transfer-service에서 같은 원인으로 멱등 재요청 테스트가 CI(Linux)에서만 실패한 적이 있다.
 *
 * <p>저장 가능한 정밀도에 맞춰 처음부터 잘라 넣으면 저장 전후의 값이 항상 같다.
 */
public final class Timestamps {

	private Timestamps() {
	}

	/** MongoDB BSON Date가 담을 수 있는 최대 정밀도. */
	public static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}
}
