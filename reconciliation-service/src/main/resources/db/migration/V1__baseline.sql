-- reconciliation-service 스키마 베이스라인 (Phase 6, DECISIONS.md 교체 4번)
--
-- 2026-08-24 홈서버 reconciliation_db를 mysqldump로 그대로 떠온 것이다.

-- 대사 한 회차. 60초마다 하나씩 늘어난다.
CREATE TABLE reconciliation_runs (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    started_at       DATETIME(6)  NOT NULL,
    -- 아직 도는 중이면 NULL. 이 상태로 오래 남아 있으면 배치가 죽은 것이다.
    finished_at      DATETIME(6)  NULL,
    accounts_checked INT          NOT NULL,
    finding_count    INT          NOT NULL,
    failure_reason   VARCHAR(500) COLLATE utf8mb4_unicode_ci NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 그 회차가 찾아낸 어긋남. 여기 행이 생겼다는 건 사람이 봐야 한다는 뜻이다.
CREATE TABLE reconciliation_findings (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    run_id      BIGINT       NOT NULL,
    type        ENUM('BALANCE_MISMATCH','STRANDED_IDEMPOTENCY_KEY','UNSETTLED_TRANSFER')
                             COLLATE utf8mb4_unicode_ci NOT NULL,
    subject     VARCHAR(64)  COLLATE utf8mb4_unicode_ci NOT NULL,
    detail      VARCHAR(500) COLLATE utf8mb4_unicode_ci NOT NULL,
    detected_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
