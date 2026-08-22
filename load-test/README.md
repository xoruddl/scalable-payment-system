# 부하 테스트 (Phase 5 Step 1)

**목적은 이 테스트를 통과시키는 게 아니라 지금 시스템의 천장을 숫자로 기록하는 것입니다.**
그 baseline이 Phase 6에서 병목을 뚫은 뒤 비교할 기준선이 됩니다.

> 임계값(`thresholds`)은 일부러 **현실적인 목표치**로 잡아뒀습니다.
> baseline에서는 **통과하지 못하는 게 정상**입니다 — 못 맞춘 그 숫자가 출발점입니다.

## 먼저 알아야 할 것 — **202는 성공이 아닙니다**

```
POST /transfers  →  202 Accepted (PENDING)   ← 여기서 응답 끝. 돈은 아직 안 움직였다
                    ↓
     Outbox 릴레이(500ms) → Kafka → account 컨슈머 → ledger → COMPLETED
     ↑ 진짜 병목은 이 뒤에 있다
```

`POST /transfers`만 때리면 **"초당 몇 건을 접수할 수 있나"**를 잽니다. 그건 `INSERT` 두 번의
속도라 수천 TPS가 나오는데, **실제 송금 처리량이 아닙니다.**

그래서 모든 시나리오가 두 가지를 나눠 잽니다.

| 지표 | 무엇 | 어디서 |
|---|---|---|
| `http_req_duration{name:accept}` | 접수 지연 | 부하 시나리오 |
| **`settle_duration`** | **접수 → COMPLETED** | **관측용 시나리오(prober)** |
| **`settled`** | **끝까지 간 비율** | 〃 |
| `settle_timeout` | 시간 안에 안 끝난 건수 → **큐에 밀려 있다는 뜻** | 〃 |

### 부하와 측정을 왜 나눴나

부하를 거는 모든 요청에 대해 완료를 폴링하면 **폴링 자체가 부하**가 됩니다.
요청을 늘릴수록 조회도 같이 늘어 무엇을 재는지 알 수 없게 됩니다.

그래서 각 시나리오는 k6 시나리오 두 개를 동시에 돌립니다.

```
load    ramping-arrival-rate   접수만 하고 종결은 안 기다림   ← 부하를 거는 쪽
prober  constant-arrival-rate  초당 1건, 끝까지 따라감        ← 재는 쪽
```

### `ramping-arrival-rate`를 쓴 이유

VU 수를 고정하면(`ramping-vus`) 시스템이 느려질 때 **부하도 같이 줄어들어 문제가 가려집니다**
(coordinated omission). 초당 요청 수를 목표로 잡으면 느려져도 계속 밀어붙여 진짜 천장이 드러납니다.

---

## 준비

### 1. 인프라와 서비스 기동

```bash
docker compose -f docker-compose.dev.yml up -d          # MySQL·MongoDB·Redis·Kafka

./gradlew :account-service:bootRun         # 8081
./gradlew :transfer-service:bootRun        # 8082
./gradlew :ledger-service:bootRun          # 8083
```

`reconciliation-service`(8084)와 `notification-service`(8085)는 부하 경로에 없지만,
**함께 띄우면 실제 운영에 가까운 부하**가 됩니다(대사 배치가 60초마다 돌고 알림이 소비됨).

> ⚠️ `notification_db`가 없으면 알림 서비스가 안 뜹니다. MySQL 볼륨이 이미 있으면
> `docker/mysql-init`이 다시 안 돌기 때문입니다:
> `docker exec remittance-mysql mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS notification_db"`

### 2. k6

```bash
brew install k6
# 또는 설치 없이
docker run --rm -i -v "$PWD:/work" -w /work --network host grafana/k6:latest run load-test/scenarios/spread.js
```

> `--network host`가 안 먹는 환경(Docker Desktop 설정에 따라 다름)에서는 주소를 넘겨주세요:
> ```bash
> docker run --rm -i -v "$PWD:/work" -w /work \
>   -e ACCOUNT_URL=http://host.docker.internal:8081 \
>   -e TRANSFER_URL=http://host.docker.internal:8082 \
>   -e LEDGER_URL=http://host.docker.internal:8083 \
>   --add-host host.docker.internal:host-gateway \
>   grafana/k6:latest run load-test/scenarios/spread.js
> ```

### 3. 서버 쪽을 보는 눈 (Phase 5 Step 2)

k6가 주는 건 **클라이언트에서 본 숫자**입니다. 어디가 막혔는지는 서버 메트릭이 답합니다.

```bash
docker compose -f docker-compose.dev.yml up -d prometheus grafana
open http://localhost:3000        # 대시보드 "송금 시스템 — 개요" (로그인 없음)
```

부하를 걸기 전에 **수집 대상 상태(up) 패널이 전부 1인지** 먼저 보세요.
패널이 비었을 때 시스템 탓인지 수집 탓인지 거기서 갈립니다.

