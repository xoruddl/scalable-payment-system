package com.remittance.ledger.service;

import com.remittance.ledger.AbstractIntegrationTest;
import com.remittance.ledger.domain.BalanceChangeReason;
import com.remittance.ledger.domain.TransactionDirection;
import com.remittance.ledger.messaging.AccountEvents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상대 은행으로 나간 송금도 <b>원장이 두 다리로 세는가</b> (Phase 6.5).
 *
 * <h2>왜 이 테스트가 있어야 하나 ★</h2>
 * 이건 기능 검증이 아니라 <b>아키텍처 결정의 전제 검증</b>이다.
 *
 * <p>외부 송금은 받는 쪽이 우리 계좌가 아니라서 원장에 한 다리밖에 안 남는다. 그런데 원장은
 * 두 다리가 모여야 종결 신호({@code transfer.ledger-recorded})를 내고, 그게 없으면
 * <b>송금이 영영 COMPLETED가 되지 않는다.</b>
 *
 * <p>그래서 상대 은행마다 <b>정산 계좌</b>를 두고 그쪽으로 적기로 했다. 그 선택의 근거가
 * "그러면 원장·대사 로직을 하나도 안 고쳐도 된다"였는데, <b>그건 코드를 읽고 내린 추론이었다.</b>
 * 추론 위에 다음 Step을 쌓기 전에 여기서 못 박는다.
 *
 * <p>핵심은 {@code isTransferFullyRecorded}가 <b>계좌가 아니라 reason을</b> 센다는 점이다.
 * 정산 계좌로 들어간 입금도 {@code TRANSFER_CREDIT}이므로 그대로 두 번째 다리가 된다.
 * 계좌를 셌다면 이 설계는 성립하지 않았을 것이다.
 */
@SpringBootTest
class ExternalTransferLegsTest extends AbstractIntegrationTest {

	@Autowired
	private TransactionService transactionService;

	private AccountEvents.BalanceChanged leg(UUID transferId, UUID accountId,
			BalanceChangeReason reason, TransactionDirection direction) {
		return new AccountEvents.BalanceChanged(
				UUID.randomUUID(), accountId, reason, direction,
				new BigDecimal("50000.00"), new BigDecimal("50000.00"), "KRW",
				transferId, Instant.now());
	}

	@Test
	void 고객_계좌_출금과_정산_계좌_입금이면_두_다리로_센다() {
		UUID transferId = UUID.randomUUID();
		UUID customerAccount = UUID.randomUUID();
		UUID settlementAccount = UUID.randomUUID();

		transactionService.record(
				leg(transferId, customerAccount, BalanceChangeReason.TRANSFER_DEBIT,
						TransactionDirection.DEBIT)).block();
		transactionService.record(
				leg(transferId, settlementAccount, BalanceChangeReason.TRANSFER_CREDIT,
						TransactionDirection.CREDIT)).block();

		// 받는 쪽이 고객 계좌든 정산 계좌든 원장에는 똑같이 TRANSFER_CREDIT 한 줄이다.
		// 이게 성립해야 "원장·대사를 안 고쳐도 된다"는 전제가 산다.
		assertThat(transactionService.isTransferFullyRecorded(transferId).block())
				.as("정산 계좌 다리도 두 번째 다리로 세야 송금이 COMPLETED까지 간다")
				.isTrue();
	}

	@Test
	void 출금만_있으면_아직_끝난_것이_아니다() {
		UUID transferId = UUID.randomUUID();

		transactionService.record(
				leg(transferId, UUID.randomUUID(), BalanceChangeReason.TRANSFER_DEBIT,
						TransactionDirection.DEBIT)).block();

		// 상대가 아직 못 받았거나 결과를 모르는 구간이다. 여기서 종결 신호가 나가면
		// <b>돈이 안 갔는데 COMPLETED가 된다.</b>
		assertThat(transactionService.isTransferFullyRecorded(transferId).block())
				.as("한 다리만으로 끝났다고 보면 안 간 돈을 갔다고 하는 셈이다")
				.isFalse();
	}
}
