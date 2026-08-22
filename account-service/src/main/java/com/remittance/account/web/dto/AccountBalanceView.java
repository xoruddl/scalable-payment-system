package com.remittance.account.web.dto;

import com.remittance.account.domain.Account;

import java.math.BigDecimal;
import java.util.UUID;

/** 대사에 필요한 최소한만 담는다 — 잔액이 얼마인가. */
public record AccountBalanceView(UUID accountId, BigDecimal balance, String currency) {

	public static AccountBalanceView from(Account account) {
		return new AccountBalanceView(account.getAccountId(), account.getBalance(), account.getCurrency());
	}
}
