package com.remittance.reconciliation.repository;

import com.remittance.reconciliation.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

	Optional<ReconciliationRun> findFirstByOrderByIdDesc();
}
