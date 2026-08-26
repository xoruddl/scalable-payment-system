package com.remittance.account.web.dto;

import jakarta.validation.constraints.Min;

/** @param shardCount 이 계좌의 잔액을 몇 조각으로 쓸지. 지금보다 작으면 거절한다. */
public record ShardingRequest(@Min(1) short shardCount) {
}
