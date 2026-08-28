package com.remittance.externalbank.web

import com.remittance.externalbank.domain.CreditOutcome
import com.remittance.externalbank.domain.InboundCredit
import com.remittance.externalbank.fault.FaultInjector
import com.remittance.externalbank.service.CreditService
import com.remittance.externalbank.service.ResponseSwallowedException
import com.remittance.externalbank.service.TemporaryFailureException
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * 상대 은행이 밖에 내주는 계약. **이것뿐이다.**
 *
 * ```
 * POST /transfers/{transferId}/credit    입금 요청. 멱등성 키는 transferId
 * GET  /transfers/{transferId}           거래 조회 — 타임아웃 뒤 결과를 아는 유일한 방법
 * ```
 *
 * <p>조회가 없으면 보내는 쪽은 타임아웃 뒤에 **아무것도 할 수 없다.**
 * 재시도는 이중 입금이고, 실패 처리는 돈을 잃는 것이다. 그래서 조회는 선택이 아니라
 * **이 계약의 필수 절반**이다.
 */
@RestController
@RequestMapping("/transfers")
class CreditController(
	private val creditService: CreditService,
	private val faults: FaultInjector,
) {

	private val log = LoggerFactory.getLogger(CreditController::class.java)

	data class CreditRequest(
		@field:NotBlank @field:Size(max = 34) val accountNumber: String,
		@field:DecimalMin("0.01") val amount: BigDecimal,
		@field:NotBlank @field:Size(min = 3, max = 3) val currency: String,
	)

	data class CreditResponse(
		val transferId: UUID,
		val status: CreditOutcome,
		val reason: String?,
	) {
		companion object {
			fun from(credit: InboundCredit) =
				CreditResponse(credit.transferId, credit.outcome, credit.rejectReason)
		}
	}

	/**
	 * 결함은 **여기서** 넣는다. 트랜잭션 밖이라야 순서를 마음대로 정할 수 있다.
	 *
	 * ```
	 * 지연  ─▶  5xx?(업무 전)  ─▶  [입금 커밋]  ─▶  응답 삼키기?(커밋 후)
	 * ```
	 *
	 * **커밋을 사이에 두는 것이 핵심이다.** 5xx는 그 앞이라 돈이 안 움직였고,
	 * 응답 삼키기는 그 뒤라 **돈은 움직였는데 상대가 모른다.**
	 * 처음에 이 둘을 서비스 안에서 예외로 던졌다가 트랜잭션이 롤백돼 입금이 사라졌고,
	 * 테스트가 그걸 잡아냈다.
	 */
	@PostMapping("/{transferId}/credit")
	fun credit(
		@PathVariable transferId: UUID,
		@Valid @RequestBody request: CreditRequest,
	): CreditResponse {
		faults.delay()
		if (faults.shouldFailBeforeWork()) {
			throw TemporaryFailureException()
		}

		val credit = creditService.credit(transferId, request.accountNumber, request.amount, request.currency)

		// ★ 커밋된 뒤다. 여기서 던지면 입금은 남고 응답만 안 간다.
		if (faults.shouldSwallowResponse()) {
			throw ResponseSwallowedException(transferId)
		}
		return CreditResponse.from(credit)
	}

	/** 없으면 404다. **"아직 안 들어왔다"와 "거절됐다"는 다른 답**이어야 한다. */
	@GetMapping("/{transferId}")
	fun find(@PathVariable transferId: UUID): ResponseEntity<CreditResponse> =
		creditService.find(transferId)
			?.let { ResponseEntity.ok(CreditResponse.from(it)) }
			?: ResponseEntity.notFound().build()

	/**
	 * 응답을 삼키기로 한 요청. **아무것도 쓰지 않고 그냥 매달아 둔다.**
	 *
	 * <p>여기서 504 같은 상태 코드를 주면 안 된다. 상태 코드는 **답을 준 것**이고,
	 * 그러면 보내는 쪽이 "적어도 상대가 살아는 있다"를 알게 된다.
	 * 진짜 타임아웃은 <b>아무 소식도 없는 것</b>이라, 클라이언트의 read timeout이 먼저 끊게 둔다.
	 */
	@ExceptionHandler(ResponseSwallowedException::class)
	fun onSwallowed(e: ResponseSwallowedException): ResponseEntity<Void> {
		log.warn("응답을 삼킨다 - 입금은 이미 처리됐다 (transferId={})", e.transferId)
		Thread.sleep(SWALLOW_HOLD_MS)
		return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build()
	}

	/** 5xx. **입금은 기록되지 않았으므로 그대로 재시도해도 된다.** */
	@ExceptionHandler(TemporaryFailureException::class)
	fun onTemporary(e: TemporaryFailureException): ResponseEntity<Map<String, String>> {
		log.warn("일시적인 장애를 흉내낸다 - 이 요청은 처리되지 않았다")
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(mapOf("error" to (e.message ?: "temporarily unavailable")))
	}

	companion object {
		/**
		 * 응답을 삼킬 때 매달아 두는 시간. 보내는 쪽의 read timeout보다 넉넉히 길어야
		 * **그쪽이 먼저 끊는다.** 무한정 잡고 있으면 이 서비스의 스레드가 말라죽는다.
		 */
		private const val SWALLOW_HOLD_MS = 30_000L
	}
}
