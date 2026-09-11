package com.remittance.reconciliation.repository;

import com.remittance.reconciliation.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

	/**
	 * 마지막으로 끝난 회차. 도는 중인 회차({@code finishedAt}이 빈 행)는 건너뛴다 —
	 * 기동 직후 그걸 집으면 "방금 끝났다"가 되어, 지표가 막으려는 거짓말을 지표가 하게 된다.
	 */
	Optional<ReconciliationRun> findFirstByFinishedAtIsNotNullOrderByIdDesc();
}
