package com.remittance.externalbank

import com.remittance.externalbank.domain.CreditOutcome
import com.remittance.externalbank.domain.InboundCreditRepository
import com.remittance.externalbank.fault.FaultInjector
import com.remittance.externalbank.fault.FaultProfile
import com.remittance.externalbank.service.CreditService
import com.remittance.externalbank.service.ResponseSwallowedException
import com.remittance.externalbank.service.TemporaryFailureException
import com.remittance.externalbank.web.CreditController
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.util.UUID

/**
 * 이 서비스가 지켜야 하는 것은 둘이다.
 *
 * <p><b>① 멱등성</b> — 같은 송금 ID로 몇 번을 받아도 돈은 한 번만 들어간다.
 * 보내는 쪽은 우리 DB에 제약을 걸 수 없으므로 <b>이 보장이 곧 계약</b>이다.
 *
 * <p><b>② 세 실패의 뜻이 서로 다르다</b> — 이게 이 서비스를 만드는 이유다.
 * 지금까지 이 시스템의 실패는 한 종류뿐이라
 * <b>다시 보내도 되는 실패와 보내면 안 되는 실패를 구분하는 연습</b>을 할 자리가 없었다.
 */
@SpringBootTest
class CreditServiceTest : AbstractIntegrationTest() {

	@Autowired
	private lateinit var creditService: CreditService

	@Autowired
	private lateinit var credits: InboundCreditRepository

	@Autowired
	private lateinit var faults: FaultInjector

	@Autowired
	private lateinit var controller: CreditController

	@AfterEach
	fun reset() {
		// 실험 설정과 남은 행이 다음 테스트로 새면 무엇을 쟀는지 알 수 없다.
		faults.apply(FaultProfile.HEALTHY)
		credits.deleteAll()
	}

	/**
	 * **컨트롤러를 거쳐 부른다.** 결함은 트랜잭션 밖에서 들어가므로
	 * 서비스만 직접 부르면 재현되지 않는다 — 그게 이 설계의 핵심이다.
	 */
	private fun credit(transferId: UUID = UUID.randomUUID()) = controller.credit(
		transferId,
		CreditController.CreditRequest("KR-1234-5678", BigDecimal("10000.00"), "KRW"),
	)

	@Test
	fun `정상이면 입금된다`() {
		val credit = credit()

		assertThat(credit.status).isEqualTo(CreditOutcome.ACCEPTED)
		assertThat(credits.findById(credit.transferId)).isPresent()
	}

	@Test
	fun `같은 송금 ID로 두 번 받아도 한 건만 남는다`() {
		val transferId = UUID.randomUUID()

		credit(transferId)
		credit(transferId)

		// 보내는 쪽은 타임아웃이 나면 다시 보낼 수밖에 없다.
		// 그때 두 번 들어가지 않는다는 보장을 우리가 해야 한다.
		assertThat(credits.count()).isEqualTo(1)
	}

	@Test
	fun `5xx는 입금을 남기지 않는다 — 그대로 재시도해도 된다`() {
		faults.apply(FaultProfile(errorRate = 1.0))
		val transferId = UUID.randomUUID()

		assertThatThrownBy { credit(transferId) }.isInstanceOf(TemporaryFailureException::class.java)

		assertThat(credits.findById(transferId))
			.`as`("안 들어간 것이 확실해야 보내는 쪽이 마음 놓고 재시도한다")
			.isEmpty()
	}

	@Test
	fun `타임아웃은 입금을 남긴다 — 재시도하면 이중 입금이다`() {
		faults.apply(FaultProfile(timeoutRate = 1.0))
		val transferId = UUID.randomUUID()

		assertThatThrownBy { credit(transferId) }.isInstanceOf(ResponseSwallowedException::class.java)

		// ★ 이 서비스의 존재 이유가 이 한 줄이다.
		// 응답은 못 갔는데 돈은 들어갔다. 보내는 쪽은 그걸 모른다.
		assertThat(credits.findById(transferId))
			.`as`("응답을 못 줬을 뿐 입금은 처리됐다 — 그래서 보내는 쪽은 조회해야 한다")
			.isPresent()
	}

	@Test
	fun `타임아웃 뒤에도 조회로 결과를 알 수 있다`() {
		faults.apply(FaultProfile(timeoutRate = 1.0))
		val transferId = UUID.randomUUID()
		assertThatThrownBy { credit(transferId) }.isInstanceOf(ResponseSwallowedException::class.java)

		faults.apply(FaultProfile.HEALTHY)

		// 조회에는 결함을 넣지 않는다. 여기까지 못 믿으면 보내는 쪽에 아무 수단도 안 남는다.
		assertThat(creditService.find(transferId)?.outcome).isEqualTo(CreditOutcome.ACCEPTED)
	}

	@Test
	fun `업무적 거절도 확정된 결과라 기록에 남는다`() {
		faults.apply(FaultProfile(rejectRate = 1.0))
		val transferId = UUID.randomUUID()

		val credit = credit(transferId)

		assertThat(credit.status).isEqualTo(CreditOutcome.REJECTED)
		assertThat(credit.reason).isNotBlank()
		// 나중에 조회해도 같은 답이 나와야 한다 — 다시 보내도 결과가 같기 때문이다.
		assertThat(creditService.find(transferId)?.outcome).isEqualTo(CreditOutcome.REJECTED)
	}

	@Test
	fun `거절된 송금을 다시 보내도 승인으로 바뀌지 않는다`() {
		val transferId = UUID.randomUUID()
		faults.apply(FaultProfile(rejectRate = 1.0))
		credit(transferId)

		faults.apply(FaultProfile.HEALTHY)
		val retried = credit(transferId)

		// 결과가 뒤집히면 보내는 쪽은 무엇을 믿어야 할지 알 수 없다.
		assertThat(retried.status).isEqualTo(CreditOutcome.REJECTED)
	}

	@Test
	fun `지연은 응답을 늦출 뿐 결과를 바꾸지 않는다`() {
		faults.apply(FaultProfile(latencyMs = 120))

		val startedAt = System.nanoTime()
		val credit = credit()
		val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

		assertThat(elapsedMs).isGreaterThanOrEqualTo(120)
		assertThat(credit.status).isEqualTo(CreditOutcome.ACCEPTED)
	}
}
