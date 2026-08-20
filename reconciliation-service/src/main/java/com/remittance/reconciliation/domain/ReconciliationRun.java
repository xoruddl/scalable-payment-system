package com.remittance.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 대사 한 회차.
 *
 * <p>회차를 따로 남기는 이유는 <b>"어긋난 게 없었다"와 "대사가 안 돌았다"를 구분</b>하기 위해서다.
 * 발견 건수만 보면 둘 다 0이라 똑같아 보인다. 배치가 죽은 걸 "깨끗하다"로 오해하는 게
 * 어긋남 자체보다 위험하다.
 */
@Entity
@Table(name = "reconciliation_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Instant startedAt;

	private Instant finishedAt;

	@Column(nullable = false)
	private int accountsChecked;

	@Column(nullable = false)
	private int findingCount;

	/** 대사 도중 다른 서비스를 못 읽었으면 남긴다. 이 회차의 결과를 그대로 믿으면 안 된다는 표시다. */
	@Column(length = 500)
	private String failureReason;

	public ReconciliationRun(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public void complete(int accountsChecked, int findingCount, Instant finishedAt) {
		this.accountsChecked = accountsChecked;
		this.findingCount = findingCount;
		this.finishedAt = finishedAt;
	}

	public void fail(String failureReason, Instant finishedAt) {
		this.failureReason = failureReason;
		this.finishedAt = finishedAt;
	}
}
