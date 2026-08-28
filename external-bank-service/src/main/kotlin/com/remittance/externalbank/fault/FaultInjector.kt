package com.remittance.externalbank.fault

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * 정해진 확률대로 **나쁘게 군다.**
 *
 * <h2>세 실패의 뜻이 서로 달라야 한다 ★</h2>
 * 이 서비스를 만드는 이유가 여기 있다. 지금까지 이 시스템의 실패는 한 종류(업무적 실패)뿐이라
 * **다시 보내도 되는 실패와 보내면 안 되는 실패를 구분하는 연습**을 할 자리가 없었다.
 *
 * | 결함 | 보내는 쪽이 보는 것 | **돈은?** | 어떻게 해야 하나 |
 * |---|---|---|---|
 * | `timeoutRate` | 응답 없음 | **들어갔을 수도 있다** | 재시도 금지. **조회해서 확인** |
 * | `errorRate` | 5xx | 안 들어갔다 | 그대로 재시도 |
 * | `rejectRate` | 200 REJECTED | 안 들어갔고 앞으로도 안 들어간다 | 재시도 금지. 실패로 종결 |
 *
 * <h2>타임아웃일 때 입금은 **처리한다** ★★</h2>
 * 이게 이 클래스에서 가장 중요한 규칙이다. 타임아웃이 "아무 일도 안 일어남"이면
 * 보내는 쪽은 그냥 다시 보내면 되고, **불확실성이 사라진다.**
 * 실제 은행은 처리해놓고 응답을 못 주는 일이 생기고, 그게 어려운 이유다.
 * 그래서 여기서는 **먼저 기록하고 그다음에 응답을 삼킨다.**
 */
@Component
class FaultInjector {

	private val log = LoggerFactory.getLogger(FaultInjector::class.java)
	private val profile = AtomicReference(FaultProfile.HEALTHY)

	fun current(): FaultProfile = profile.get()

	fun apply(next: FaultProfile): FaultProfile {
		profile.set(next)
		log.info("상대 은행의 결함 설정을 바꿨다: {}", next)
		return next
	}

	/** 응답을 늦춘다. 느린 상대가 우리 스레드를 묶는 상황을 만든다. */
	fun delay() {
		val latency = current().latencyMs
		if (latency > 0) {
			Thread.sleep(latency)
		}
	}

	/** 이번 요청은 5xx로 답할까. **업무를 시작하기 전에** 물어야 한다 — 안 들어간 게 맞아야 하므로. */
	fun shouldFailBeforeWork(): Boolean = hit(current().errorRate)

	/** 이번 요청은 업무적으로 거절할까. 거절도 **확정된 결과**라 기록은 남는다. */
	fun shouldReject(): Boolean = hit(current().rejectRate)

	/**
	 * 이번 요청은 응답을 삼킬까. **입금이 커밋된 뒤에** 물어야 한다 —
	 * 그래야 "들어갔는데 모른다"가 된다. 트랜잭션 안에서 던지면 롤백되어 의미가 없다.
	 */
	fun shouldSwallowResponse(): Boolean = hit(current().timeoutRate)

	private fun hit(rate: Double): Boolean =
		rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate
}
