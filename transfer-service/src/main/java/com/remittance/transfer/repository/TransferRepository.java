package com.remittance.transfer.repository;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

	Optional<Transfer> findByTransferId(UUID transferId);

	/**
	 * 이 키로 접수된 송금이 실제로 커밋됐는가.
	 *
	 * <p>발이 묶인 멱등성 키를 만났을 때 <b>풀어도 되는지를 가르는 유일한 근거</b>다.
	 * 있으면 접수는 끝난 것이고(키 기록만 못 남겼다), 없으면 접수가 커밋되지 않은 것이다.
	 */
	Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

	/** 여러 키를 한 번에. 대사가 묶인 키 목록을 한꺼번에 대조할 때 쓴다. */
	List<Transfer> findByIdempotencyKeyIn(Collection<String> idempotencyKeys);

	/** 종결되지 않은 채 오래 남은 송금. 대사가 "흐름이 끊겼다"를 판단하는 근거다. */
	List<Transfer> findByStatusInAndRequestedAtBeforeOrderByRequestedAtAsc(
			Collection<TransferStatus> statuses, Instant before, Limit limit);
}
