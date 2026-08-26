package com.remittance.transfer.service;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 한 건을 <b>하나의 트랜잭션</b>으로 끝낸다 — 송금 저장 · Outbox 기록 · 키 결과 기록.
 *
 * <h2>왜 합쳤나 (Phase 6, 2026-08-26)</h2>
 * 셋 다 {@code transfer_db}인데 트랜잭션이 둘로 갈라져 있어 <b>커밋이 두 번</b> 일어났다.
 * 2026-08-24 실측에서 커밋 하나가 <b>평균 47.8ms짜리 공유 관문</b>을 지나고, 송금 한 건이
 * 그 관문을 10.5번 지난다는 것이 드러났다. 그중 접수가 3번을 쓰고 있었다.
 *
 * <p>커밋을 아무리 잘 묶어도(group commit은 이미 5.5건당 fsync 1회로 잘 되고 있다)
 * <b>관문을 지나는 횟수 자체를 줄이지 않으면</b> 더 나아지지 않는다.
 *
 * <h2>합쳐서 잃는 것이 없다 — 오히려 실패 모드가 하나 사라진다</h2>
 * 갈라져 있을 때는 <b>"송금은 커밋됐는데 키에는 아직 안 적힌"</b> 창이 있었다.
 * 그 사이에 죽으면 키가 {@code IN_PROGRESS}로 남고, 재요청이
 * {@code TransferService#recoverInProgress}의 전진 복구를 타야 했다.
 * 한 트랜잭션이 되면 <b>그 상태 자체가 만들어지지 않는다.</b>
 *
 * <p>복구 코드는 <b>지우지 않는다.</b> 이 변경 전에 만들어진 행이 아직 있을 수 있고,
 * 방어선을 없애는 것과 필요 없게 만드는 것은 다르다.
 *
 * <h2>키 선점({@code reserve})은 합치지 않는다</h2>
 * 그건 <b>별도 커밋이어야 의미가 있다.</b> 동시에 같은 키로 들어온 요청이 PK 충돌로
 * 곧바로 실패해야 재요청 경로로 갈 수 있다. 같은 트랜잭션에 넣으면 충돌 대신
 * <b>행 잠금에서 기다리게</b> 되어, 접수 지연이 상대편 트랜잭션 길이에 묶인다.
 *
 * <p>{@code @Transactional} 프록시가 걸리려면 호출부가 <b>다른 빈을 통해</b> 불러야 한다.
 * 안쪽 두 컴포넌트도 각자 {@code @Transactional}이지만 기본 전파가 {@code REQUIRED}라
 * 새 트랜잭션을 열지 않고 <b>이 트랜잭션에 합류</b>한다 — 그래서 커밋이 한 번이다.
 */
@Component
@RequiredArgsConstructor
public class TransferAcceptExecutor {

	private final TransferOutboxRecorder outboxRecorder;
	private final IdempotencyService idempotencyService;

	@Transactional
	public Transfer accept(String idempotencyKey, CreateTransferRequest request) {
		Transfer transfer = outboxRecorder.record(
				Transfer.builder()
						.fromAccountId(request.fromAccountId())
						.toAccountId(request.toAccountId())
						.amount(request.amount())
						.currency(request.currency())
						.memo(request.memo())
						// 송금 저장과 같은 트랜잭션에 들어간다 — 송금이 있으면 키도 반드시 적혀 있다.
						.idempotencyKey(idempotencyKey)
						.build(),
				TransferEventType.REQUESTED);

		// 여기서 COMPLETED는 "송금이 끝났다"가 아니라 "접수가 끝났다"는 뜻이다.
		// 재요청은 이 시점 이후로 항상 같은 transferId를 돌려받는다.
		idempotencyService.complete(idempotencyKey, transfer.getTransferId());
		return transfer;
	}
}
