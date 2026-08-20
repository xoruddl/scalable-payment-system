package com.remittance.ledger.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 원장만 보고 재구성한 잔액. 입금은 더하고 출금은 뺀 값이다.
 *
 * <p>계좌는 잔액을 숫자 하나로 들고 있고 원장은 움직인 내역을 줄로 들고 있다.
 * 같은 사실을 두 번 적어둔 셈이라, 정상이라면 이 값이 계좌 잔액과 같아야 한다.
 */
public record LedgerBalanceView(UUID accountId, BigDecimal balance) {
}
