package com.remittance.account.settlement;

import com.remittance.account.domain.Account;
import com.remittance.account.domain.AccountType;
import com.remittance.account.repository.AccountRepository;
import com.remittance.account.service.BalanceShards;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 상대 은행마다 하나씩 있는 <b>정산 계좌</b>를 찾아준다 (Phase 6.5).
 *
 * <h2>왜 있는가</h2>
 * 외부로 나가는 송금은 받는 쪽이 우리 계좌가 아니다. 그런데 원장은 송금 한 건에
 * <b>두 다리</b>가 모여야 종결로 본다. 상대 계좌를 우리 원장에 적을 수는 없으니,
 * 대신 <b>"이 은행에 지급할 채무"</b>를 담는 계좌를 두고 그쪽으로 적는다.
 *
 * <pre>
 *   고객 계좌  −50,000  ─┐
 *                        ├─ 우리 원장에 두 다리 (원장·대사 로직을 안 고쳐도 된다)
 *   KB 정산계좌 +50,000  ─┘
 * </pre>
 *
 * <h2>메모리에 들고 있는 이유</h2>
 * 은행 수만큼만 있고 <b>거의 안 바뀐다.</b> 입금마다 계좌를 한 번 더 읽으면 커넥션을 한 번 더
 * 잡는데, Phase 6에서 첫 병목이 정확히 커넥션 대기였으므로 되살릴 이유가 없다
 * ({@code ShardRouter}와 같은 판단이다).
 */
@Component
@RequiredArgsConstructor
public class SettlementAccounts {

	private static final Logger log = LoggerFactory.getLogger(SettlementAccounts.class);

	private final AccountRepository accountRepository;
	private final BalanceShards balanceShards;

	/** 은행 코드 → 정산 계좌 ID. 한 번 정해지면 안 바뀐다 (계좌를 지우지 않는다). */
	private final Map<String, UUID> byBankCode = new ConcurrentHashMap<>();

	/**
	 * 이 은행의 정산 계좌를 찾는다. 없으면 <b>만든다.</b>
	 *
	 * <p>미리 만들어두지 않는 이유는 <b>은행이 늘어날 때 사람 손을 타지 않게</b> 하기 위해서다.
	 * 첫 송금이 그 은행으로 나가는 순간 자리가 생긴다. 잔액은 0에서 시작하므로
	 * 만드는 것만으로는 돈이 생기지 않는다.
	 */
	@Transactional
	public UUID of(String bankCode, String currency) {
		UUID cached = byBankCode.get(bankCode);
		if (cached != null) {
			return cached;
		}
		Account account = accountRepository.findBySettlementBankCode(bankCode)
				.orElseGet(() -> create(bankCode, currency));
		byBankCode.put(bankCode, account.getAccountId());
		return account.getAccountId();
	}

	private Account create(String bankCode, String currency) {
		Account account = accountRepository.save(Account.builder()
				// 정산 계좌에는 고객이 없다. 은행 코드에서 만든 고정 ID를 주인으로 둬서
				// "누구 것도 아니지만 아무거나도 아니다"를 분명히 한다.
				.ownerId(UUID.nameUUIDFromBytes(("settlement:" + bankCode).getBytes()))
				.currency(currency)
				.accountType(AccountType.SETTLEMENT)
				.settlementBankCode(bankCode)
				.build());
		balanceShards.createFirstShard(account);
		log.info("정산 계좌를 만들었다 (bank={}, accountId={})", bankCode, account.getAccountId());
		return account;
	}
}
