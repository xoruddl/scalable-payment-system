-- transfer-service 스키마 베이스라인 (Phase 6, DECISIONS.md 교체 4번)
--
-- 2026-08-24 홈서버 transfer_db를 mysqldump로 그대로 떠온 것이다. 설계를 새로 한 게 아니다.
-- 베이스라인은 "있어야 할 모습"이 아니라 "지금 있는 모습"이어야 한다 — 자세한 이유는
-- account-service의 같은 파일에 적어두었다.

CREATE TABLE transfers (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_id     BINARY(16)   NOT NULL,
    from_account_id BINARY(16)   NOT NULL,
    to_account_id   BINARY(16)   NOT NULL,
    amount          DECIMAL(19,2) NOT NULL,
    currency        VARCHAR(3)   COLLATE utf8mb4_unicode_ci NOT NULL,
    status          ENUM('COMPENSATING','COMPLETED','CREDIT_COMPLETED','DEBIT_COMPLETED','FAILED','PENDING')
                                 COLLATE utf8mb4_unicode_ci NOT NULL,
    memo            VARCHAR(100) COLLATE utf8mb4_unicode_ci NULL,
    failure_reason  VARCHAR(255) COLLATE utf8mb4_unicode_ci NULL,
    -- 이 컬럼의 UNIQUE가 "같은 키로 두 번 송금되지 않는다"의 마지막 방어선이다.
    -- 앱에서 먼저 막지만, 동시에 들어오면 결국 여기서 걸린다.
    idempotency_key VARCHAR(36)  COLLATE utf8mb4_unicode_ci NULL,
    requested_at    DATETIME(6)  NOT NULL,
    completed_at    DATETIME(6)  NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY UKppoqrpyegae436cly4gobytij (transfer_id),
    UNIQUE KEY UKl3ede3q7badjcx6g20fi4lkdu (idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 키 선점 → 송금 저장 → 결과 기록이 각각 다른 트랜잭션이다.
-- 어디서 죽었는지 구분하려고 나눈 구조라, status가 그 세 단계를 그대로 담는다.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(36) COLLATE utf8mb4_unicode_ci NOT NULL,
    request_hash    VARCHAR(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    status          ENUM('COMPLETED','FAILED','IN_PROGRESS') COLLATE utf8mb4_unicode_ci NOT NULL,
    transfer_id     BINARY(16)  NULL,
    created_at      DATETIME(6) NOT NULL,
    expires_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE outbox_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id   BINARY(16)   NOT NULL,
    aggregate_type VARCHAR(50)  COLLATE utf8mb4_unicode_ci NOT NULL,
    event_type     VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL,
    payload        LONGTEXT     COLLATE utf8mb4_unicode_ci NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    published_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_unpublished (published_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
