package com.remittance.account.external;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PendingExternalCreditRepository extends JpaRepository<PendingExternalCredit, UUID> {

	/** 지금 물어볼 것들. 오래 기다린 것부터 본다. */
	List<PendingExternalCredit> findByNextInquiryAtBeforeOrderByNextInquiryAtAsc(Instant now, Limit limit);
}
