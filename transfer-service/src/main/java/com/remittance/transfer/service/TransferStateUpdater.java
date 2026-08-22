package com.remittance.transfer.service;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.exception.TransferNotFoundException;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 송금 상태 전이 한 건을 <b>하나의 트랜잭션</b>으로 실행한다.
 *
 * <p>재시도(낙관적 락 충돌)를 하려면 <b>트랜잭션 밖에서</b> 다시 시도해야 한다. 같은 트랜잭션
 * 안에서 다시 읽어봐야 이미 깨진 영속성 컨텍스트를 보게 되고, 무엇보다 그 사이 다른 쪽이
 * 커밋한 값을 볼 수 없다. 그래서 전이는 이 빈에, 재시도는 {@link TransferService}에 둔다.
 *
 * <p>{@code @Transactional} 프록시가 걸리려면 호출부가 <b>다른 빈을 통해</b> 불러야 한다.
 * 같은 클래스 안에서 부르면(self-invocation) 적용되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferStateUpdater {

	private static final Logger log = LoggerFactory.getLogger(TransferStateUpdater.class);

	private final TransferRepository transferRepository;
	private final TransferOutboxRecorder outboxRecorder;

	/**
	 * 정상 흐름을 {@code target}까지 진행시킨다.
	 *
	 * <p>직전 단계가 아니어도 <b>진행도가 앞서면 건너뛰어서라도 적용</b>한다. 뒤 단계 이벤트가
	 * 도착했다는 건 앞 단계가 이미 끝났다는 뜻이고, 여기서 버리면 그 이벤트는 다시 오지 않아
	 * 송금이 영원히 멈춘다.
	 */
	@Transactional
	public void advanceTo(UUID transferId, TransferStatus target) {
		Transfer transfer = findOrThrow(transferId);
		TransferStatus current = transfer.getStatus();

		if (current.isTerminal() || current.isCompensating() || !target.isAheadOf(current)) {
			logSkip(transferId, current, target);
			return;
		}

		transfer.advanceTo(target);
		if (target == TransferStatus.COMPLETED) {
			// 완료 사실도 이벤트로 남긴다. 알림 같은 후속 처리가 이걸 구독한다 (Phase 3).
			outboxRecorder.record(transfer, TransferEventType.COMPLETED);
		} else {
			transferRepository.save(transfer);
		}
	}

	/** 되돌리는 중임을 표시한다. 아직 종결이 아니다 — 환불이 끝나야 닫힌다. */
	@Transactional
	public void markCompensating(UUID transferId) {
		Transfer transfer = findOrThrow(transferId);
		if (transfer.getStatus().isTerminal() || transfer.getStatus().isCompensating()) {
			logSkip(transferId, transfer.getStatus(), TransferStatus.COMPENSATING);
			return;
		}
		transfer.markCompensating();
		transferRepository.save(transfer);
	}

	/** 실패로 종결하고 그 사실을 이벤트로 남긴다. */
	@Transactional
	public void markFailed(UUID transferId, String reason) {
		Transfer transfer = findOrThrow(transferId);
		if (transfer.getStatus().isTerminal()) {
			logSkip(transferId, transfer.getStatus(), TransferStatus.FAILED);
			return;
		}
		transfer.markFailed(reason);
		outboxRecorder.record(transfer, TransferEventType.FAILED);
	}

	private void logSkip(UUID transferId, TransferStatus current, TransferStatus target) {
		log.info("적용하지 않고 건너뛴다 (transferId={}, 현재={}, 요청={})", transferId, current, target);
	}

	private Transfer findOrThrow(UUID transferId) {
		return transferRepository.findByTransferId(transferId)
				.orElseThrow(() -> new TransferNotFoundException(transferId));
	}
}
