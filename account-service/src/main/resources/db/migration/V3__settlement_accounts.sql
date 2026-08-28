-- 상대 은행과의 정산 계좌 (Phase 6.5).
--
-- 외부로 나가는 송금은 받는 쪽이 우리 계좌가 아니다. 그런데 원장은 송금 한 건에
-- <b>두 다리</b>가 모여야 종결로 본다(legs == 2). 상대 계좌를 우리 원장에 적을 수는 없으니,
-- 대신 "이 은행에 지급할 채무"를 담는 계좌를 두고 그쪽으로 적는다.
--
--   고객 계좌  −50,000  ─┐
--                        ├─ 우리 원장에 두 다리 (원장·대사 로직을 안 고쳐도 된다)
--   KB 정산계좌 +50,000  ─┘
--
-- 실제 은행이 하는 방식이다(nostro/vostro).

-- 은행당 하나뿐이라야 "KB로 가는 돈은 어디에 쌓이나"에 답이 하나로 정해진다.
-- 고객 계좌는 NULL이고, MySQL의 UNIQUE는 NULL을 여러 개 허용하므로 그대로 쓸 수 있다.
ALTER TABLE accounts
    ADD COLUMN settlement_bank_code VARCHAR(11) NULL,
    ADD CONSTRAINT uk_settlement_bank UNIQUE (settlement_bank_code);

-- ENUM에 값을 더한다. 뒤에 붙이면 기존 행의 저장값이 바뀌지 않는다.
ALTER TABLE accounts
    MODIFY COLUMN account_type ENUM('BUSINESS','PERSONAL','SETTLEMENT')
        COLLATE utf8mb4_unicode_ci NOT NULL;
