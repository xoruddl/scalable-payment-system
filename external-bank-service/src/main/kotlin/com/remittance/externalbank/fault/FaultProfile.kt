package com.remittance.externalbank.fault

/**
 * 이 은행이 **얼마나 나쁘게 굴지**. 런타임에 바꾼다.
 *
 * <p>같은 jar에 이 값만 바꿔 A/B를 재기 위해서다 —
 * `account.lock.strategy`, `SHARDS`와 같은 규칙이다.
 * 코드를 고쳐가며 재면 빌드가 달라져 무엇 때문에 숫자가 바뀌었는지 말할 수 없다.
 *
 * @param latencyMs   응답을 이만큼 늦춘다. 느린 상대가 우리 스레드를 묶는 상황
 * @param timeoutRate 이 비율만큼 **응답을 주지 않는다.** 이 서비스의 존재 이유
 * @param errorRate   이 비율만큼 5xx. **다시 보내도 되는** 실패
 * @param rejectRate  이 비율만큼 업무적 거절. **다시 보내면 안 되는** 실패
 */
data class FaultProfile(
	val latencyMs: Long = 0,
	val timeoutRate: Double = 0.0,
	val errorRate: Double = 0.0,
	val rejectRate: Double = 0.0,
) {
	init {
		require(latencyMs >= 0) { "지연은 음수일 수 없다: $latencyMs" }
		listOf("timeoutRate" to timeoutRate, "errorRate" to errorRate, "rejectRate" to rejectRate)
			.forEach { (name, rate) -> require(rate in 0.0..1.0) { "$name 은 0~1이어야 한다: $rate" } }
	}

	companion object {
		/** 아무 문제 없는 상대. 기본값이다 — 켜는 것은 실험할 때뿐이다. */
		val HEALTHY = FaultProfile()
	}
}
