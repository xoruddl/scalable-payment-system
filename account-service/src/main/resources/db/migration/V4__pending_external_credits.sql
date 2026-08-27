-- 상대 은행에 보냈는데 답을 못 받은 입금 (Phase 6.5 Step 2b).
--
-- 이 테이블에 행이 있다는 것은 <b>돈이 나갔을 수도 있다</b>는 뜻이다.
-- 우리 고객 계좌는 이미 줄었는데 상대가 받았는지 모른다.
--
-- 메모리에만 두면 재기동 한 번에 사라진다. 그러면 고객 돈이 어디 있는지
-- <b>아무도 모르는 채로</b> 끝나므로 반드시 디스크에 남긴다.
--
-- 해소는 <b>조회</b>로만 한다. 다시 보내는 것은 답이 아니다 — 이미 처리됐을 수 있다.
CREATE TABLE pending_external_credits (
    -- 송금 ID가 곧 상대 은행에 준 멱등성 키다. 그래서 이게 기본키다.
    transfer_id        BINARY(16)    NOT NULL,
    bank_code          VARCHAR(11)   COLLATE utf8mb4_unicode_ci NOT NULL,
    to_account_number  VARCHAR(34)   COLLATE utf8mb4_unicode_ci NOT NULL,
    from_account_id    BINARY(16)    NOT NULL,
    amount             DECIMAL(19,2) NOT NULL,
    currency           VARCHAR(3)    COLLATE utf8mb4_unicode_ci NOT NULL,
    -- 나중에 해소될 때 transfer.credited를 만들어야 해서 그대로 들고 있는다.
    from_balance_after DECIMAL(19,2) NOT NULL,
    inquiries          INT           NOT NULL,
    next_inquiry_at    DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (transfer_id),
    -- 조회 루프의 유일한 조회 경로: next_inquiry_at < now ORDER BY next_inquiry_at.
    KEY idx_pending_next_inquiry (next_inquiry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
