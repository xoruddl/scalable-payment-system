package com.remittance.account.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 개시 잔액 이월 요청. <b>이월할 금액이 아니라 관측한 두 값</b>을 보낸다 —
 * 금액은 계좌 서비스가 직접 빼서 정한다.
 *
 * <p>금액을 그대로 받으면 호출자가 부르는 대로 원장에 줄이 생긴다. 그러면 이 엔드포인트는
 * "남이 내 원장에 아무 숫자나 적을 수 있는 문"이 된다. 관측값을 받아 <b>스냅샷이 아직 맞는지
 * 확인하고 스스로 계산</b>해야, 데이터를 바꾸는 주체가 끝까지 주인으로 남는다.
 *
 * @param observedBalance 호출자가 읽은 이 계좌의 잔액. 지금 잔액과 다르면 409로 거절한다.
 * @param ledgerBalance   호출자가 읽은 이 계좌의 원장 합. 줄이 없었으면 0.
 */
public record CarryOpeningBalanceRequest(
		@NotNull BigDecimal observedBalance,
		@NotNull BigDecimal ledgerBalance
) {
}
