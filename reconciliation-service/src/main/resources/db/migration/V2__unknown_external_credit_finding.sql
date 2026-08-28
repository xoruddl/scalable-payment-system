-- 대사가 찾는 어긋남에 "상대 은행 결과를 오래 모르는 건"을 추가한다 (Phase 6.5).
--
-- type이 MySQL ENUM이라 자바 enum에 상수를 더하는 것만으로는 저장되지 않는다.
-- 넣으면 "Data truncated for column 'type'"으로 insert가 실패하고, 대사 회차 전체가
-- 롤백된다 — 새 종류 하나 때문에 기존 발견까지 함께 사라진다.
--
-- D-001에 적어둔 "엔티티만 고치면 되던 게 두 곳이 됐다"는 대가가 여기서 처음 실제로 청구됐다.
ALTER TABLE reconciliation_findings
    MODIFY COLUMN type ENUM(
        'BALANCE_MISMATCH',
        'STRANDED_IDEMPOTENCY_KEY',
        'UNSETTLED_TRANSFER',
        'UNKNOWN_EXTERNAL_CREDIT'
        ) COLLATE utf8mb4_unicode_ci NOT NULL;
