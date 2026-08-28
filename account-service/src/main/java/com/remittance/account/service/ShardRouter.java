package com.remittance.account.service;

import com.remittance.account.domain.Account;
import com.remittance.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 입금을 <b>어느 조각에 넣을지</b> 고른다.
 *
 * <h2>왜 락보다 먼저 정해야 하는가</h2>
 * 쪼갠 이득은 <b>락이 조각별로 걸릴 때</b>만 나온다. 계좌 하나에 락 하나면 조각을 아무리 나눠도
 * 그 락에서 다시 줄을 선다. 그런데 락 키를 만들려면 조각 번호가 필요하고,
 * 조각 번호를 알려면 계좌를 읽어야 한다 — <b>락 밖에서</b> 읽어야 한다는 뜻이다.
 *
 * <h2>그래서 미리 들고 있는다</h2>
 * 입금마다 계좌를 한 번 더 읽으면 커넥션을 한 번 더 잡는다. Phase 6 Step 2에서 첫 병목이
 * 정확히 커넥션 대기였으므로, 그걸 되살릴 이유가 없다.
 *
 * <p><b>쪼갠 계좌만</b> 담는다. 나머지는 전부 1이라 담을 이유가 없고, 이 맵이 계좌 수만큼
 * 자라지도 않는다 — 핫 계좌는 몇 개뿐이다.
 *
 * <h2>늦게 알아도 안전한 이유</h2>
 * 다른 인스턴스가 계좌를 쪼개면 여기 반영되기까지 최대 {@link #REFRESH_MS}가 걸린다.
 * 그동안은 <b>예전 조각 수</b>로 고른다. 조각은 늘리기만 하고 줄일 수 없으므로
 * (Account#widenShards), 예전 수로 고른 번호는 <b>반드시 존재한다.</b>
 * 늦게 아는 대가는 "아직 안 빨라짐"이지 "틀림"이 아니다.
 */
@Component
@RequiredArgsConstructor
public class ShardRouter implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

	/** 다른 인스턴스가 쪼갠 것을 알아채는 데 걸리는 최대 시간. */
	private static final long REFRESH_MS = 60_000;

	private final AccountRepository accountRepository;

	/** 쪼갠 계좌만. 여기 없으면 조각이 하나다. */
	private final Map<UUID, Short> shardCounts = new ConcurrentHashMap<>();

	@Override
	public void afterPropertiesSet() {
		refresh();
	}

	@Scheduled(fixedDelay = REFRESH_MS)
	@Transactional(readOnly = true)
	public void refresh() {
		Map<UUID, Short> loaded = accountRepository.findByShardCountGreaterThan((short) 1).stream()
				.collect(Collectors.toMap(Account::getAccountId, Account::getShardCount));
		shardCounts.keySet().retainAll(loaded.keySet());
		shardCounts.putAll(loaded);
		if (!loaded.isEmpty()) {
			log.info("쪼갠 계좌를 갱신했다: {}", loaded);
		}
	}

	public short shardCount(UUID accountId) {
		return shardCounts.getOrDefault(accountId, (short) 1);
	}

	/**
	 * 어느 조각에 넣을지 고른다. <b>무작위</b>다.
	 *
	 * <p>순번을 돌리면(round-robin) 인스턴스마다 자기 순번을 갖게 되어, 인스턴스가 여럿이면
	 * 같은 조각을 동시에 고르는 일이 오히려 잦아진다. 송금 ID로 해시하는 방법도 있지만,
	 * 그러면 <b>같은 송금의 재시도가 늘 같은 조각으로</b> 몰려 경합이 난 조각을 계속 다시 두드린다.
	 */
	public short pickForCredit(UUID accountId) {
		short count = shardCount(accountId);
		return count <= 1 ? 0 : (short) ThreadLocalRandom.current().nextInt(count);
	}

	/** 방금 쪼갠 계좌를 이 인스턴스에 즉시 반영한다. 다른 인스턴스는 {@link #refresh()}가 알린다. */
	public void remember(UUID accountId, short shardCount) {
		shardCounts.put(accountId, shardCount);
	}
}
