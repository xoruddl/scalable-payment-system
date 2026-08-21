package com.remittance.transfer.service;

import com.remittance.transfer.domain.IdempotencyKey;
import com.remittance.transfer.domain.IdempotencyStatus;
import com.remittance.transfer.domain.Transfer;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 대사에 필요한 조회만 모아둔다. <b>고치는 일은 하지 않는다.</b>
 *
 * <p>Step 6b에서 송금에 멱등성 키가 남게 되어, 묶인 키가 <b>어느 쪽 사고인지</b> 구분할 수 있게
 * 됐다 — 그 키로 커밋된 송금이 있는지 보면 된다. 그래도 <b>여기서 풀지는 않는다.</b>
 * 키를 푸는 건 그 데이터의 주인인 접수 경로가 할 일이고, 실제로 재요청이 들어오면
 * {@code TransferService}가 같은 근거로 스스로 푼다. 대사는 <b>구분해서 알리는 데까지</b>다.
 *
 * <p>대사가 직접 풀면 아무도 재요청하지 않은 키까지 건드리게 되고, 그건 사고를 없애는 게 아니라
 * <b>사고의 흔적을 없애는 것</b>이다.
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
		List<IdempotencyKey> stranded = idempotencyKeyRepository
				.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
						IdempotencyStatus.IN_PROGRESS, Instant.now().minus(olderThan), Limit.of(limit));
		if (stranded.isEmpty()) {
			return List.of();
		}

		// 키마다 따로 묻지 않고 한 번에 대조한다 — 묶인 키가 많을 때 조회가 그 수만큼 늘어난다.
		Map<String, UUID> committed = transferRepository
				.findByIdempotencyKeyIn(stranded.stream().map(IdempotencyKey::getKey).toList())
				.stream()
				.collect(Collectors.toMap(Transfer::getIdempotencyKey, Transfer::getTransferId));

		return stranded.stream()
				.map(key -> StrandedKeyView.of(key, committed.get(key.getKey())))
				.toList();
	}
}
