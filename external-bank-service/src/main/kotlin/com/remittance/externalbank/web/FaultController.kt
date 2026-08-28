package com.remittance.externalbank.web

import com.remittance.externalbank.fault.FaultInjector
import com.remittance.externalbank.fault.FaultProfile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 이 은행이 **얼마나 나쁘게 굴지**를 런타임에 바꾼다. 운영 API가 아니라 실험 장치다.
 *
 * ```
 * curl -X POST localhost:8086/faults -H 'Content-Type: application/json' \
 *      -d '{"timeoutRate":0.1,"latencyMs":50}'
 * ```
 *
 * <p><b>왜 런타임인가</b> — 같은 jar에 이 값만 바꿔 A/B를 재기 위해서다.
 * 코드를 고쳐가며 재면 빌드가 달라져 무엇 때문에 숫자가 바뀌었는지 말할 수 없다
 * (`account.lock.strategy`, `SHARDS`와 같은 규칙).
 */
@RestController
@RequestMapping("/faults")
class FaultController(private val faults: FaultInjector) {

	@GetMapping
	fun current(): FaultProfile = faults.current()

	/** 안 적은 항목은 **0으로 되돌아간다.** 실험 사이에 이전 설정이 남아 있으면 안 된다. */
	@PostMapping
	fun apply(@RequestBody profile: FaultProfile): FaultProfile = faults.apply(profile)
}
