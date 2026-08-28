package com.remittance.account.web.dto;

import java.util.UUID;

public record ShardingResponse(UUID accountId, short shardCount) {
}
