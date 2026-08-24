-- notification-service 스키마 베이스라인 (Phase 6, DECISIONS.md 교체 4번)
--
-- 2026-08-24 홈서버 notification_db를 mysqldump로 그대로 떠온 것이다.

CREATE TABLE notifications (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_id          BINARY(16)   NOT NULL,
    recipient_account_id BINARY(16)   NOT NULL,
    type                 ENUM('TRANSFER_FAILED','TRANSFER_RECEIVED','TRANSFER_SENT')
                                      COLLATE utf8mb4_unicode_ci NOT NULL,
    status               ENUM('PENDING','SENT') COLLATE utf8mb4_unicode_ci NOT NULL,
    message              VARCHAR(300) COLLATE utf8mb4_unicode_ci NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    sent_at              DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- 이 서비스의 멱등성이 여기 걸려 있다. 같은 송금·같은 종류·같은 수신자면 알림은 하나다.
    -- 컨슈머가 재시도로 두 번 처리해도 두 번째는 여기서 막힌다.
    UNIQUE KEY uk_notification_transfer_type_recipient (transfer_id, type, recipient_account_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