---

## 실행

```bash
k6 run load-test/scenarios/spread.js        # A. 골고루  — 파이프라인 병목
k6 run load-test/scenarios/hot-account.js   # B. 핫 계좌 — 계좌 락 병목
k6 run load-test/scenarios/read-heavy.js    # C. 조회 폭주 — 원장 읽기
```

결과 요약이 `load-test/results/<시나리오>-<시각>.json`에 남습니다. **지우지 마세요** —
Phase 6에서 같은 시나리오로 다시 재서 비교합니다.

### 조정할 수 있는 것

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `ACCOUNTS` / `SENDERS` | 60 | 시드 계좌 수. **적으면 의도치 않은 락 경합**이 생깁니다 |
| `TRANSFER_AMOUNT` | 100 | 송금 한 건의 금액 |
| `SEED_BALANCE` | 1000000000 | 계좌당 충전액 |
| `SETTLE_TIMEOUT_SEC` | 60 | 이만큼 안 끝나면 실패로 셈 |
| `ACCOUNT_URL` / `TRANSFER_URL` / `LEDGER_URL` | localhost | 서비스 주소 |

```bash
k6 run -e ACCOUNTS=200 -e SETTLE_TIMEOUT_SEC=120 load-test/scenarios/spread.js
```

---

## 시나리오별로 무엇을 기대하나

### A. `spread.js` — 골고루

계좌를 넓게 흩어 **락 경합을 최소화**합니다. 그러면 락이 아니라 **파이프라인이 먼저 막힙니다.**

> **가설: 약 50 TPS 근처에서 천장.**
> Outbox 릴레이가 500ms마다 100건 = 초당 200 이벤트인데, 송금 한 건이 `account`에서만
> 분개 2개 + 단계 이벤트 2개를 만듭니다.

천장에 닿으면 **접수는 여전히 빠른데** `settle_duration`이 치솟고 `settle_timeout`이 쌓입니다.

### B. `hot-account.js` — 핫 계좌 ★

받는 계좌를 **하나로 고정**합니다. 정산 계좌·가맹점 대표 계좌가 이렇게 됩니다.
그 계좌의 분산 락(TTL 3초)이 모든 입금을 **완전히 직렬화**합니다.

> **여기가 가장 중요합니다.** 접수(202)는 A와 똑같이 빠르고 HTTP 에러도 거의 안 납니다.
> 락 경합이 **비동기 파이프라인 뒤에서** 벌어지기 때문입니다.
> **접수 지표만 보고 있으면 시스템이 멀쩡해 보입니다.**

그리고 이 병목은 **서버를 늘려도 안 풀립니다** — 병목이 계좌 하나에 있기 때문입니다.
Phase 6에서 이걸 뚫습니다(락 홀딩 시간 단축 → 낙관적 락만으로 → 잔액 샤딩).

### C. `read-heavy.js` — 조회 폭주

원장은 조회가 몰리는 곳이라 WebFlux + MongoDB로 만들었습니다. **그 선택이 값을 하는지** 봅니다.
쓰기와 달리 조회는 접수/종결이 안 나뉘므로 `http_req_duration`이 그대로 답입니다.

---

## 흔한 함정

| 함정 | 증상 | 대응 |
|---|---|---|
| **워밍업 없음** | 첫 30초가 유독 느림 | JVM JIT·커넥션 풀이 덥혀지기 전. 앞부분은 버리고 읽으세요 |
| **잔액 고갈** | 갑자기 에러율 폭증 | `SEED_BALANCE`를 올리거나 `TRANSFER_AMOUNT`를 낮추세요 |
| **계좌가 너무 적음** | A인데 B처럼 나옴 | `ACCOUNTS`를 올리세요. 적으면 의도치 않은 락 경합이 생깁니다 |
| **클라이언트가 먼저 죽음** | 앱은 한가한데 지연이 큼 | k6와 서버가 같은 노트북입니다. CPU 경쟁을 감안해 해석하세요 |
| **한 번 돌리고 결론** | 값이 들쭉날쭉 | 3회 이상 돌려 중앙값을 쓰세요 |
| **평균만 봄** | 평균 50ms인데 사용자는 느리다고 함 | **p95/p99를 보세요.** 평균은 느린 요청을 빠른 요청이 덮습니다 |

---

## 다음

이 시나리오들로 잰 값을 `docs/PROGRESS.md`에 기록하면 **Phase 5 Step 3(baseline)**이 끝납니다.
그 전에 **Step 2(Prometheus + Grafana)**를 세우면 훨씬 잘 보입니다 — k6는 클라이언트에서 본
숫자만 주고, **어디가 막혔는지**(Outbox 적체, 락 대기, 커넥션 풀)는 서버 쪽 메트릭이 답합니다.

```bash
k6 run --out experimental-prometheus-rw load-test/scenarios/spread.js
```
