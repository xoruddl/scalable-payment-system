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

	/** 종결되지 않은 채 오래 남은 송금. 대사가 "흐름이 끊겼다"를 판단하는 근거다. */
	List<Transfer> findByStatusInAndRequestedAtBeforeOrderByRequestedAtAsc(
			Collection<TransferStatus> statuses, Instant before, Limit limit);
}
