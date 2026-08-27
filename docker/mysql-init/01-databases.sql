-- 서비스마다 DB를 나눈다. 여기서 만드는 것은 <b>DB(스키마)까지</b>이고,
-- 그 안의 테이블은 각 서비스의 Flyway가 만든다 (src/main/resources/db/migration).
--
-- 왜 나뉘어 있나: Flyway는 이미 있는 DB에 붙어 테이블을 만들 뿐, DB 자체를 만들지 않는다.
-- 접속할 DB가 없으면 Flyway가 돌기도 전에 커넥션이 실패한다.
--
-- ⚠️ 이 파일은 <b>볼륨이 비어 있을 때만</b> 실행된다. 이미 쓰던 볼륨에 DB를 하나 더
-- 추가하려면 손으로 CREATE DATABASE 하거나 볼륨을 지워야 한다.
--
-- <b>두 번 데였다.</b> notification_db 때 한 번, external_bank_db 때 또 한 번.
-- 서비스를 늘릴 때는 이 파일을 고치는 것으로 끝나지 않는다 —
-- 이미 떠 있는 DB에도 손으로 만들어줘야 한다:
--
--   docker exec remittance-mysql mysql -uroot -proot \
--     -e 'CREATE DATABASE IF NOT EXISTS <이름> CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
CREATE DATABASE IF NOT EXISTS account_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS transfer_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS reconciliation_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Phase 6.5 — 상대 은행. 우리 조직이 아니므로 DB도 따로다.
CREATE DATABASE IF NOT EXISTS external_bank_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
