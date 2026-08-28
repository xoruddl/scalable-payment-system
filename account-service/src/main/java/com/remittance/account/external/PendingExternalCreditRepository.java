package com.remittance.account.external;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PendingExternalCreditRepository extends JpaRepository<PendingExternalCredit, UUID> {

	/** 지금 물어볼 것들. 오래 기다린 것부터 본다. */
	List<PendingExternalCredit> findByNextInquiryAtBeforeOrderByNextInquiryAtAsc(Instant now, Limit limit);

	/** <b>보냈는데 결과를 모르는</b> 건수. 돈이 나갔을 수 있다. */
	long countBySentTrue();

	/** <b>보내지도 못해</b> 미뤄둔 건수. 돈은 안 나갔다. */
	long countBySentFalse();
}
