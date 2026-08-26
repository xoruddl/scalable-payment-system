package com.remittance.externalbank.service

import com.remittance.externalbank.domain.CreditOutcome
import com.remittance.externalbank.domain.InboundCredit
import com.remittance.externalbank.domain.InboundCreditRepository
import com.remittance.externalbank.fault.FaultInjector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * 이 은행의 <b>업무</b>만 한다 — 받고, 판정하고, 기록한다.
 *
 * <h2>결함 주입이 여기 없는 이유 ★</h2>
 * 처음에는 "응답을 삼킨다"를 이 안에서 예외로 던졌다. 그랬더니
 * <b>트랜잭션이 함께 롤백되어 입금이 남지 않았다.</b> 테스트가 잡아냈다.
 *
 * <p>그러면 이 서비스의 존재 이유가 통째로 사라진다. 타임아웃이 "아무 일도 안 일어남"이면
 * 보내는 쪽은 그냥 다시 보내면 되고, <b>"들어갔나 안 들어갔나"가 생기지 않는다.</b>
 *
 * <p>그래서 <b>응답을 삼키는 것은 트랜잭션 밖에서</b> 한다({@code CreditController}).
 * 층을 나눠 놓고 보면 당연하다 — 삼키는 것은 <b>전송의 문제</b>이지 업무의 문제가 아니다.
 * 입금은 커밋되고, 그다음에 응답이 안 간다. 실제 은행에서 벌어지는 일도 그 순서다.
 */
@Service
class CreditService(
	private val credits: InboundCreditRepository,
	private val faults: FaultInjector,
) {

	private val log = LoggerFactory.getLogger(CreditService::class.java)

	/**
	 * 입금을 받는다. **같은 `transferId`로 몇 번을 불려도 결과는 한 번만 만들어진다.**
	 *
	 * <p>보내는 쪽은 우리 DB에 제약을 걸 수 없다. **이 보장이 곧 계약이다.**
	 */
	@Transactional
	fun credit(transferId: UUID, accountNumber: String, amount: BigDecimal, currency: String): InboundCredit {
		val existing = credits.findById(transferId).orElse(null)
		if (existing != null) {
			// 재요청이다. 새로 만들지 않는다 — 여기가 이중 입금을 막는 유일한 지점이다.
			log.info("이미 처리한 입금이라 그대로 돌려준다 (transferId={}, outcome={})", transferId, existing.outcome)
			return existing
		}

		val rejected = faults.shouldReject()
		return credits.save(
			InboundCredit(
				transferId = transferId,
				amount = amount,
				currency = currency,
				accountNumber = accountNumber,
				outcome = if (rejected) CreditOutcome.REJECTED else CreditOutcome.ACCEPTED,
				rejectReason = if (rejected) "수취 계좌를 찾을 수 없습니다" else null,
			)
		)
	}

	/**
	 * 거래를 조회한다. **타임아웃이 난 뒤 결과를 알 수 있는 유일한 방법이다.**
	 *
	 * <p>조회에는 결함을 넣지 않는다. 조회까지 못 믿으면 보내는 쪽에 **아무 수단도 남지 않고**,
	 * 그건 흉내가 아니라 그냥 고장 난 상대다. 실제 은행도 거래 조회는 별도 채널로 열어둔다.
	 */
	@Transactional(readOnly = true)
	fun find(transferId: UUID): InboundCredit? = credits.findById(transferId).orElse(null)
}
