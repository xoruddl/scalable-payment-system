-- 상대 은행이 받은 입금 (Phase 6.5).
--
-- 다른 서비스의 V1은 이미 있던 스키마를 뜬 것이지만, 이건 <b>처음부터 마이그레이션으로</b>
-- 만든 첫 테이블이다. Flyway를 넣은 보람이 여기서 나온다.
CREATE TABLE inbound_credits (
    -- <b>송금 ID가 곧 멱등성 키다.</b> 보내는 쪽이 정하고 우리가 기본키로 쓴다.
    -- 두 번 들어가지 않는다는 보장을 <b>우리가</b> 해야 한다 —
    -- 보내는 쪽은 우리 DB에 제약을 걸 수 없다.
    transfer_id    BINARY(16)    NOT NULL,
    account_number VARCHAR(34)   COLLATE utf8mb4_unicode_ci NOT NULL,
    amount         DECIMAL(19,2) NOT NULL,
    currency       VARCHAR(3)    COLLATE utf8mb4_unicode_ci NOT NULL,
    -- 한 번 정해지면 바뀌지 않는다. 나중에 조회해도 같은 답이 나와야 한다.
    outcome        ENUM('ACCEPTED','REJECTED') COLLATE utf8mb4_unicode_ci NOT NULL,
    reject_reason  VARCHAR(200)  COLLATE utf8mb4_unicode_ci NULL,
    received_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (transfer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
