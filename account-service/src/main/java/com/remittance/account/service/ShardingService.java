package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountBalanceShard;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.repository.AccountBalanceShardRepository;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 계좌 하나의 잔액을 <b>몇 조각으로 쓸지</b> 바꾼다. 운영 조치지 업무 흐름이 아니다.
 *
 * <p>붐비는 계좌를 찾아 쪼개는 일은 사람이(또는 지표를 보는 자동화가) 판단한다.
 * 전부 쪼개면 조회할 때마다 합산만 늘어 손해이므로, <b>붐비는 계좌에만</b> 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ShardingService {

	private static final Logger log = LoggerFactory.getLogger(ShardingService.class);

	/** 조각을 무한정 늘려도 좋을 이유가 없다. 출금이 조각 수만큼 락을 잡는다. */
	private static final short MAX_SHARDS = 64;

	private final AccountRepository accountRepository;
	private final AccountBalanceShardRepository shardRepository;
	private final ShardRouter shardRouter;

	/**
	 * <b>순서가 중요하다</b> — 조각 행을 먼저 만들고 그다음에 개수를 올린다.
	 *
	 * <p>반대로 하면 그 사이에 들어온 입금이 <b>아직 없는 조각</b>을 고른다.
	 * 새 조각은 잔액 0으로 시작하므로 총액은 변하지 않는다 — 돈을 옮기는 게 아니라
	 * <b>넣을 자리를 늘리는</b> 일이다.
	 */
	@Transactional
	public short widen(UUID accountId, short shardCount) {
		if (shardCount < 1 || shardCount > MAX_SHARDS) {
			throw new IllegalArgumentException("조각 수는 1~%d여야 한다: %d".formatted(MAX_SHARDS, shardCount));
		}
		Account account = accountRepository.findByAccountId(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));

		for (short shardNo = account.getShardCount(); shardNo < shardCount; shardNo++) {
			shardRepository.save(new AccountBalanceShard(accountId, shardNo, BigDecimal.ZERO));
		}
		shardRepository.flush();

		account.widenShards(shardCount);
		accountRepository.saveAndFlush(account);
		// 이 인스턴스는 즉시, 다른 인스턴스는 ShardRouter의 주기 갱신이 알린다.
		shardRouter.remember(accountId, shardCount);

		log.info("계좌를 {}조각으로 쓴다 (accountId={})", shardCount, accountId);
		return shardCount;
	}
}
