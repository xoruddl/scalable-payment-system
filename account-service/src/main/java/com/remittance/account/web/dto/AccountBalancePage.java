package com.remittance.account.web.dto;

import java.util.List;

/**
 * 대사는 계좌를 전부 훑어야 하므로 페이지로 끊어 넘긴다.
 *
 * <p>커서는 계좌의 내부 순번(id)이다. 대사가 도는 동안 계좌가 새로 생겨도 이미 지나간 페이지가
 * 밀리지 않는다 — offset 페이징이었다면 같은 계좌를 두 번 보거나 건너뛸 수 있다.
 */
public record AccountBalancePage(List<AccountBalanceView> items, Long nextCursor, boolean hasNext) {
}
