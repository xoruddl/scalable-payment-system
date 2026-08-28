-- 상대 은행으로 나가는 송금 (Phase 6.5).
--
-- 받는 쪽을 적는 방법이 둘이 되고, 정확히 하나만 쓴다.
--
--   우리 은행 계좌   to_account_id
--   상대 은행 계좌   to_bank_code + to_account_number
--
-- 상대 은행 계좌에는 UUID가 없다 — <b>우리가 발급한 적이 없기 때문이다.</b>
-- 그쪽 계좌번호는 그쪽 규칙을 따르는 문자열이라 우리는 그대로 전달만 한다.

ALTER TABLE transfers
    -- 상대 은행으로 나가면 우리 계좌 UUID가 없다. NULL을 허용해야 한다.
    MODIFY COLUMN to_account_id BINARY(16) NULL,
    ADD COLUMN to_bank_code      VARCHAR(11) COLLATE utf8mb4_unicode_ci NULL,
    ADD COLUMN to_account_number VARCHAR(34) COLLATE utf8mb4_unicode_ci NULL;
