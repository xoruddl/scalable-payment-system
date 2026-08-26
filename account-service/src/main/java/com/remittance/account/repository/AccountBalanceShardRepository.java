package com.remittance.account.repository;

import com.remittance.account.domain.AccountBalanceShard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceShardRepository extends JpaRepository<AccountBalanceShard, Long> {

	/** 순서를 고정한다 — 출금이 조각을 잡는 순서가 달라지면 교착이 생긴다. */
	List<AccountBalanceShard> findByAccountIdOrderByShardNoAsc(UUID accountId);

	Optional<AccountBalanceShard> findByAccountIdAndShardNo(UUID accountId, short shardNo);

	/**
	 * 여러 계좌의 잔액을 <b>한 번에</b> 합산한다. 대사가 계좌를 페이지로 훑을 때 쓴다.
	 *
	 * <p>계좌마다 조각을 따로 읽으면 페이지 크기만큼 쿼리가 나간다(N+1).
	 * 대사는 계좌 전체를 훑으므로 그 차이가 그대로 대사 시간이 된다.
	 */
	/**
	 * 이 계좌에서 <b>한 조각을 뺀 나머지</b>의 합. 입금이 조각 하나만 읽을 때,
	 * 분개장에 남길 "변경 후 잔액"을 만들기 위해 쓴다.
	 *
	 * <p>잠그지 않는 읽기라 이 값 때문에 경합이 생기지는 않는다.
	 * 대신 <b>읽은 시점의 값</b>이라 근사치다.
	 */
	@Query("""
			select coalesce(sum(s.balance), 0)
			from AccountBalanceShard s
			where s.accountId = :accountId and s.shardNo <> :shardNo
			""")
	BigDecimal totalExcluding(@Param("accountId") UUID accountId, @Param("shardNo") short shardNo);

	@Query("""
			select s.accountId as accountId, sum(s.balance) as total
			from AccountBalanceShard s
			where s.accountId in :accountIds
			group by s.accountId
			""")
	List<Total> totalsOf(@Param("accountIds") Collection<UUID> accountIds);

	interface Total {
		UUID getAccountId();

		BigDecimal getTotal();
	}
}
