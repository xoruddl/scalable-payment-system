package com.remittance.account.web;

import com.remittance.account.service.ShardingService;
import com.remittance.account.web.dto.ShardingRequest;
import com.remittance.account.web.dto.ShardingResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 계좌를 몇 조각으로 쓸지 바꾸는 운영용 API. Gateway로 노출되지 않는다.
 *
 * <p>업무 흐름이 아니라 <b>붐비는 계좌에 내리는 처방</b>이라 여기 따로 둔다.
 */
@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class InternalShardingController {

	private final ShardingService shardingService;

	@PostMapping("/{accountId}/shards")
	public ShardingResponse widen(@PathVariable UUID accountId, @Valid @RequestBody ShardingRequest request) {
		return new ShardingResponse(accountId, shardingService.widen(accountId, request.shardCount()));
	}
}
