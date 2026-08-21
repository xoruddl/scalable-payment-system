package com.remittance.transfer.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 저장소가 표현할 수 있는 정밀도로 잘라낸 현재 시각.
 *
 * <p>{@code Instant.now()}를 그대로 엔티티에 넣으면 <b>메모리상의 값과 DB에 저장된 값이 달라진다.</b>
 * MySQL의 {@code DATETIME}/{@code TIMESTAMP}는 최대 6자리(마이크로초)까지만 담을 수 있어,
 * 그보다 정밀한 값은 반올림되어 저장되기 때문이다.
 *
 * <p>이 차이는 <b>OS에 따라 드러나기도 하고 숨기도 한다.</b> macOS의 {@code Instant.now()}는
 * 애초에 마이크로초까지만 주므로 아무 문제가 없지만, Linux는 나노초까지 주므로 어긋난다.
 * 실제로 멱등 재요청 테스트가 로컬(macOS)에서는 통과하고 CI(Linux)에서만 실패했다.
 *
 * <pre>
 * 1차 응답(메모리):  2026-08-19T10:46:49.877330472Z
 * 2차 응답(DB 재조회): 2026-08-19T10:46:49.877330Z
 * </pre>
 *
 * <p>MySQL이 6자리보다 정밀한 값을 담을 방법은 없으므로, 저장 가능한 정밀도에 맞춰
 * 처음부터 잘라 넣는다. 그러면 저장 전후의 값이 항상 같다.
 */
public final class Timestamps {

	private Timestamps() {
	}

	/** MySQL DATETIME/TIMESTAMP가 담을 수 있는 최대 정밀도. */
	public static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
