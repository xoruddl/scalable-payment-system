-- account-service 스키마 베이스라인 (Phase 6, DECISIONS.md 교체 4번)
--
-- 여태 이 스키마는 `ddl-auto: update`가 만들고 있었다. 여기 적힌 것은 새로 설계한 스키마가
-- 아니라 2026-08-24 홈서버 account_db에서 mysqldump로 그대로 떠온 것이다.
--
-- 베이스라인은 "있어야 할 모습"이 아니라 "지금 있는 모습"이어야 한다.
-- 여기서 이름을 예쁘게 고치면, 이미 떠 있는 DB(도장만 찍고 이 파일을 건너뛴다)와
-- 새로 만든 DB(이 파일을 실행한다)의 스키마가 갈라진다.
-- 그래서 아래 UK 이름 같은 Hibernate의 흔적도 그대로 둔다 — 고치려면 지금부터는 V2다.

CREATE TABLE accounts (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    account_id                 BINARY(16)   NOT NULL,
    owner_id                   BINARY(16)   NOT NULL,
    account_type               ENUM('BUSINESS','PERSONAL')      COLLATE utf8mb4_unicode_ci NOT NULL,
    status                     ENUM('ACTIVE','CLOSED','FROZEN') COLLATE utf8mb4_unicode_ci NOT NULL,
    balance                    DECIMAL(19,2) NOT NULL,
    currency                   VARCHAR(3)   COLLATE utf8mb4_unicode_ci NOT NULL,
    -- 대사(reconciliation)가 "언제까지의 잔액을 이월했나"를 보는 시각. 아직 이월 전이면 NULL.
    opening_balance_carried_at DATETIME(6)  NULL,
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    -- 낙관적 락. 분산 락을 껐을 때(account.lock.strategy=OPTIMISTIC) 이것만으로 버텨야 한다.
    version                    BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY UKc662f7lm5ec167m89rp50kb1d (account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE outbox_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id   BINARY(16)   NOT NULL,
    aggregate_type VARCHAR(50)  COLLATE utf8mb4_unicode_ci NOT NULL,
    event_type     VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL,
    payload        LONGTEXT     COLLATE utf8mb4_unicode_ci NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    -- 발행되면 시각이 찍힌다. NULL인 행이 릴레이가 가져갈 대상이다.
    published_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- 릴레이의 유일한 조회 경로: published_at IS NULL ORDER BY id.
    -- 순서가 (published_at, id)라야 미발행 구간 안에서 id 정렬까지 인덱스로 끝난다.
    KEY idx_outbox_unpublished (published_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 멱등 처리 흔적. 같은 이벤트를 몇 번 다시 처리해도 잔액이 한 번만 움직이게 하는 근거다.
CREATE TABLE processed_events (
    event_key    VARCHAR(150) COLLATE utf8mb4_unicode_ci NOT NULL,
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
