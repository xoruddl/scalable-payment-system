package com.remittance.reconciliation.repository;

import com.remittance.reconciliation.domain.ReconciliationFinding;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, Long> {

	List<ReconciliationFinding> findByRunIdOrderByIdAsc(Long runId);

	List<ReconciliationFinding> findByOrderByIdDesc(Limit limit);
}
