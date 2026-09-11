package com.remittance.account.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 원장을 도입하기 전부터 있던 잔액을 분개 한 줄로 이월한다.
 *
 * 왜 필요한가
 * Step 5a에서 "모든 잔액 변경을 원장에 남긴다"로 바꿨지만, 그 전에 이미 움직인 돈은 원장에 없다.
 * 그래서 옛 계좌들은 잔액이 아무리 정상이어도 대사에서 영원히 {@code BALANCE_MISMATCH}로 잡혔다.
 * 실제로 Step 5b e2e에서 17건이 그랬다 — 오탐이 아니라 정탐이라서 더 곤란했다. 지울 수도, 무시할 수도 없다.
 *
 * 왜 기준 시점(cutoff)으로 자르지 않았나
 * "그 전에 만들어진 계좌는 대사하지 않는다"가 더 간단하다. 하지만 그 계좌들은 앞으로 진짜로
 * 어긋나도 영영 잡히지 않는다. 사각지대를 만드는 대신, 과거를 한 줄로 요약해 넣어
 * 모든 계좌를 계속 대사 대상으로 남기는 쪽을 택했다.
 *
 * 이월 금액은 누가 계산하나
 * 차이는 "계좌 잔액 − 원장 합"이라 양쪽을 다 봐야 나온다. 그런데 그 둘을 함께 볼 수 있는 건
 * 대사 서비스뿐이고, 대사는 남의 데이터를 고치지 않는다는 게 이 저장소의 규칙이다.
 *
 * 그래서 본 값을 들고 와서 요청하고, 심는 건 주인이 한다. 호출자는 자기가 관측한
 * 잔액과 원장 합을 함께 보내고, 계좌 서비스는 그 스냅샷이 아직 유효한지 확인한 뒤 스스로 적는다.
 * 데이터를 바꾸는 주체는 끝까지 계좌 서비스다.
 *
 * 스냅샷이 낡는 문제
 * 잔액과 원장 합은 서로 다른 서비스에서 다른 순간에 읽은 값이다. 그 사이에 뭔가 움직였다면
 * 계산한 차이가 맞지 않는다. 두 가지로 막는다.
 *   - 잔액 CAS — 본 잔액과 지금 잔액이 다르면 거절한다.
 *   - 미발행 분개 검사 — 잔액은 그대로인데 원장만 뒤처진 경우를 잡는다.
 *       Outbox에 안 나간 항목이 있으면 거절한다. 잔액 검사만으로는 이쪽이 통과해버린다.
 *
 * 그래도 완전히 닫히지는 않는다. 발행은 됐지만 원장이 아직 소비하지 않은 이벤트가 있으면
 * 그만큼 두 번 세게 된다. 이건 멈추지 않고서는 못 막는 종류의 경합이라, 실무에서 하듯
 * 한산한 시점에 돌리고 다음 대사 회차로 확인하는 것으로 갈음한다 —
 * 잘못 심었으면 바로 다음 회차에 {@code BALANCE_MISMATCH}로 다시 잡힌다.
 */
@Service
@RequiredArgsConstructor
public class OpeningBalanceService {

	private static final Logger log = LoggerFactory.getLogger(OpeningBalanceService.class);

	private final BalanceGuard balanceGuard;
	private final OpeningBalanceExecutor openingBalanceExecutor;

	/**
	 * @param observedBalance 호출자가 계좌 서비스에서 읽은 잔액. 지금 잔액과 다르면 거절한다.
	 * @param ledgerBalance   호출자가 원장에서 읽은 합. 줄이 하나도 없었으면 0이다.
	 */
	public OpeningBalanceResult carryForward(UUID accountId, BigDecimal observedBalance,
			BigDecimal ledgerBalance) {
		// 잔액을 바꾸진 않지만 잔액을 읽고 판단하므로, 다른 변경과 같은 문으로 들어가야 한다.
		// 락 밖에서 하면 판단하는 사이에 잔액이 움직여 엉뚱한 금액을 심을 수 있다.
		OpeningBalanceResult result = balanceGuard.guardedWhole(accountId,
				() -> openingBalanceExecutor.execute(accountId, observedBalance, ledgerBalance));
		if (result.outcome() == OpeningBalanceResult.Outcome.SEEDED) {
			log.info("개시 잔액을 이월했다 (accountId={}, 금액={})", accountId, result.amount().toPlainString());
		}
		return result;
	}
}
