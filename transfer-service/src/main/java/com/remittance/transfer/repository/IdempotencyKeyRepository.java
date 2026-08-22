package com.remittance.transfer.repository;

import com.remittance.transfer.domain.IdempotencyKey;
import com.remittance.transfer.domain.IdempotencyStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

	/** 접수 도중 죽어 IN_PROGRESS로 남은 키. 재요청이 영원히 409를 받는다. */
	List<IdempotencyKey> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
			IdempotencyStatus status, Instant before, Limit limit);
}
