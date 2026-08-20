package com.remittance.transfer.service;

import com.remittance.transfer.domain.IdempotencyStatus;
import com.remittance.transfer.domain.TransferStatus;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.StrandedKeyView;
import com.remittance.transfer.web.dto.UnsettledTransferView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 대사에 필요한 조회만 모아둔다. <b>고치는 일은 하지 않는다.</b>
 *
 * <p>발이 묶인 멱등성 키를 여기서 풀어주고 싶은 마음이 들지만, 지금 구조로는 안전하지 않다.
 * 접수가 실제로 커밋됐는지 알 방법이 없어서다 — 키에는 송금 ID가 완료 시점에야 채워지고,
 * 송금 쪽에는 키가 남지 않는다. 그래서 <b>"죽은 키인지"와 "커밋됐는데 기록만 못 남긴 키인지"를
 * 구분할 수 없고</b>, 잘못 풀면 재요청이 두 번째 송금을 만든다.
 * 대사는 찾아서 알리는 데까지만 한다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationQueryService {

	/** 종결되지 않은 상태들. 오래 여기 머물면 흐름이 끊긴 것이다. */
	private static final Set<TransferStatus> UNSETTLED = Arrays.stream(TransferStatus.values())
			.filter(status -> !status.isTerminal())
			.collect(Collectors.toUnmodifiableSet());

	private final TransferRepository transferRepository;
	private final IdempotencyKeyRepository idempotencyKeyRepository;

	@Transactional(readOnly = true)
	public List<UnsettledTransferView> unsettledTransfers(Duration olderThan, int limit) {
		return transferRepository
				.findByStatusInAndRequestedAtBeforeOrderByRequestedAtAsc(
						UNSETTLED, Instant.now().minus(olderThan), Limit.of(limit))
				.stream().map(UnsettledTransferView::from).toList();
	}

	@Transactional(readOnly = true)
	public List<StrandedKeyView> strandedKeys(Duration olderThan, int limit) {
		return idempotencyKeyRepository
				.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
						IdempotencyStatus.IN_PROGRESS, Instant.now().minus(olderThan), Limit.of(limit))
				.stream().map(StrandedKeyView::from).toList();
	}
}
