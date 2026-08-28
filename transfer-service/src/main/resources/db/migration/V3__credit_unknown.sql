-- 상대 은행에 보냈는데 답을 못 받은 상태 (Phase 6.5 Step 2b).
--
-- 실패가 아니다. 실패로 처리하면 이미 나간 돈을 환불해 <b>이중 지급</b>이 되고,
-- 성공으로 처리하면 안 간 돈을 갔다고 하는 셈이다. 어느 쪽으로도 밀 수 없어서
-- 제3의 상태가 필요하다.
--
-- ENUM에 뒤로 붙인다. 기존 행의 저장값이 바뀌지 않는다.
ALTER TABLE transfers
    MODIFY COLUMN status ENUM('COMPENSATING','COMPLETED','CREDIT_COMPLETED','DEBIT_COMPLETED',
                              'FAILED','PENDING','CREDIT_UNKNOWN')
        COLLATE utf8mb4_unicode_ci NOT NULL;
