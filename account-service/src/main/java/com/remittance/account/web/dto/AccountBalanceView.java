package com.remittance.account.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** 대사에 필요한 최소한만 담는다 — 잔액이 얼마인가. 쪼갠 계좌면 조각들의 합이다. */
public record AccountBalanceView(UUID accountId, BigDecimal balance, String currency) {
}
