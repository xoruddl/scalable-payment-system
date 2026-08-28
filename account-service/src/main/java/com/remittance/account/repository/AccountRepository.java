package com.remittance.account.repository;

import com.remittance.account.domain.Account;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByAccountId(UUID accountId);

	/** 상대 은행의 정산 계좌. 은행당 하나뿐이다 (Phase 6.5). */
	Optional<Account> findBySettlementBankCode(String settlementBankCode);

	/** 쪼갠 계좌만. 몇 개 안 되므로 통째로 읽어 메모리에 들고 있는다 ({@code ShardRouter}). */
	List<Account> findByShardCountGreaterThan(short shardCount);

	/** 대사가 계좌를 순번대로 훑을 때 쓴다. 커서는 마지막으로 본 순번이다. */
	List<Account> findByIdGreaterThanOrderByIdAsc(Long id, Limit limit);
}
