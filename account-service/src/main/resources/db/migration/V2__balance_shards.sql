-- 잔액을 계좌 행에서 떼어내 <b>샤드 테이블</b>로 옮긴다 (Phase 6 Step 1, 잔액 샤딩).
--
-- 왜: 한 계좌의 잔액이 한 행이라 입금이 전부 그 행 하나에 줄을 선다.
-- 측정된 상한이 <b>한 계좌 초당 26건</b>이고, 이건 서버를 늘려도 안 변한다.
-- 행을 N개로 쪼개면 입금끼리 다른 행을 만지므로 그만큼 나란히 처리된다.
--
-- 이 마이그레이션은 <b>아직 쪼개지 않는다.</b> 모든 계좌가 샤드 하나(0번)를 갖고 시작하므로
-- 동작도 성능도 지금과 같아야 한다. 쪼개는 것은 다음 단계다 —
-- 여기서 숫자가 나빠지면 그건 샤딩이 아니라 <b>테이블을 옮긴 대가</b>다.

CREATE TABLE account_balance_shards (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    account_id BINARY(16)    NOT NULL,
    -- 0번부터. accounts.shard_count가 이 계좌에 몇 개가 있는지를 말한다.
    shard_no   SMALLINT      NOT NULL,
    balance    DECIMAL(19,2) NOT NULL,
    -- 낙관적 락은 계좌가 아니라 <b>샤드마다</b> 걸린다. 이게 쪼개는 의미의 절반이다.
    version    BIGINT        NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 한 계좌에 같은 번호의 샤드가 둘 있으면 잔액이 두 배로 보인다.
    UNIQUE KEY uk_balance_shard (account_id, shard_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 지금 있는 잔액을 전부 0번 샤드로 옮긴다. <b>합계는 그대로여야 한다</b> —
-- 여기서 한 푼이라도 어긋나면 정합성 대사가 즉시 잡아낸다.
INSERT INTO account_balance_shards (account_id, shard_no, balance, version, created_at, updated_at)
SELECT account_id, 0, balance, 0, created_at, updated_at FROM accounts;

-- 이 계좌를 몇 개로 쪼갰나. 1이면 안 쪼갠 것이고, 계좌 대부분이 여기 해당한다.
-- 경합이 없는 계좌를 쪼개면 조회할 때마다 합산만 늘어 손해다.
ALTER TABLE accounts ADD COLUMN shard_count SMALLINT NOT NULL DEFAULT 1;

-- 잔액이 두 곳에 있으면 반드시 갈라진다. 진실은 하나여야 한다.
--
-- 무중단 배포라면 여기서 지우면 안 되고, 두 벌 쓰기 → 검증 → 제거(expand/contract)로 가야 한다.
-- 지금은 서비스를 내렸다 올리는 구조라 그 복잡도를 살 이유가 없다.
-- Phase 9(배포 전략)에서 무중단이 되면 이 한 줄이 왜 위험한지가 그대로 실습 소재가 된다.
ALTER TABLE accounts DROP COLUMN balance;
