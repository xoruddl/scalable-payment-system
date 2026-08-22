package com.remittance.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 대사가 찾아낸 어긋남 한 건.
 *
 * <p>같은 문제가 매 회차 다시 발견되는 게 정상이다 — 대사는 고치지 않으므로, 사람이 손대기 전까지
 * 계속 잡힌다. 그래서 <b>회차마다 새로 쌓는다.</b> "언제부터 어긋나 있었나"가 남아야
 * 원인을 되짚을 수 있다.
 */
@Entity
@Table(name = "reconciliation_findings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationFinding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Long runId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40, updatable = false)
	private FindingType type;

	/** 무엇이 어긋났는지 가리키는 ID — 계좌 ID, 송금 ID, 멱등성 키 등 종류마다 다르다. */
	@Column(nullable = false, length = 64, updatable = false)
	private String subject;

	/** 사람이 읽을 설명. 금액 차이나 멈춘 상태처럼 <b>바로 손댈 수 있는 정보</b>를 담는다. */
	@Column(nullable = false, length = 500, updatable = false)
	private String detail;

	@Column(nullable = false, updatable = false)
	private Instant detectedAt;

	@Builder
	public ReconciliationFinding(Long runId, FindingType type, String subject, String detail, Instant detectedAt) {
		this.runId = runId;
		this.type = type;
		this.subject = subject;
		this.detail = detail;
		this.detectedAt = detectedAt;
	}
}
