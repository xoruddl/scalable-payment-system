# 진행 기록

문서마다 역할이 다릅니다 (전체 지도는 저장소 루트의 `AGENTS.md` 참고).

| 문서 | 역할 |
|---|---|
| `ARCHITECTURE.md` | **구조와 흐름** — 서비스 경계, 이벤트가 흐르는 길 |
| `DECISIONS.md` | **기술 결정** — 자체 구현을 무엇으로 왜 갈아탔나 |
| `ROADMAP.md` | **앞으로 할 일** — Phase 0~13 계획 |
| `PROGRESS.md` (이 문서) | **실제로 한 일과 그 이유** — 시간순 기록 |
| `CONTRIBUTING.md` | **규칙** — 커밋 컨벤션, 브랜치 전략 |
| `../AGENTS.md` | **저장소 진입점** — 프로젝트 목적, 작업 규칙, 실행 방법 |

> 각 Step을 완료할 때마다 이 문서를 갱신합니다.

---

## 현재 위치

```
Phase 0  ✅  프로젝트 기반 설정
Phase 1  ✅  핵심 도메인 서비스 (Account / Transfer / Ledger)
Phase 2  ✅  분산 환경 데이터 정합성   ← 모든 Step 완료
Phase 3  ✅  이벤트 기반 아키텍처 (알림 컨슈머까지)
Phase 5  ✅  측정 기반    Step 1 부하 · Step 2 관측 · Step 3 baseline 전부 완료
Phase 6  ✅  고동시성 대응 — 용량 40 → 100 TPS, 핫 계좌 25 → 70 TPS
             Step 2 파이프라인 ✅ 7건 (커넥션 풀 · 릴레이 배치 · 리스너 concurrency ×3 ·
                                      원장 인덱스 · 릴레이 드레인)
             Step 1 핫 계좌  ✅ 관문 통과 · 락 전략 비교 · 갇힘 9 → 0건
                              계좌별 전략 분리는 근거가 없어 취소
             🔁 Flyway     ✅ 스키마를 앱이 아니라 SQL이 만든다 (D-001)
             잔액 샤딩     ✅ 핫 계좌 용량 25 → 50 TPS (2배)
             파티션 3 → 6   ✅ 용량 50 → 60 TPS (2배가 아니라 1.2배)
             80ms 구간 측정 ✅ 지연 쓰기+commit이 평균 47.8ms로 91.3% 지배
             fsync 진단     ✅ group commit은 이미 잘 된다 (커밋 5.5건당 fsync 1회)
             접수 커밋 −1   ✅ transfer_db 6.05 → 5.06. 용량은 그대로 60 TPS
             알림 커밋 −50%  ✅ 송금당 11.07 → 9.12회. 그런데 용량은 안 움직였다
             redo 100MB→1GB ✅ 용량 60 → 70 TPS (p99 11.8초 → 3.1초)
             종료 조건 5개  ✅ 지연·유실0·대사0·과부하회복·재현성 전부 통과
                              80 TPS는 "여기서 멈춘 이유"만 적고 남긴다
Phase 6.5 🔄 상대 은행 (Kotlin, HTTP)  ← 지금 여기
             Step 1 서비스  ✅ 여섯 번째 서비스가 뜬다. 진짜 타임아웃으로
                              "돈은 들어갔는데 응답이 없다"를 확인했다
             Step 2a 외부 경로 ✅ 정산 계좌로 원장 두 다리를 맞춘다
             Step 2b 모르는 상태 ✅ 답이 없으면 재시도가 아니라 조회로 결론짓는다
             홈서버 e2e     ✅ 외부 송금이 2초에 COMPLETED. 타임아웃도 조회로 1초 해소
             격벽 근거      ✅ 느린 상대가 내부 송금을 19배 느리게 만든다 (재현 완료)
             격벽 ✅          내부 p99 58,790 → 11,579ms (5배). 아직 SLO(5초)는 못 지킴
             스케줄러 풀 1 ⚠️ 조회 루프가 Outbox 릴레이를 굶겨 전부 멈췄던 것을 고침
             리스너 분리 ✅   내부 p99 2,587ms · 성공률 1.00 — 상대가 죽어도 우리 일은 멀쩡
             회로 차단기 ✅    5회 연속 실패 뒤 은행별 OPEN. 조회 GET은 계속 허용한다
             Resilience4j ✅   직접 만든 상태 머신·Semaphore를 표준 구현으로 교체 (D-002)
             재측정 준비 ✅    교체하며 잃은 거절 카운터를 되찾고, 확인 루프의 줄서기를 고침
             재측정 ✅         내부 p99 2,580ms · 1.00 (교체 전과 동일) · 5xx에 회로 OPEN →
                              차단 801건 전부 미전송 · 회복 뒤 320건 전부 해소 · 대사 0건
                              ⚠️ 2초 느린 상대로는 회로가 안 열린다 (실패가 아니라 성공이다)
             지표 분리 ✅      "보냈는데 모른다"(unknown)와 "못 보냈다"(unsent)를 갈랐다 —
                              하나로 두면 회로가 잘 돌 때마다 사고로 보인다
             대사 항목 ✅      UNKNOWN_EXTERNAL_CREDIT — 기계가 못 푸는 건을 사람에게 넘긴다
             D-003 ✅          느린 호출은 실패로 안 센다 — 기준은 read-timeout 하나로 둔다
             SLO 외부 항목 ✅  외부 지연은 목표로 걸지 않는다. 거는 것은 격리와 정합성이다
             홈서버 e2e ✅     정상 상대로 내부·외부 둘 다 p99 3초 · 성공률 1.00 · 대사 0건
             develop 머지 ✅   33커밋
출시          ✅ 2026-08-28 — PR #2로 Phase 5·6·6.5를 한 번에 냈다 (main `0cbb74f`).
             태그 셋을 각 Phase 끝점에 달았다: `phase-5-complete`(`6c10aec`) ·
             `phase-6-complete`(`6b278c2`) · `phase-6-5-complete`(`5c79d54`)
Phase 4       ✅ 완료 (2026-08-29) — Gateway. **PR을 항목마다 잘게 냈다**(5개)
             1/5 springdoc ✅  여섯 서비스가 자기 계약을 낸다. 공개/내부를 그룹으로 갈랐다
             2/5 Gateway 라우팅 ✅ :8080 하나로 들어온다. /internal은 안 나간다
             3/5 인증 필터 ✅ JWT 검증(발급 안 함). X-User-Id 위조를 지운다
             4/5 재측정 ✅      60 TPS에선 접수 p99 +24ms, 100 TPS에선 노이즈에 묻힌다.
                              종결은 +1ms — 게이트웨이는 문이지 길이 아니다
             🚩 별건: 100 TPS 종결 p99가 4,673 → 5,044ms로 SLO를 넘었다 → **원인 찾음**.
                발행이 끝난 Outbox 360만 건(1.6GB). 지우니 4,639ms로 복귀,
                보관 기간 배치를 넣었다(그 배치가 트랜잭션 없이 죽던 것도 고침)
             5/5 Rate Limiting ✅ 사용자당 10 TPS(용량의 10%)·burst 20. Redis가 죽으면
                              통과시킨다(fail-open) — 인증·설정이 fail-closed인 것과 기준이 다르다
             ⚠️ 4·5의 순서를 바꿨다 — **제한값을 정할 근거가 측정에서 나오기 때문**이다
             ⏭️ Config Server는 **Phase 8로 옮겼다** — 만들어보고 접었다.
                옮길 설정이 `management.*` 7줄뿐이라 대가가 값을 넘었다
Phase 6.6     ❌ 취소 (2026-08-28) — 한도 서비스는 만들지 않는다. 근거는 ROADMAP 맨 위 표
Phase 7~13    미착수 — **컨테이너화가 다음 차례**. 서비스 구성이 확정됐다(여덟 개)
로드맵 정리   ✅ 2026-08-29 — 표기를 `[x]` · `[ ]` · `⏭️`(조건 명시) · 인용문(기록)으로 갈랐다.
             완료된 Phase 안에 미체크가 하나도 없다

▶ 다음 작업 (2026-08-30에 정한 순서)
  1. 락 해제 실패 카운터        지금 신호를 버리고 있다. 어느 방향이든 먼저 필요
  2. SET NX PX → **Redisson**   watchdog으로 TTL 문제가 사라진다
                              + AccountService의 동시성 방어 축 분리 (구조 정리)
  3. 2차를 다시 판단            2번 뒤엔 질문이 "비관적으로 바꿀까"에서 "아직 필요한가"로 바뀐다
  4. @Scheduled ×10 → **ShedLock** + SKIP LOCKED (릴레이는 다르게)
                              + Outbox 인프라 두 벌 공통화 (구조 정리)
                              ⚠️ Phase 8의 전제. 컨테이너화 전에 끝나야 한다
  ─────
  그다음  Phase 7 컨테이너화

  방침: **무엇을 손댈지는 측정이, 무엇으로 할지는 현업 기술이 정한다** (`DECISIONS.md`)
  순서와 각 교체의 장단점은 `DECISIONS.md`의 "교체 예정 목록"에 표로 있다
```

## 지금의 숫자 (2026-08-26)

| | |
|---|---|
| **용량 (흩어진 계좌)** | **100 TPS** — SLO를 지키며 견디는 최대 도착률 (`docs/SLO.md`) |
| **용량 (핫 계좌)** | **70 TPS** (8조각·파티션 6·redo 1GB) — 쪼개기 전 25 TPS에서 **2.8배** |
| 무엇을 안 낮췄나 | **내구성** — `flush_log_at_trx_commit=1`·`sync_binlog=1` 그대로 |
| 다음 | **Phase 6.5 상대 은행** — 여기서 잰 용량은 그때 비교 대상이 아니게 된다 |

> **처리 속도와 용량은 다릅니다.** 밀린 큐를 비우는 속도는 100% 가동에서 나오는 값이고,
> 용량은 **약속한 지연 안에 서비스할 수 있는 속도**입니다. 개선은 항상 **용량**으로 말합니다.

**측정 환경**: 홈서버(집 안이면 `ssh home1`, 집 밖이면 `ssh home2`).
**노트북에서 잰 값은 성능 숫자로 쓰지 않습니다** (`HOMELAB.md`).
**빌드 상태**: 🟢 `./gradlew test` 통과 (테스트 532건)
**스키마**: 🟢 Flyway가 만듭니다 (`*/src/main/resources/db/migration`). `ddl-auto`는 `validate`뿐 —
**엔티티에 컬럼을 더하면 마이그레이션 파일도 함께 써야 기동됩니다** (원장은 MongoDB라 아직 예외).
**관측**: 🟢 `docker compose up -d prometheus grafana` → http://localhost:3000
(대시보드 `송금 시스템 — 개요`. **25개 쿼리 전부 값 확인 완료**)
**크로스 서비스 e2e**: 🟢 **밀려 있던 확인을 모두 마쳤습니다** (2026-08-22, 커밋 `54a0da2` 기준).
기존 5개 시나리오 회귀 + Step 6a·6b + Phase 3 알림까지 9건 전부 통과 —
아래 "밀린 e2e를 몰아서 확인했다" 참고.

> **출시했습니다** (2026-08-22). `release/phase-2-3` → `main` PR #1이 CI 3종
> (`unit`·`build`·`lint-workflows`)을 통과하고 머지됐습니다 (`2636dff`).
> `main`은 이제 `phase-1-complete`가 아니라 **Phase 3 + CI 위생 + Phase 5 Step 1**까지 담고 있습니다.
>
> | 태그 | 커밋 | 그 시점의 코드 |
> |---|---|---|
> | `phase-2-complete` | `edb9673` | notification-service **없음** |
> | `phase-3-complete` | `e924c9b` | notification-service **있음** |
>
> 릴리스가 왜 두 번이 아니라 한 번인지는 아래 "브랜치 히스토리"에 있습니다.
> Phase 2에서 e2e가 잡아낸 결함이 여럿이라(토픽 파티션, 상태 경합, 이중 출금, TINYTEXT)
> **테스트 통과만으로 출시하지 않는 규칙은 그대로입니다.**
**Phase 2 Step 0과 Phase 6.5 회로 차단기의 재현 테스트가 모두 green이 되어
`reproduction` 태그가 다시 0건입니다.**

| 재현 테스트 | 상태 |
|---|---|
| 동시 출금 (#1) | ✅ Step 2에서 해결 |
| 멱등성 (#2) | ✅ Step 1에서 해결 |
| 원장 기록 실패 (#3) | ✅ Step 4a에서 해결 (원장 기록 이벤트가 와야 COMPLETED) |
| 보상 재시도 (#4) | ✅ Step 4b에서 해결 (`TransferCompensationTest`로 전환) |

`reproductionTest` 태스크와 태그 분리 장치는 남겨뒀습니다. 다음 Phase에서 또 "먼저 재현하고 고치는"
방식을 쓸 때 그대로 쓰면 됩니다 (지금 돌리면 0건 실행 후 성공).

### 시스템 구성

| 서비스 | 포트 | 스택 | 저장소 |
|---|---|---|---|
| account-service | 8081 | Spring MVC + JPA | MySQL `account_db` |
| transfer-service | 8082 | Spring MVC + JPA | MySQL `transfer_db` |
| ledger-service | 8083 | Spring WebFlux | MongoDB `ledger_db` |
| reconciliation-service | 8084 | Spring MVC + JPA | MySQL `reconciliation_db` |
| notification-service | 8085 | Spring MVC + JPA | MySQL `notification_db` |
| gateway | 8080 | (Phase 4에서 구현) | — |
| config-server | 8888 | (Phase 4에서 구현) | — |

로컬 인프라(MySQL·MongoDB·Redis·Kafka)는 Docker로 띄웁니다: `docker compose -f docker-compose.dev.yml up -d`
서비스 자체의 컨테이너화는 Phase 7 예정 — 지금은 `./gradlew :{서비스}:bootRun`으로 직접 실행합니다.

---

## Phase 0 — 프로젝트 기반 설정 ✅

**목표**: 작업을 시작할 수 있는 뼈대 만들기

- Gradle 멀티모듈 구조로 전환 (5개 모듈)
- 도메인 모델 설계: `Account`, `Transfer`, `IdempotencyKey`, `Transaction`(원장)
- API 계약 문서 작성 (`docs/openapi.yaml`)
- 커밋 컨벤션 문서화

**커밋**: `472b32d`, `4964d2e`

---

## Phase 1 — 핵심 도메인 서비스 ✅

**목표**: 세 서비스가 실제로 동작해서 송금이 되게 만들기

### 한 일

- **Account Service**: 계좌 생성/조회/잔액조회 공개 API + Transfer 전용 내부 API(`/internal/accounts/{id}/debit|credit`)
- **Transfer Service**: `RestClient`로 Account를 동기 호출해 출금 → 입금 순차 처리
- **Ledger Service**: WebFlux + Reactive MongoDB, 커서 기반 페이지네이션으로 거래 내역 조회
- 낙관적 락(`@Version`) 충돌 시 재조회 후 최대 5회 재시도
- 로컬 개발용 `docker-compose.dev-db.yml` (MySQL + MongoDB만) — Step 2에서 Redis가 추가되며 `docker-compose.dev.yml`로 이름이 바뀝니다

### 의도적으로 남겨둔 것

Phase 1은 "일단 동작하게" 만드는 단계라, 아래는 **알면서 순진하게** 두었습니다. Phase 2에서 고칩니다.

- `Idempotency-Key` 헤더를 받기만 하고 사용하지 않음
- 보상(환불)을 요청 스레드 안에서 딱 한 번만 시도
- 원장 기록은 best-effort (실패해도 로그만)
- `TransferService`에 `@Transactional` 없음

### e2e 검증 결과

계좌 2개 생성 → A에 10,000 입금 → A→B 3,000 송금 → 잔액(7,000 / 3,000)·원장 2건·잔액부족 409 모두 정상 확인.

### 겪은 문제 — Spring Boot 4.1 이행 이슈

이 프로젝트는 Spring Boot 4.1 / Jackson 3 / Testcontainers 2.x 조합이라, 기존 Boot 3.x 기준 자료와 좌표·패키지가 다른 곳이 많았습니다. **Phase 2 이후에도 같은 종류의 함정이 나올 수 있어 기록해 둡니다.**

| 항목 | Boot 3.x | 이 프로젝트(4.1) |
|---|---|---|
| `TestRestTemplate` | `spring-boot-test` | 별도 모듈 `spring-boot-resttestclient`로 분리 |
| `@AutoConfigureMockMvc` | `...test.autoconfigure.web.servlet` | `spring-boot-webmvc-test` 모듈 / `...webmvc.test.autoconfigure` |
| `ObjectMapper` | `com.fasterxml.jackson.databind` | `tools.jackson.databind` (Jackson 3) |
| Mongo 설정 prefix | `spring.data.mongodb.*` | `spring.mongodb.*` |
| Mongo UUID | 자동 처리 | 기본값이 `UNSPECIFIED` → `representation.uuid: standard` 명시 필요 |
| 임베디드 Mongo | flapdoodle | BOM에서 사라짐 → Testcontainers 사용 |
| Testcontainers 좌표 | `org.testcontainers:mongodb` | `org.testcontainers:testcontainers-mongodb` |

추가로 MySQL 8의 `caching_sha2_password` 때문에 JDBC URL에 `allowPublicKeyRetrieval=true`가 필요했습니다.

**커밋**: `9a8afc3` · **태그**: `phase-1-complete`

---

## Phase 2 — 분산 환경 데이터 정합성 🔄

**목표**: Phase 1에서 일부러 순진하게 둔 부분들이 실제로 깨지는 걸 확인하고, 하나씩 제대로 된 패턴으로 고치기

### 진행 방식 (사용자와 확정)

- **Kafka를 Phase 2로 당겨옴** — Saga(Choreography)와 Outbox는 Kafka 없이는 반쪽이라 처음부터 제대로 구현. 대신 Phase 3는 토픽 확장·Notification 컨슈머 중심으로 축소됩니다.
- **재현 먼저** — 고치기 전에 실패하는 테스트로 문제를 드러냅니다.
- **한 단계마다 멈춤** — Step마다 구현→테스트→설명→확인 후 다음으로. Step별 독립 커밋.

### Step 진행 상황

| Step | 내용 | 상태 |
|---|---|---|
| 0 | 문제 재현 (실패하는 테스트) | ✅ `ac3b4ac` |
| 1 | 멱등성 처리 | ✅ `2172086` |
| 2 | Redis 분산 락 | ✅ `39d12f0` |
| 3 | Outbox 패턴 + Kafka 인프라 | ✅ `419bb88` |
| — | (곁가지) 기술 스택 정비 — Java 21, Actuator, CI | ✅ `e97e67d` |
| 4a | Choreography Saga 전환 — 정상 흐름 | ✅ `5214917` |
| 4b | 보상 트랜잭션 (실패 흐름) | ✅ |
| 4c | 컨슈머 재시도 + DLT | ✅ |
| 4d | 크로스 서비스 e2e에서 나온 결함 2건 수정 | ✅ |
| 5a | 모든 잔액 변경을 원장에 기록 (대사의 전제) | ✅ |
| 5b | 정합성 대사 배치 (`reconciliation-service`) | ✅ |

> Step 4b가 "보상 + 재시도/DLT"였는데, 보상만으로도 충분히 커서 **4b(보상) / 4c(재시도·DLT)**로 나눴습니다.

### 설계 결정

- **분산 락은 Account Service에 건다** (Transfer가 아니라). 잔액을 소유한 쪽이 계좌별 변경을 직렬화하는 게 맞고, Step 4에서 흐름이 이벤트 기반으로 바뀌어도 락 위치를 옮길 필요가 없습니다.
- **멱등성은 Transfer Service의 HTTP 진입점에 건다.** Phase 0에서 만들어두고 안 쓰던 `IdempotencyKey` 엔티티를 그대로 활용합니다.

---

### Step 0 — 문제 재현 ✅

**목표**: 패턴을 적용하기 전에, 현재 코드가 실제로 깨지는 지점을 실패하는 테스트로 먼저 드러내기

정상 동작을 **기대하는** 테스트를 작성했고, 현재 코드로는 4개 모두 실패합니다. 이후 Step에서 하나씩 green이 됩니다.

| # | 재현한 문제 | 실패 내용 | 해결 예정 |
|---|---|---|---|
| 1 | 동시 출금 시 정상 요청이 실패 | 잔액 10,000에 20스레드가 100씩 동시 출금 → 낙관적 락 재시도 5회 소진 → `ConcurrentUpdateException` 다발 | Step 2 |
| 2 | 동일 Idempotency-Key 재요청 → 이중 송금 | 송금 건수 `expected: 1L / but was: 2L` | Step 1 |
| 3 | 원장 기록 실패해도 송금은 COMPLETED | Ledger가 500을 반환해도 `LedgerClient`가 예외를 삼켜 상태가 `COMPLETED`로 남음 → 잔액과 원장이 어긋남 | Step 3~4 |
| 4 | 보상 환불 1회 실패 시 출금액 영구 소실 | 환불 재시도가 아예 없음 (`TooFewActualInvocations`) | Step 4 |

**추가한 테스트 파일**
- `account-service/.../service/AccountConcurrencyReproductionTest.java`
- `transfer-service/.../web/TransferIdempotencyReproductionTest.java`
- `transfer-service/.../service/TransferConsistencyReproductionTest.java`

**커밋**: `ac3b4ac`

---

### Step 1 — 멱등성 처리 ✅

**목표**: 같은 `Idempotency-Key`로 재요청해도 송금이 두 번 실행되지 않게 하기

#### 동작 방식

`POST /transfers` 진입점에서 키를 선점(IN_PROGRESS)하고, 처리가 끝나면 결과를 키에 기록합니다.
같은 키로 다시 오면 재처리하지 않고 저장된 결과를 그대로 돌려줍니다.

| 상황 | 응답 |
|---|---|
| 최초 요청 | 202 + 처리 결과 |
| 같은 키 + 같은 payload, 처리 완료 | 202 + **최초와 동일한 응답** (재처리 안 함) |
| 같은 키 + 같은 payload, 처리 중 | 409 `IDEMPOTENCY_KEY_IN_PROGRESS` |
| 같은 키 + **다른** payload | 422 `IDEMPOTENCY_KEY_REUSED` |
| 헤더 누락 | 400 `MISSING_HEADER` (기존엔 optional이었음) |

요청 해시는 JSON 원문이 아니라 **필드 값으로** 계산합니다. 공백·필드 순서가 달라도 같은 요청이면
같은 해시가 나오고, 금액은 `3000`과 `3000.00`을 같은 요청으로 봅니다.

#### 구현 중 만난 문제 3가지

**1) 수동 할당 PK라서 중복 INSERT가 조용한 UPDATE가 됨**
Spring Data JPA는 `@Id`가 이미 채워진 엔티티를 "기존 것"으로 보고 `merge()`를 호출합니다.
그러면 중복 키 저장이 예외 없이 UPDATE로 처리되어 멱등성 판정 자체가 무너집니다.
→ `IdempotencyKey`가 `Persistable<String>`을 구현해 신규 여부를 직접 알려주도록 해서
`persist()`(INSERT)가 실행되게 하고, 중복은 DB unique 제약 위반으로 드러나게 했습니다.

**2) `key`는 MySQL 예약어**
컬럼명을 `idempotency_key`로 변경했습니다.

**3) 실패 시 키가 삭제되어 이중 출금 위험** ← e2e에서 발견
처음엔 "송금 레코드를 만들지 못한 채 실패하면 키를 놓아준다"고 짰는데,
`executeTransfer`가 예외를 던지면 지역변수가 `null`로 남아 **출금 실패도 이 경로를 타서** 키가 삭제됐습니다.
출금이 타임아웃됐지만 실제로는 성공한 경우, 재시도가 그대로 이중 출금이 됩니다.
→ 송금 레코드를 먼저 만들어 참조를 확보한 뒤 Saga를 실행하도록 바꿔, 실패해도 키가 남게 했습니다.
회귀 테스트(`출금이_예외로_끝나도_키가_남아_재시도가_재실행되지_않는다`)로 고정했습니다.

#### 덤으로 고친 것 — 금액 표현 불일치

테스트가 잡아낸 버그입니다. 최초 응답은 요청받은 표현 그대로 `3000`,
재요청 응답은 DB를 거친 `3000.00`이라 **"재요청은 동일한 응답"이라는 멱등성 계약이 깨졌습니다.**
`Transfer`가 금액을 scale 2로 정규화하도록 하고, 컬럼이 담을 수 없는 정밀도는
DB가 조용히 잘라내기 전에 거절하도록 했습니다.

> ⚠️ `BigDecimal.equals()`는 scale까지 비교합니다. 이 변경으로 기존 테스트의
> `eq(BigDecimal.valueOf(1000))` 스텁이 `1000.00`과 매칭되지 않아 4건이 깨졌고, 스텁을 정규화 표현으로 맞췄습니다.

#### 검증

- 단위/통합 테스트 21건 중 19건 통과 (남은 2건은 Step 3~4 대상)
- 실제 MySQL 기동 후 e2e 확인: 재요청 시 응답 완전 동일·이중 출금 없음, 422/400 분기 정상,
  실패한 요청도 키가 `FAILED`로 남아 재요청 2회가 새 송금을 만들지 않음(총 건수 8 → 8 유지)

**커밋**: `2172086`

---

### Step 2 — Redis 분산 락 ✅

**목표**: 같은 계좌에 출금이 동시에 몰려도 정상 요청이 실패하지 않게 하기

#### 라이브러리 선택 — 토스 사례 조사

"토스 스펙으로 해달라"는 요청에 따라 [토스 기술블로그](https://toss.tech/article/slash23-corebanking)를 확인했습니다.
원문에서 확인된 것은 다음과 같습니다.

- **"Redis Global Lock과 더불어 DB Layer에서 동시성을 제어하기 위한 JPA의 `@Lock` 어노테이션을 통해 해결했어요"**
- **"계좌 단위 현재 잔액 데이터에 대해서만 고유하게 Row Locking이 걸리도록 개발"**
- "Lock을 잡아야 하는 데이터를 명확히 식별하고, 갱신하는 데이터에 대해서만 Lock을 획득해야
  데드락과 시스템 성능 저하를 예방할 수 있습니다"

즉 토스가 공개한 핵심은 **특정 라이브러리가 아니라 (1) Redis 락 + DB 레이어 락의 이중 방어,
(2) 락 범위를 계좌 단위로 최소화**입니다. 어떤 Redis 클라이언트를 쓰는지는 공개 자료에 없습니다.

> 첫 검색 결과 요약에는 "RedLock을 쓴다"는 내용이 있었지만, 원문을 직접 열어 확인하니
> **RedLock 언급은 없었습니다.** 검색 요약을 그대로 믿으면 안 되는 사례로 남겨둡니다.

그래서 **아키텍처는 토스를 따르고, 라이브러리는 직접 구현**하기로 했습니다.
Redisson(3.52.0)은 BOM 관리 대상이 아닌 데다, 넣어보니 Jackson 2가 Jackson 3와 공존하게 되고
서블릿 서비스에 Netty 전체가 딸려오며, Redisson이 빌드된 Netty 4.1을 Boot가 4.2.15로 올려버려
런타임 호환성 리스크까지 있었습니다.

#### 구현

`spring-boot-starter-data-redis`(Lettuce, BOM 관리)만으로 `DistributedLock`을 구현했습니다.

- **획득**: `SET key <내 토큰> NX PX <ttl>` — 키가 없을 때만 성공
- **해제**: 저장된 값이 **내 토큰일 때만** 삭제 (Lua 스크립트로 비교+삭제를 원자적으로)
  - 단순 `DEL`을 쓰면, 내 작업이 늦어져 TTL로 락이 풀린 뒤 다른 서버가 잡은 락을 지워버릴 수 있음
- **락 키**: `lock:account:{accountId}` — 변경하는 계좌 하나에만 (토스의 "락 범위 최소화")
- **이중 방어**: 낙관적 락(`@Version`) 재시도를 **그대로 유지**. 분산 락은 정상 경로 직렬화,
  낙관적 락은 락이 TTL로 풀리거나 Redis 장애로 우회된 경우를 잡는 최후 안전망
  - 토스는 DB 레이어를 비관적 락(`@Lock` row locking)으로 잡았고 우리는 낙관적 락이라는 차이가 있습니다.
    성능 비교는 Phase 10 부하 테스트에서 해볼 만한 소재입니다.

> ⚠️ 직접 구현이라 **자동 갱신(watchdog)이 없습니다.** TTL(3초)보다 오래 걸리는 작업을
> 이 락으로 감싸면 안 됩니다.

`docker-compose.dev-db.yml`에 Redis를 추가하면서, 더는 DB만 담지 않으므로
**`docker-compose.dev.yml`로 이름을 변경**했습니다.

#### 겪은 문제 — Testcontainers 컨테이너가 클래스마다 죽음

`@Testcontainers` + `@Container`를 공통 베이스 클래스에 두었더니, **테스트 클래스가 끝날 때마다
컨테이너가 멈춰서** 베이스를 상속한 두 번째 클래스부터 `Unable to connect to Redis`로 실패했습니다.
처음엔 통과하는 것처럼 보였는데, 알고 보니 docker-compose로 띄운 **로컬 Redis에 붙고 있었을 뿐**이었습니다.

→ static 블록에서 한 번만 start하는 **싱글턴 컨테이너 패턴** + `@DynamicPropertySource`로 변경.
ledger-service의 Mongo 베이스도 같은 잠재 버그가 있어(테스트 클래스가 하나뿐이라 안 드러났을 뿐) 함께 고쳤습니다.
루트 `AGENTS.md`에도 재발 방지용으로 적어두었습니다.

#### 검증

- account-service 테스트 20건 전부 통과 — 재현 테스트 #1(동시 출금)이 **green**으로 전환
- `DistributedLockTest` 5건으로 락 자체를 검증: 임계구역 상호배제, 정상/예외 종료 시 해제,
  대기 타임아웃, **남의 락은 지우지 않음**
- 실제 HTTP e2e: 잔액 10,000에 동시 출금 20건 × 100원 → **20건 전부 200**, 최종 잔액 정확히 8,000
  (Step 0 재현 시엔 `ConcurrentUpdateException` 다발이었음)
- 실패 경로(409) 포함해 Redis에 잔여 락 키 0건 확인

**커밋**: `39d12f0`

---

### Step 3 — Outbox 패턴 + Kafka 인프라 ✅

**목표**: "DB 상태 변경"과 "이벤트 발행"의 원자성 확보. **흐름은 아직 바꾸지 않고** 이벤트 발행 기반만 깔기

#### 왜 필요한가

DB와 Kafka는 서로 다른 시스템이라 하나의 트랜잭션으로 묶을 수 없습니다.

- 상태만 저장되고 발행이 실패하면 → **이벤트 유실**
- 발행만 되고 상태 저장이 롤백되면 → **있지도 않은 일이 알려짐**

그래서 발행하는 대신 **같은 트랜잭션 안에서 `outbox_events` 테이블에 INSERT**하고,
별도 릴레이가 그 테이블을 읽어 Kafka로 보냅니다.

#### 구현

| 구성요소 | 역할 |
|---|---|
| `OutboxEvent` | 이벤트 저장 테이블. `publishedAt`이 null이면 미발행 |
| `TransferOutboxRecorder` | **송금 상태 변경 + 이벤트 기록을 한 트랜잭션으로** 묶는 지점 |
| `OutboxRelay` | 0.5초마다 미발행 이벤트를 폴링해 Kafka로 발행 후 `publishedAt` 마킹 |

- 토픽: `transfer.requested` / `transfer.completed` / `transfer.failed`
- **파티션 키 = 애그리거트 ID(transferId)** — 같은 송금의 이벤트가 한 파티션에 모여 순서가 보장됩니다
- 발행 실패 시 마킹하지 않고 배치를 중단 → 다음 폴링에서 재시도 (순서 보존)
- Kafka 4.x는 **KRaft 전용**이라 Zookeeper 없이 단일 노드가 broker+controller를 겸합니다
- `acks: all` — 돈이 걸린 이벤트이므로 모든 ISR 응답을 기다립니다

> ⚠️ 이 구조는 **at-least-once**입니다. "발행은 성공했는데 마킹 직전에 죽는" 경우 같은 이벤트가
> 두 번 발행될 수 있습니다. 소비하는 쪽이 멱등해야 하며, Step 4에서 컨슈머를 만들 때 다룹니다.

#### 정리한 것 — `executeTransfer` 제거

테스트 전용으로 남겨뒀던 `executeTransfer`가 송금 생성 경로를 이중화하고 있어서,
Outbox를 붙이자 **한쪽 경로에만 이벤트가 기록되는 불일치**가 생겼습니다.
제거하고 테스트가 실제 진입점(`requestTransfer`)을 쓰도록 바꿨습니다.

#### 겪은 문제 — `@Lob`이 MySQL에서 TINYTEXT가 됨

`@Lob String payload`만 붙였더니 Hibernate가 기본 길이(255)를 보고 MySQL에 **TINYTEXT**로 만들었고,
이벤트 본문이 들어가지 못해 `Data too long for column 'payload'`로 송금이 500을 냈습니다.

**H2로 돌린 테스트는 전부 통과했고, MySQL e2e에서야 드러났습니다.** 길이를 `Length.LONG32`로 명시해
LONGTEXT로 잡았습니다. (테스트 DB와 운영 DB가 다를 때 생기는 전형적인 함정)

#### 검증

- transfer-service 테스트 23건 중 21건 통과 (남은 2건은 Step 4 대상)
- `OutboxRelayTest`: 기록된 이벤트가 결국 발행되고 `publishedAt`이 채워짐, 메시지 키가 transferId임을 확인
- e2e: 성공/실패 송금 각각 `transfer.requested`+`transfer.completed` / `+transfer.failed` 기록,
  전부 발행 완료(미발행 0), Kafka 콘솔 컨슈머로 실제 메시지 확인
- **브로커 장애 시나리오**: Kafka를 내린 상태에서도 송금은 202로 정상 처리되고 이벤트 2건이 미발행으로 남음
  → 브로커 복구 후 **릴레이가 자동으로 재발행**해 미발행 0으로 회복 (Outbox의 핵심 계약 확인)

**커밋**: `419bb88`

---

### 곁가지 — 기술 스택 정비 (Step 3 이후, Step 4 시작 전)

Step 4로 넘어가기 전에 잠시 멈추고, 참고 삼아 보던 f-lab-edu/limited-drop-commerce와
스택을 비교해 뒤처진 부분을 맞췄습니다.

#### 무엇을 왜 맞췄나

| 항목 | 이유 |
|---|---|
| **Java 17 → 21** | 21은 LTS이고, Phase 5 과부하 대응에서 가상 스레드를 실험하려면 필요합니다 |
| **Actuator (5개 서비스 전부)** | Phase 7 K8s의 liveness/readiness probe, Phase 9 Prometheus 스크래핑의 전제조건 |
| **micrometer-registry-prometheus** | 이게 없으면 `/actuator/prometheus` 엔드포인트가 아예 생기지 않습니다 |
| **GitHub Actions 빌드 워크플로** | 멀티모듈이라 로컬에서 한 모듈만 돌리다 다른 모듈이 깨진 걸 놓치기 쉽습니다 |

CI는 두 개의 job으로 나눴습니다. `build`(전체 빌드·테스트)와 `lint-workflows`(워크플로 파일 자체 검사).
후자는 actionlint 공식 이미지를 직접 씁니다 — 래퍼 액션을 거치지 않아 의존성이 하나 줄어듭니다.
워크플로 오타를 푸시하고 나서야 발견하는 일을 막아줍니다.

의도적으로 **안 맞춘 것**: Spring Security/JWT, springdoc(Swagger).
이 프로젝트의 목적은 분산 시스템 패턴 검증이라 인증·API 문서는 곁가지입니다.
인증은 Phase 4에서 Gateway 필터로 한 번에 다루는 편이 낫습니다.

#### JDK 21을 어떻게 확보했나

`settings.gradle`에 foojay 툴체인 리졸버를 넣어, JDK 21이 없는 환경에서는
Gradle이 알아서 받아오게 했습니다. 개발자마다 깔린 JDK가 달라도, CI에서도 같은 버전으로 빌드됩니다.

로컬에는 Corretto 21을 따로 설치하고 IntelliJ의 **프로젝트 SDK와 Gradle JVM도 21로** 맞췄습니다.
이걸 안 맞추면 IDE는 상위 JDK의 API 표면을 보여주면서 경고를 안 하는데,
정작 Gradle 빌드는 21로 컴파일해서 **IDE에서만 멀쩡하고 빌드에서 터지는** 상황이 생깁니다.
(Step 3의 `@Lob` 문제 — H2에선 통과하고 MySQL에서 터진 것 — 과 같은 종류의 함정)

#### 재현 테스트를 태그로 분리

CI를 붙이자마자 문제가 드러났습니다. Step 0의 재현 테스트 2건이 **일부러 red**로 남아 있어서
`./gradlew build`가 항상 실패하고, CI가 "빌드가 깨졌다"는 신호로서 무의미해집니다.

`@Tag("reproduction")`을 붙이고 기본 `test`에서 제외했습니다.
대신 `./gradlew reproductionTest`로 따로 돌려볼 수 있고, 이쪽은 실패해도 빌드를 세우지 않습니다.
**Step 4에서 green이 되면 태그를 떼면 됩니다.**

#### 검증

- 5개 모듈 전부 클린 컴파일, 생성된 바이트코드 major version 65 (= Java 21)
- IntelliJ가 실제로 Corretto 21로 Gradle 데몬을 기동하는 것까지 프로세스에서 확인
- `./gradlew build` — **BUILD SUCCESSFUL**, 49건 전부 통과
  (account 20 / transfer 21 / ledger 6 / gateway 1 / config-server 1)
- `./gradlew reproductionTest` — 2건 red 유지. 분리 후에도 재현 테스트가 살아 있음을 확인
- `actionlint` 로컬 실행 — 지적사항 0건 (문법·액션 참조·표현식·셸 스크립트)
- 다만 **Actions 실제 실행은 아직 확인 못 했습니다.** 푸시 후 확인 필요

---

### Step 4a — Choreography Saga 전환 (정상 흐름) ✅

**목표**: 요청 스레드 안에서 다른 서비스를 순서대로 호출하던 흐름을, 이벤트로 이어지는 흐름으로 바꾸기.
Step 4는 크기가 커서 **4a(정상 흐름) / 4b(실패·보상)**로 나눴습니다.

#### 무엇이 바뀌었나

전에는 `TransferService`가 요청 스레드 안에서 출금 → 입금 → 원장기록을 차례로 **호출**했습니다.
이제는 이벤트 하나만 남기고 202로 돌아옵니다.

```
POST /transfers ─▶ Transfer  transfer.requested       (여기서 응답. 상태 PENDING)
                   Account   출금 ─▶ transfer.debited
                   Account   입금 ─▶ transfer.credited
                   Ledger    원장 기록 ─▶ transfer.ledger-recorded
                   Transfer  상태 갱신 ─▶ COMPLETED (+ transfer.completed)
```

- **얻은 것**: 요청 스레드가 다른 서비스의 응답 시간에 묶이지 않고, 중간에 한 서비스가 죽어도
  이벤트가 브로커에 남아 되살아나면 이어집니다.
- **잃은 것**: 응답을 받은 시점에 송금이 끝난 게 아닙니다. 클라이언트는 조회해야 합니다.
  그리고 **흐름 전체를 한눈에 볼 수 있는 코드가 없어졌습니다** — 오케스트레이션을 버린 대가입니다.
  그래서 흐름 그림을 `AGENTS.md`와 `TransferService` 클래스 주석에 남겨뒀습니다.

`AccountClient`/`LedgerClient`와 그에 딸린 DTO·설정·예외(잔액부족 등 5개)는 역할이 사라져 삭제했습니다.
계좌 오류는 이제 HTTP 응답이 아니라 송금의 최종 상태로 드러납니다.

#### 원장 기록까지 끝나야 COMPLETED

Step 0 재현 테스트 #3이 지적한 문제입니다. 입금 시점에 완료로 찍으면, 원장 기록이 실패했을 때
**"송금은 성공인데 원장에는 없는"** 상태가 남습니다. 그래서 완료 판정을 원장 기록 이벤트까지 미뤘습니다.
`CREDIT_COMPLETED` → (`transfer.ledger-recorded`) → `COMPLETED`.

#### 컨슈머 멱등성 — 서비스마다 방법이 다르다

Outbox 릴레이는 at-least-once입니다(발행 성공 직후 마킹 전에 죽으면 재발행).
같은 이벤트가 두 번 와도 결과가 같아야 하는데, **작업의 성격에 따라 방법이 다릅니다.**

| 서비스 | 방법 | 이유 |
|---|---|---|
| Account | `processed_events` 테이블에 처리 흔적 (잔액 변경과 같은 트랜잭션) | 잔액 변경은 되돌릴 수 없어 "했는지"를 따로 기록해야 함 |
| Transfer | 상태 전이에 "기대한 이전 단계일 때만" 조건 | 상태 머신이라 지나간 단계는 자연스럽게 무시됨 |
| Ledger | 문서 `_id`를 자연키(송금+계좌+방향)로 | 같은 _id에 덮어쓰기가 되므로 줄이 늘지 않음 |

Account의 흔적 기록은 **조회 후 INSERT가 아니라 INSERT 먼저**입니다. 조회로 판단하면 두 스레드가
동시에 "없다"를 보고 둘 다 처리할 수 있어서, PK unique 제약에 맡깁니다.
그리고 `ProcessedEvent`에 `Persistable`을 구현했습니다 — Step 1의 `IdempotencyKey`와 같은 함정으로,
PK를 직접 지정하면 Spring Data가 INSERT 대신 merge(UPDATE)를 해서 중복이 **조용히 통과**합니다.

#### Ledger에는 Outbox를 두지 않았다

Outbox는 "DB 커밋"과 "이벤트 발행"을 원자적으로 묶는 장치인데, Ledger는 그 둘이 어긋나도 스스로 복구됩니다.
기록은 됐는데 발행이 실패하면 오프셋이 커밋되지 않아 재전송되고, **기록이 멱등하므로** 다시 기록해도 그대로인 채
발행만 다시 됩니다. 즉 *멱등한 쓰기 + 발행 후 ack* 조합이면 같은 보장을 얻습니다.
(게다가 MongoDB는 단일 노드에서 다중 문서 트랜잭션을 못 써서, Outbox를 둬도 원자적이지 않습니다.)

#### 그 외 결정

- **잔액 변경 경로는 하나로 모았다.** REST 진입점과 Kafka 컨슈머가 같은 계좌를 동시에 건드릴 수 있으므로,
  둘 다 `AccountService.guarded()`(분산 락 + 낙관적 락 재시도)를 지나게 했습니다. 방어가 한쪽에만 있으면 없는 것과 같습니다.
- **토픽을 `NewTopic` 빈으로 명시 생성.** 브로커 자동 생성에 맡기면 파티션이 1개로 고정되고,
  나중에 늘리면 키 분배가 달라져 순서 보장이 깨집니다. 파티션 3개 / 키는 송금 ID.
- **이벤트 본문에 `@JsonIgnoreProperties(ignoreUnknown = true)`.** 발행하는 쪽이 필드를 추가해도
  소비하는 쪽이 깨지지 않아야 각자 배포할 수 있습니다.
- **이벤트에 다음 단계가 필요한 값을 다 실어 보낸다**(변경 후 잔액 등). 되묻느라 동기 호출을 하면 바꾼 의미가 없습니다.

#### 검증

`./gradlew test` — **357건 전부 통과**. 새로 쓴 테스트는 다음과 같습니다.

| 테스트 | 확인하는 것 |
|---|---|
| `TransferSagaServiceTest` (account, 4건) | 출금/입금 + 다음 이벤트 기록, 중복 이벤트, 잔액 부족 시 아무것도 안 남김 |
| `TransferEventConsumerTest` (account) | 실제 Kafka JSON → 출금까지 도달하는 배선 |
| `TransferSagaConsumerTest` (transfer, 2건) | 세 이벤트를 순서대로 받아 마지막에야 COMPLETED / 재전송에도 안 되돌아감 |
| `TransferServiceTest` (transfer, 6건) | 접수만 하고 반환, 상태 전이 규칙 |
| `TransferAcceptanceFailureTest` (transfer) | 접수 실패 시 키가 남아 재시도가 409로 막힘 |
| `TransferCreditedConsumerTest` (ledger, 3건) | 두 줄 기록, 중복 수신에도 두 줄, ledger-recorded 발행 |

**테스트가 진짜로 잡는지도 확인했습니다** (`AGENTS.md`의 "검증 방법" 규칙).
세 가지 방어 장치를 하나씩 되돌려 red가 되는 걸 봤습니다.

| 되돌린 것 | 빨개진 테스트 |
|---|---|
| Account의 `processed_events` INSERT 제거 | `같은_접수_이벤트를_두_번_받아도_출금은_한_번만_된다` |
| Ledger의 결정적 _id → 랜덤 UUID | `같은_이벤트를_두_번_받아도_원장은_두_줄뿐이다` |
| 입금 시점에 `markCompleted()` 호출 | `입금까지만_끝난_송금은_COMPLETED가_아니다` 외 2건 |

`./gradlew reproductionTest` — 1건 red 유지 (`입금이_실패하면_출금이_보상되어야_한다`).
Step 4b 대상입니다.

#### 남은 것 (Step 4b)

- 입금 실패 시 출금 보상 (`transfer.credit-failed` → 환불 → `transfer.failed`)
- 지금은 잔액 부족 등으로 단계가 실패하면 **송금이 PENDING인 채로 멈춥니다** (로그만 남김)
- 컨슈머 재시도 정책과 DLT — 지금은 spring-kafka 기본 동작에 맡기고 있습니다

---

### Step 4b — 보상 트랜잭션 ✅

**목표**: 단계가 실패했을 때 송금이 PENDING인 채로 멈추지 않게 하고, **이미 나간 출금을 되돌리기**

#### 무엇이 문제였나

Step 4a는 정상 흐름만 옮겼습니다. 출금은 성공했는데 입금이 실패하면 이렇게 됩니다.

- 출금 계좌에서는 돈이 빠졌고, 입금 계좌에는 들어가지 않았고,
- 송금은 PENDING인 채로 남고, 아무도 되돌리지 않습니다.

`TransferSagaService`에 `TODO(Step 4b)`와 함께 로그만 찍고 있던 자리입니다.

#### 흐름

실패 지점에 따라 되돌릴 게 있고 없고가 갈립니다.

```
출금 실패 ─▶ transfer.debit-failed   ─▶ Transfer  FAILED           (돈이 안 움직였으므로 종결만)

입금 실패 ─▶ transfer.credit-failed  ─▶ Transfer  COMPENSATING     (아직 종결 아님)
                                     ─▶ Account   환불
                                     ─▶ transfer.debit-reversed
                                     ─▶ Transfer  FAILED (+ transfer.failed)
```

#### 결정 1 — 환불을 요청 스레드에서 하지 않고 이벤트로 한 바퀴 돌린다

입금 실패는 Account Service 안에서 감지되고, 되돌릴 계좌도 같은 서비스에 있습니다.
그 자리에서 바로 환불하면 코드가 훨씬 짧습니다. 그런데도 `transfer.credit-failed`를 발행하고
**자기가 다시 구독**하게 했습니다.

이유는 **재시도**입니다. 그 자리에서 환불하다 실패하면(예: 낙관적 락 소진, Redis 장애) 아무도 다시
해주지 않습니다 — Step 0 재현 테스트 #4가 지적한 게 정확히 이거였습니다("환불 재시도가 아예 없음").
브로커에 남겨두면 실패해도 오프셋이 커밋되지 않아 다시 배달됩니다.

즉 **한 서비스 안의 일이라도, 재시도가 필요하면 브로커를 거치는 게 값이 싸다**는 판단입니다.

#### 결정 2 — 전진 단계와 보상 단계는 실패 시 처신이 다르다

| 단계 | 업무적 실패 시 |
|---|---|
| 전진 (출금·입금) | 실패 이벤트를 남기고 물러난다. 다시 시도해도 결과가 같으므로 재시도는 무의미 |
| 보상 (환불) | **예외를 그대로 던진다.** 재배달 → 끝내 안 되면 DLT |

보상의 보상은 없습니다. 여기서 예외를 삼키면 고객 돈이 사라진 채로 조용히 끝납니다.
사람이 봐야 하는 사건이므로 밖으로 내보냅니다.

#### 결정 3 — 실패도 "처리했다"로 기록해야 한다

`SagaStepExecutor.execute`가 예외로 끝나면 처리 흔적(`processed_events`)도 함께 롤백됩니다.
그래서 실패 이벤트를 남기는 `recordFailure`를 **별도 트랜잭션**으로 두고, 거기서 처리 흔적을 다시 남깁니다.
이게 없으면 재전송이 올 때마다 실패 이벤트가 새로 발행되어, Transfer가 같은 실패를 반복해서 듣습니다.

#### 겪은 문제 — 실패 이벤트는 도착 순서가 보장되지 않는다

Step 4a의 상태 전이 규칙은 "기대한 직전 단계일 때만"이었습니다. 실패 흐름에 그대로 쓰려다 보니
구멍이 보였습니다.

`transfer.debited`와 `transfer.credit-failed`는 **서로 다른 토픽**입니다. 파티션 키가 같아도
토픽이 다르면 순서가 보장되지 않습니다. credit-failed가 debited보다 먼저 도착하면 송금은 아직
PENDING인데 보상 이벤트가 오는 셈이고, 한 점만 허용하면 그 이벤트를 **조용히 버려서 송금이 영원히
PENDING에 갇힙니다.**

→ 실패 계열 전이는 **허용 상태를 집합으로** 잡았습니다. 대신 종결 상태(COMPLETED/FAILED)는 어디에도
넣지 않아, 한 번 닫힌 송금은 뒤늦은 이벤트로 다시 열리지 않습니다.

> 정상 흐름에도 같은 종류의 순서 문제가 잠재해 있습니다(debited/credited/ledger-recorded가 각각 다른 토픽).
> 지금은 앞 단계가 늦게 도착하면 그 이벤트를 버리고 **뒤 단계에서 이어집니다** —
> 상태가 뒤로 가지는 않지만, 중간 상태 하나를 건너뛸 수 있습니다. 실패 흐름만큼 위험하지 않아
> 이번에는 손대지 않았습니다.

#### 검증

`./gradlew test` — **371건 전부 통과**. `reproduction` 태그는 이제 하나도 남지 않았습니다.

| 테스트 | 확인하는 것 |
|---|---|
| `TransferSagaServiceTest` (account, +5건) | 출금/입금 실패 시 실패 이벤트, 환불, 중복 환불 방지, 실패 이벤트 중복 방지, 보상 실패 시 예외 전파 |
| `TransferCompensationTest` (account) | **진짜 Kafka + 릴레이로 보상 고리가 스스로 닫히는지** (재현 테스트 #4를 전환) |
| `TransferServiceTest` (transfer, +6건) | 실패 상태 전이, 순서 뒤바뀜 허용, 종결된 송금은 뒤집히지 않음 |
| `TransferSagaConsumerTest` (transfer, +2건) | 실패 계열 토픽 세 개의 리스너 배선 |

재현 테스트 #4는 `TransferCompensationTest`로 바꿔 다시 썼습니다. 원래는 `onDebited`를 직접 부르고
그 자리에서 잔액이 복구되기를 기대했는데, 보상을 이벤트로 돌리기로 하면서 **진짜 Kafka와 릴레이를 거쳐**
`requested → debited → credit-failed → debit-reversed`가 스스로 이어지는지를 보도록 했습니다.
보상은 세 번의 배달을 거치므로, 단계별 테스트로는 배선이 끊긴 걸 못 잡습니다.

**테스트가 진짜로 잡는지도 확인했습니다** (`AGENTS.md`의 "검증 방법" 규칙). 넷을 되돌려 red를 봤습니다.

| 되돌린 것 | 빨개진 테스트 |
|---|---|
| 실패 이벤트 발행을 로그로 되돌림 (4a 상태) | 4건 (`TransferCompensationTest` 포함) |
| 보상 실패 시 예외를 삼키게 함 | `보상_자체가_실패하면_삼키지_않고_밖으로_던진다` |
| `recordFailure`의 처리 흔적 INSERT 제거 | `같은_이벤트로_두_번_실패해도_실패_이벤트는_한_번만_나간다` |
| 실패 전이 허용 상태를 한 점으로 좁힘 | `출금_이벤트보다_보상_이벤트가_먼저_와도_갇히지_않는다` |

세 번째 되돌리기는 **테스트가 없어서 먼저 추가한 것**입니다. 구현할 때는 당연하게 넣은 흔적 기록인데,
그걸 지워도 아무 테스트도 빨개지지 않는다는 걸 보고 나서야 빠진 걸 알았습니다.

---

### Step 4c — 컨슈머 재시도 + DLT ✅

**목표**: 처리하지 못한 이벤트가 **조용히 사라지지 않게** 하기

#### 기본값이 위험했다

여태 컨슈머 실패는 spring-kafka 기본 동작에 맡기고 있었습니다. 그 기본값이 무엇인지 확인해보니
**지연 없이 10번 재시도하고, 로그만 남긴 뒤 오프셋을 커밋**합니다. 즉 메시지가 없어집니다.

두 가지가 문제입니다.

- 잠깐 DB가 끊긴 것뿐이어도 **1초 안에 10번을 몰아 시도**하고 포기합니다. 회복될 틈이 없습니다.
- 포기한 뒤 그 메시지가 **어디에도 남지 않습니다.** 돈이 걸린 이벤트라 유실이 곧 사고입니다.

#### 바꾼 것

세 서비스에 각각 `KafkaErrorHandlingConfig`를 두었습니다 (공유 모듈이 없으므로 이벤트 계약과 같은
규칙 — **바꿀 때는 세 곳을 함께**).

| 항목 | 값 | 이유 |
|---|---|---|
| 백오프 | 1초 → 2초 → 4초 (최대 3회 재시도) | 일시적 장애가 회복될 시간을 준다 |
| 실패 후 | `<원래 토픽>.DLT`로 발행 | 사라지지 않아야 사람이 찾아낼 수 있다 |
| 즉시 DLT | 결과가 달라질 리 없는 실패 | 백오프를 낭비하는 동안 같은 파티션의 뒤 메시지가 발이 묶인다 |

"즉시 DLT" 대상은 서비스마다 다릅니다.

- **Account**: 계좌 관련 업무 예외 4종 + JSON 파싱 실패.
  이 예외들이 리스너 밖까지 올라왔다는 건 **보상 단계가 실패했다**는 뜻입니다
  (전진 단계의 업무적 실패는 Step 4b에서 실패 이벤트로 바뀝니다). 사람이 봐야 하는 사건입니다.
- **Transfer**: `TransferNotFoundException` + JSON 파싱 실패.
- **Ledger**: JSON 파싱 실패만. Mongo 장애는 일시적일 수 있으므로 재시도합니다.

재시도가 의미 있으려면 **컨슈머가 멱등해야 합니다.** Step 4a·4b에서 서비스별로 멱등성을
확보해둔 덕분에 같은 이벤트를 몇 번 다시 처리해도 결과가 같습니다.

#### 겪은 문제 — DLT 파티션 번호

`DeadLetterPublishingRecoverer`의 기본 동작은 **원래 메시지와 같은 번호의 파티션**으로 보내는 것입니다.
우리 토픽은 파티션이 3개인데 DLT는 자동 생성되며 1개짜리로 만들어지므로, 2번 파티션으로 가야 할
메시지는 갈 곳이 없어 **DLT 발행 자체가 실패**합니다. 마지막 안전망이 조용히 무너지는 셈입니다.

→ 목적지 resolver에서 파티션을 `-1`로 넘겨 프로듀서가 고르게 했습니다.

#### 검증

`./gradlew test` — **375건 전부 통과**.

| 테스트 | 확인하는 것 |
|---|---|
| `KafkaErrorHandlingTest` (account, 2건) | 일시적 실패는 재시도되어 결국 처리됨 / 보상 실패는 **재시도 없이** DLT행 |
| `KafkaErrorHandlingTest` (transfer) | 읽을 수 없는 메시지가 DLT에 남음 |
| `KafkaErrorHandlingTest` (ledger) | 같음 |

account의 테스트만 컨슈머 그룹을 따로 씁니다. 다른 테스트 컨텍스트와 그룹을 공유하면 메시지가
**다른 컨텍스트의 리스너**에게 갈 수 있어, "이 컨텍스트에서 몇 번 불렸나"를 셀 수 없습니다.

**테스트가 진짜로 잡는지 확인한 결과** (`AGENTS.md`의 "검증 방법" 규칙):

| 되돌린 것 | 결과 |
|---|---|
| `KafkaErrorHandlingConfig` 3개를 통째로 제거 | 세 서비스의 DLT 테스트가 모두 red |
| account의 `addNotRetryableExceptions` 제거 | `TooManyActualInvocations` — 4번 호출됨(1번이어야 함) |

**다만 재시도 테스트(`일시적으로_실패한_이벤트는_재시도되어_결국_처리된다`)는 설정을 제거해도 green입니다.**
기본값도 재시도는 하기 때문입니다. 이 테스트가 지키는 건 "우리 설정이 재시도를 없애지 않았다"까지이고,
백오프 간격 자체는 테스트로 고정하지 않았습니다 — 시간에 의존하는 테스트는 CI에서 흔들립니다.

#### 남은 것

- DLT에 쌓인 메시지를 **다시 흘려보내는 도구**가 없습니다. 지금은 사람이 콘솔 컨슈머로 들여다봐야 합니다.
- DLT 적재를 알리는 알람도 없습니다 (Phase 9 관측성에서 다룰 소재).

---

### Step 4 이후 — 크로스 서비스 e2e에서 결함 2건 발견 🔴

**세 서비스를 실제로 띄워 돌려봤습니다.** 그전까지 테스트 375건은 전부 통과했지만,
각 테스트는 진짜 Kafka를 쓰면서도 **자기 모듈 안에서만** 돌았습니다.
서비스를 함께 띄우자 두 가지가 드러났습니다.

| 시나리오 | 돈 | 송금 상태 | 판정 |
|---|---|---|---|
| A 정상 송금 | ✅ 7000 / 3000 | 🔴 `DEBIT_COMPLETED`에서 **영구 정지** | 실패 |
| B 입금 실패 → 보상 | ✅ 5000으로 복구 | 🔴 `DEBIT_COMPLETED` (실제로는 FAILED여야) | 실패 |
| C 출금 실패 | ✅ 100 그대로 | ✅ `FAILED` | 통과 |

**잔액은 세 시나리오 모두 정확했습니다.** 보상도 실제로 동작해 B의 출금이 되돌아왔습니다.
깨진 건 전부 Transfer Service의 **상태 추적** 쪽입니다.

#### 결함 1 — 컨슈머가 토픽을 1파티션으로 자동 생성해버린다

`transfer.debited` 등은 account-service가 `NewTopic` 빈으로 3파티션으로 만듭니다. 그런데
transfer-service의 컨슈머가 **토픽이 만들어지기 전에 먼저 구독**하면, 브로커가 기본값(1파티션)으로
자동 생성합니다. 그 뒤 account의 `KafkaAdmin`이 3으로 늘리지만, 이미 붙은 컨슈머는
**늘어난 파티션을 모릅니다.**

```
transfer-service: partitions assigned: [transfer.debited-0]     ← p0만
account-service:  transfer.debited p0, p1, p2                   ← 전부
```

메시지는 키 해시로 p1·p2에 떨어졌고, transfer-service는 그걸 5분(`metadata.max.age.ms` 기본값)
동안 못 봤습니다. **유실은 아닙니다** — 정확히 5분 뒤 메타데이터가 갱신되며 밀린 걸 처리했습니다.
다만 콜드 스타트마다 Saga 전체가 5분 멈춥니다. Phase 6·7에서 컨테이너·K8s로 매번 새 환경을
띄우게 되면 상시 겪을 문제입니다.

#### 결함 2 — 송금 상태 전이에 동시성 제어가 없다 (더 심각)

Saga 단계마다 토픽이 다르고, 토픽마다 리스너 스레드가 다릅니다. 다섯 개의 리스너가
**같은 송금 행을 동시에 read-modify-write** 할 수 있는데, `Transfer`에는 `@Version`이 없습니다.

결함 1 때문에 여섯 개 토픽이 5분치를 한꺼번에 쏟아내며 이 경합이 실제로 터졌습니다.

```
스레드1 (debited)        : PENDING 읽음 → DEBIT_COMPLETED 쓰기
스레드2 (debit-reversed) : PENDING 읽음 → markFailed() → transfer.failed 발행
                           → 마지막에 커밋된 스레드1이 덮어씀
```

증거는 DB에 남았습니다. B의 `transfer.failed` **이벤트는 발행됐는데**(03:29:51) 정작
송금 행은 `DEBIT_COMPLETED`입니다. **바깥에는 실패라고 알려놓고 자기 기록은 진행 중**인 상태 —
정지보다 나쁩니다.

#### 결함 2b — 순서가 뒤바뀌면 정상 흐름이 영구 정지한다

Step 4b에서 이렇게 적었습니다.

> 정상 흐름에도 같은 종류의 순서 문제가 잠재해 있습니다 (…) 상태가 뒤로 가지는 않지만,
> 중간 상태 하나를 건너뛸 수 있습니다. 실패 흐름만큼 위험하지 않아 이번에는 손대지 않았습니다.

**과소평가였습니다.** A에서 `credited`와 `ledger-recorded`가 아직 PENDING일 때 도착해
"기대한 직전 단계가 아니다"로 버려졌고, 그 뒤로는 올 이벤트가 없어 `DEBIT_COMPLETED`에
**영원히** 멈췄습니다. 건너뛰는 게 아니라 정지입니다.

#### 왜 테스트로 못 잡았나

- 각 서비스의 통합 테스트는 자기 모듈 리스너만 띄웁니다. **토픽을 만드는 쪽과 소비하는 쪽이
  다른 프로세스인 상황**이 재현되지 않습니다.
- Testcontainers Kafka는 테스트마다 깨끗하고, 토픽 생성 순서도 한 프로세스 안에서 결정됩니다.
- 상태 전이 경합은 이벤트가 초 단위로 떨어져 도착하는 정상 상황에서는 거의 안 터집니다.
  결함 1이 5분치를 한꺼번에 쏟아붓는 조건을 만들어줘서 드러났습니다.

`AGENTS.md`의 "수동 실행은 보조 수단"에 사례가 두 건 적혀 있었는데, 여기에 세 번째가 붙습니다.

#### 고칠 방향 (Step 4d)

1. **브로커 자동 생성을 끄고**, 토픽은 발행하는 서비스가 소유하게 한다.
   컨슈머가 먼저 떠도 토픽을 잘못 만들지 않고, 생길 때까지 기다리다 3파티션을 전부 잡는다.
2. **`Transfer`에 `@Version`** + 충돌 시 재조회 후 전이 조건을 다시 평가한다 (Account와 같은 방식).
3. **전진 전이를 단조(monotonic)로** 바꾼다. "직전 단계일 때만"이 아니라 "더 앞선 단계면 적용".
   그러면 순서가 뒤바뀌어도 갇히지 않는다.

---

### Step 4d — e2e에서 나온 결함 수정 ✅

**목표**: 바로 앞에서 찾은 결함 2건을 고치고, 같은 게 다시 들어오면 e2e까지 가지 않고 잡히게 하기

#### 재현부터 했다

고치기 전에 실패하는 테스트를 먼저 썼습니다 (`reproduction` 태그, 커밋 `3a6374d`).
9건 중 8건이 red로 시작했습니다.

경합 재현이 까다로웠습니다. 스레드 두 개를 그냥 돌리면 타이밍에 따라 통과했다 실패했다 하므로,
**출금 이벤트를 처리하는 스레드를 "읽은 뒤 · 커밋하기 전"에 붙잡아두고** 그 사이 환불 완료가
읽고 쓰고 커밋하게 해서 순서를 강제했습니다. 그러자 e2e와 **똑같은 증상**이 나왔습니다 —
`expected: FAILED but was: DEBIT_COMPLETED`.

> 붙잡는 지점으로 `save`를 골랐습니다. 조회를 가로채려면 Mockito의 `callRealMethod()`가 필요한데
> Spring Data 리포지토리는 인터페이스라 쓸 수 없습니다("Cannot call abstract real method").
> 반면 `save`는 실제로 부르지 않아도 됩니다 — 이미 영속 상태라 커밋 시점의 변경 감지로 UPDATE가 나갑니다.

#### 고친 것 1 — 소비하는 토픽도 각자 선언한다

전에는 "토픽을 만드는 건 발행하는 서비스 몫"이라 보고 발행 토픽만 선언했습니다.
그런데 컨슈머가 토픽이 생기기 전에 구독하면 **브로커가 1파티션으로 자동 생성**해버립니다.

이제 세 서비스가 각각 **발행 + 소비 + DLT** 토픽을 전부 선언합니다.
`KafkaAdmin`은 이미 있는 토픽을 다시 만들지 않고 파티션이 모자랄 때만 늘리므로, 중복 선언은 안전합니다.
다만 **양쪽 선언이 어긋나면 큰 쪽이 이긴다**는 뜻이라, 파티션 수를 바꿀 때는 그 토픽을 쓰는
모든 서비스를 함께 봐야 합니다 (이벤트 계약과 같은 규칙).

#### 고친 것 2 — 상태를 "단조 진행"으로 바꿨다

`TransferStatus`에 진행도를 매기고, **진행도가 앞서면 건너뛰어서라도 적용**합니다.

```
PENDING(0) ─▶ DEBIT_COMPLETED(1) ─▶ CREDIT_COMPLETED(2) ─▶ COMPLETED(3)
COMPENSATING(-1) · FAILED(-1)   ← 정상 흐름 위에 없음
```

뒤 단계 이벤트가 도착했다는 건 앞 단계가 이미 끝났다는 뜻이므로, 건너뛰어도 사실과 어긋나지 않습니다.
반대로 지나간 단계는 진행도가 뒤라 자연스럽게 무시되고, 종결 상태는 무엇이 와도 바뀌지 않습니다 —
전에 쓰던 "허용 상태 집합"보다 규칙이 하나로 줄었습니다.

#### 고친 것 3 — `Transfer`에 `@Version`

리스너 스레드끼리 같은 행을 덮어쓰지 못하게 낙관적 락을 걸었습니다. 충돌하면 **다시 읽어 전이 조건을
처음부터 다시 판단**합니다 — 이미 종결됐으면 건너뛰고, 아직이면 이어서 진행합니다.

재시도는 트랜잭션 **밖**에서 해야 하므로 전이를 `TransferStateUpdater`로 분리했습니다.
같은 트랜잭션 안에서 다시 읽으면 그 사이 다른 쪽이 커밋한 값을 볼 수 없습니다.
(Account가 `AccountService.guarded` + `SagaStepExecutor`로 나눈 것과 같은 구조입니다.)

끝내 못 잡으면 예외를 삼키지 않고 내보냅니다. 컨슈머가 재시도하고, 그래도 안 되면 DLT로 갑니다(Step 4c).

#### 검증

`./gradlew test` — **386건 전부 통과**. 되돌려 red를 확인했습니다.

| 되돌린 것 | 빨개진 테스트 |
|---|---|
| 소비 토픽 선언 제거 | `KafkaTopicPartitionTest` 6건 |
| 단조 진행 → "직전 단계일 때만" | `SagaOrderingTest`, `TransferStateUpdaterTest`의 건너뛰기 |
| `@Version` 제거 | `TransferConcurrencyTest` (e2e와 같은 증상으로 red) |

**그리고 e2e를 다시 돌렸습니다.** 문제가 났던 조건을 그대로 만들려고 **Kafka 토픽을 전부 지우고**
콜드 스타트로 띄웠습니다.

```
transfer-service: partitions assigned: [transfer.debited-0, transfer.debited-1, transfer.debited-2]
```

처음부터 세 파티션을 잡습니다(전에는 `-0`만). 5분을 기다릴 필요 없이 곧바로 처리됐습니다.

| 시나리오 | 상태 | 잔액 | 원장 |
|---|---|---|---|
| A 정상 송금 | ✅ COMPLETED | 7000 / 3000 | 2줄 (출금·입금) |
| B 입금 실패 → 보상 | ✅ FAILED (사유 기록됨) | 5000 복구 | 0줄 |
| C 출금 실패 | ✅ FAILED | 100 그대로 | 0줄 |

DLT는 전부 0건입니다. 앞선 e2e에서 A·B가 실패했던 게 모두 해소됐습니다.

> 참고로 e2e 스크립트 자체에도 버그가 있었습니다 — 원장 응답의 필드가 `items`인데 `transactions`를
> 읽어 계속 0줄로 나왔습니다. **확인 도구가 틀리면 멀쩡한 것도 고장으로 보인다**는 걸 다시 확인했습니다.

---

### e2e 보강 — 실패 경로 두 가지 ✅

Step 4d 뒤에도 **밟아보지 않은 경로**로 남겨뒀던 둘을 확인했습니다. 둘 다 "돈이 뜬 채 사람이
개입해야 하는" 상황이라, 그때 시스템이 조용히 넘어가지 않는지가 핵심입니다.

#### 시나리오 D — 보상이 실패하면 DLT에 남는가

보상 실패는 만들기가 까다롭습니다. 환불이 실패하려면 출금 계좌가 망가져 있어야 하는데,
그러면 애초에 출금이 안 됩니다. saga가 1초 만에 끝나서 "출금과 환불 사이"를 노리는 것도 불안정합니다.

→ **Outbox 릴레이를 끈 채 account-service를 띄워** saga를 출금 직후에 세웠습니다.
`transfer.debited`가 발행되지 않으니 그 자리에서 멈춥니다. 그 상태에서 계좌를 FROZEN으로 바꾸고,
릴레이를 켜서 재기동하면 나머지가 이어집니다. 타이밍에 기대지 않는 방법입니다.

| 확인 | 결과 |
|---|---|
| 보상 시도 횟수 | **1회** (계좌 비활성은 재시도해도 같으므로 즉시 DLT — Step 4c의 분류가 서비스 간에도 동작) |
| `transfer.credit-failed.DLT` | **1건** |
| 송금 상태 | `COMPENSATING` — 종결되지 않고 남아 사람이 볼 수 있다 |
| 출금 계좌 잔액 | 4000 (되돌아가지 못함 — 이게 DLT가 알려야 하는 사건) |

로그에도 남습니다: `보상 단계가 실패했다 - 출금이 되돌아가지 않았다 (… reason=계좌가 활성 상태가 아닙니다 …)`

#### 시나리오 E — 재시도를 소진하면 DLT로 가는가

MySQL을 내린 상태에서 `transfer.debited`를 발행했습니다. DB 장애는 <b>다시 하면 될 수도 있는</b>
실패라 재시도 대상입니다.

- `transfer.debited.DLT`에 **2건** — 이 토픽은 transfer와 account 둘 다 소비하므로 양쪽 다 DLT로 갔습니다.
- MySQL을 되살리자 두 서비스 모두 회복했고, 새 송금이 정상적으로 COMPLETED까지 갔습니다.

**여기서 예상 못 한 걸 하나 알았습니다.** DLT까지 **약 134초**가 걸렸습니다.
백오프는 1+2+4초로 잡아뒀는데, 정작 시간을 잡아먹은 건 **HikariCP의 커넥션 타임아웃 30초**였습니다.
시도마다 30초씩 기다리니 4번이면 2분입니다.

> 즉 **백오프 설정은 "실패가 빨리 난다"는 가정 위에 있습니다.** DB가 죽은 동안에는 그 파티션의
> 뒤 메시지들이 2분 넘게 발이 묶입니다. 커넥션 타임아웃 조정은 Phase 5의 "커넥션 풀 튜닝
> (HikariCP), DB 커넥션 고갈 대응" 항목에서 다룰 소재로 남깁니다.

#### 확인 도구가 틀렸던 일 (두 번째)

Step 4d에서 "DLT 전부 0건"이라고 적었는데, 그때 쓴 `kafka.tools.GetOffsetShell`은 **Kafka 4.x에서
제거된 명령**이라 조용히 빈 값을 내고 있었습니다. 올바른 `kafka-get-offsets.sh`로 다시 세어보니
실제로도 0건이 맞았지만, **근거는 없었던 셈**입니다.

원장 조회 필드를 `items` 대신 `transactions`로 읽었던 것과 같은 종류의 실수입니다.
**확인 도구도 틀릴 수 있으니, "0이 나왔다"보다 "0이 아닌 값도 낼 수 있는 명령인가"를 먼저 봐야 합니다.**

---

### Step 5a — 원장을 "송금 내역"에서 "분개장"으로 ✅

**목표**: 정합성 대사를 할 수 있는 상태 만들기

#### 대사를 하려는데 전제가 없었다

Step 5를 설계하다 막혔습니다. "계좌 잔액 합 vs 원장 합"을 맞춰보려면 그 둘이 같아야 하는데,
**애초에 같을 수가 없는 구조**였습니다. 계좌 잔액을 바꾸는 경로가 여럿인데 원장에는 송금만 남았습니다.

| 잔액이 움직이는 경로 | 원장에 남았나 |
|---|---|
| 송금 출금·입금 | ✅ |
| `/internal/accounts/{id}/credit·debit` (입출금 API) | ❌ |
| 보상 환불 | ❌ |

그래서 **모든 잔액 변경을 원장에 남기는 쪽**으로 정했습니다(사용자와 확정). 원장의 성격이
"송금 내역"에서 **"모든 잔액 변경의 분개장"**으로 바뀝니다.

#### 무엇이 바뀌었나

새 이벤트 `account.balance-changed`가 생겼습니다. 잔액이 움직이면 **그 변경과 같은 트랜잭션으로**
Outbox에 남고, 원장은 그것만 보고 한 줄씩 적습니다.

```
전: transfer.credited ─▶ Ledger가 송금 한 건을 두 줄로 기록
후: account.balance-changed ─▶ Ledger가 잔액 변경 하나를 한 줄로 기록
```

| 바뀐 곳 | 내용 |
|---|---|
| `BalanceJournal` (account) | 분개 이벤트를 Outbox에 적는 한 곳. 잔액을 바꾸는 모든 경로가 여기를 지난다 |
| `BalanceMutationExecutor` (account) | 입출금 API용. 잔액 변경 + 분개를 한 트랜잭션으로 |
| `SagaStepExecutor` (account) | Saga 단계도 같은 분개장을 거치게 |
| `BalanceChangedConsumer` (ledger) | `TransferCreditedConsumer`를 대체 |
| `Transaction` (ledger) | `reason` 추가, `transferId`는 nullable로 |

#### 결정 — 멱등성 기준을 자연키에서 분개 항목 ID로

원장은 "송금 + 계좌 + 방향"을 자연키로 삼아 멱등했습니다. 원장이 송금만 기록하던 시절엔 충분했지만
이제는 성립하지 않습니다 — **송금과 무관한 변경**이 들어오고, **같은 송금에서 같은 계좌가 두 번**
움직일 수 있습니다(출금 → 환불).

그래서 발행하는 쪽이 `entryId`를 만들어 Outbox에 **고정**하고, 원장은 그걸 문서 ID로 씁니다.
재전송이 와도 같은 값이라 같은 줄을 덮어쓰는 것으로 끝납니다.

#### 결정 — 파티션 키는 계좌 ID

Saga 이벤트는 송금 ID를 키로 씁니다. 분개 이벤트는 **계좌 ID**를 씁니다 —
한 계좌의 잔액 변경이 순서대로 소비되어야 잔액 추이가 뒤섞이지 않습니다.
목적이 다르면 키도 다릅니다.

#### 겪은 문제 — "원장 기록 완료"를 언제 알릴 것인가

전에는 `transfer.credited` 하나를 받아 두 줄을 적고 곧바로 `transfer.ledger-recorded`를 냈습니다.
이제 두 줄이 **각각 다른 이벤트로, 다른 계좌 키로** 오므로 도착 순서가 보장되지 않습니다.
"입금 줄을 적었으니 끝"이라고 볼 수 없습니다.

→ 분개를 적을 때마다 **그 송금의 출금 줄과 입금 줄이 모두 있는지** 확인하고, 다 모였을 때만 알립니다.
환불 줄은 종결 신호가 아닙니다 — 그건 Account가 `transfer.debit-reversed`로 따로 알립니다.

#### 정리한 것 — 죽은 REST 경로 제거

`InternalTransactionController`(Transfer가 원장을 동기 호출하던 Phase 1의 흔적)는 Step 4a에서
호출자가 사라졌는데도 남아 있었습니다. 이제는 위험하기까지 합니다 — **잔액 변경 없이 원장에만 줄을
넣을 수 있는 문**이라, 그것 자체가 대사를 깨뜨립니다. 삭제했습니다.

#### 검증

`./gradlew test` — **392건 전부 통과**.

| 테스트 | 확인하는 것 |
|---|---|
| `BalanceJournalTest` (account, 5건) | 잔액이 움직이는 **네 경로가 각각** 분개장에 남는지 (입금·출금 API, 송금 출금·입금, 보상 환불) |
| `BalanceChangedConsumerTest` (ledger, 4건) | 송금과 무관한 변경도 기록, 환불도 기록, 재수신에도 한 줄, 두 줄이 모여야 완료 알림 |

되돌려 red를 확인했습니다 — 입출금 경로의 분개를 빼면 3건, Saga 경로를 빼면 3건이 빨개집니다.

**e2e로 "원장 합 = 잔액"을 직접 확인했습니다.** 한 계좌에서 입금 → 정상 송금 → 실패 송금(보상)을
연달아 돌리고 세 계좌를 대조했습니다.

```
DEPOSIT         +10000 → 10000
TRANSFER_DEBIT   -3000 →  7000
TRANSFER_DEBIT   -1000 →  6000
TRANSFER_REFUND  +1000 →  7000   (보상)
```

세 계좌 모두 잔액과 원장 합이 일치했습니다. Step 5b의 대사는 이제 이 계산을 주기적으로 돌리는 일입니다.

---

### Step 5b — 정합성 대사 배치 ✅

**목표**: 어긋난 것을 <b>사람이 찾아보지 않아도</b> 드러나게 하기

#### 왜 별도 서비스인가

대사는 세 저장소를 모두 가로질러 봐야 하는 일입니다. 어느 한 서비스에 넣으면 그 서비스가
남의 데이터를 들여다보게 됩니다. 역할이 뚜렷하고 서비스 수를 늘려보는 목적에도 맞아
`reconciliation-service`(8084, MySQL `reconciliation_db`)로 분리했습니다.

대신 각 서비스가 **대사 전용 조회 API**를 열어야 했습니다.

| 서비스 | 엔드포인트 | 주는 것 |
|---|---|---|
| account | `GET /internal/reconciliation/balances` | 계좌별 잔액 (커서 페이징) |
| ledger | `POST /internal/reconciliation/balances` | 준 계좌들의 원장 합 |
| transfer | `GET /internal/reconciliation/unsettled-transfers` | 종결 안 된 오래된 송금 |
| transfer | `GET /internal/reconciliation/stranded-keys` | IN_PROGRESS로 남은 멱등성 키 |

#### 원칙 — 찾아서 알리기만 하고 고치지 않는다

고치고 싶은 유혹이 있었습니다. 특히 발이 묶인 멱등성 키는 지워주면 그만인 것처럼 보입니다.
하지만 두 가지 이유로 하지 않았습니다.

1. **남의 서비스 데이터를 대사가 바꾸는 순간 서비스 경계가 무너집니다.** 고치는 건 데이터 주인의 몫입니다.
2. **지금 구조로는 안전하지도 않습니다.** 키에는 송금 ID가 완료 시점에야 채워지고 송금 쪽에는 키가
   남지 않아, "죽은 키"와 "커밋됐는데 기록만 못 남긴 키"를 구분할 수 없습니다. 잘못 풀면
   재요청이 두 번째 송금을 만듭니다. 안전하게 고치려면 키↔송금 연결을 먼저 만들어야 합니다.

그래서 `/internal/reconciliation/*`는 전부 **읽기 전용**입니다.

#### 결정 — 계좌 쪽을 기준으로 훑는다

원장을 기준으로 돌면 **"계좌는 있는데 원장이 통째로 빈"** 경우를 못 잡습니다. 정작 그게 가장 흔한
사고인데 존재조차 모르게 됩니다. 그래서 계좌를 커서로 페이징하며 원장에 물어보는 방향으로 잡았습니다.
원장에 줄이 하나도 없는 계좌도 **0으로 반드시 돌려주도록** 했습니다 — 안 그러면 대사하는 쪽이
"아직 안 왔다"와 "정말 비었다"를 구분하지 못합니다.

#### 결정 — 회차를 따로 남긴다

발견 건수만 보면 **"어긋난 게 없었다"와 "대사가 안 돌았다"가 똑같이 0**입니다.
배치가 죽은 걸 "깨끗하다"로 오해하는 게 어긋남 자체보다 위험해서, 회차(`reconciliation_runs`)에
시작·종료 시각과 `failureReason`을 남깁니다. 다른 서비스를 못 읽으면 결과를 지우지 않고
**실패로 표시**합니다.

같은 이유로 클라이언트에 **타임아웃(연결 2초, 읽기 5초)을 걸었습니다.** 응답 없는 서비스에 매달리면
스케줄러 스레드가 잠겨 대사 자체가 조용히 멈춥니다 — 어긋남을 찾으라고 만든 것이 어긋난 줄도
모르는 상태가 됩니다.

#### 검증

`./gradlew test` — **407건 전부 통과**.

| 테스트 | 확인하는 것 |
|---|---|
| `ReconciliationServiceTest` (7건) | 일치하면 0건 / 금액 차이까지 보고 / 원장이 통째로 빈 경우 / 여러 페이지 전부 훑기 / 멈춘 송금 / 묶인 키 / **못 읽으면 실패로 남기기** |
| `ReconciliationQueryServiceTest` (account) | 커서 페이징이 계좌를 빠뜨리거나 중복하지 않는지 |
| `ReconciliationQueryServiceTest` (transfer, 4건) | 방금 접수된 건은 안 잡히고, 종결된 건도 안 잡히고, COMPENSATING은 잡히는지 |
| `BalanceChangedConsumerTest` (ledger, +1건) | 원장 합의 부호, 줄 없는 계좌도 0으로 |

되돌려 red를 확인했습니다 — 페이징을 첫 장에서 끊으면 1건, 실패를 삼키면 1건,
원장 빈 계좌를 건너뛰면 1건이 빨개집니다.

#### e2e — 배치가 이 세션에서 만든 상처를 스스로 찾아냈다

네 서비스를 띄우고 대사를 한 번 돌렸습니다. **22건을 찾았고, 그중 5건이 이 세션에서 제가
손으로 찾아냈던 바로 그 사고들이었습니다.**

```
UNSETTLED_TRANSFER  17a809ad… DEBIT_COMPLETED 상태로 멈춰 있다   ← Step 4d 전 순서 뒤바뀜 버그
UNSETTLED_TRANSFER  aba1b257… DEBIT_COMPLETED 상태로 멈춰 있다   ← Step 4d 전 상태 경합 버그
UNSETTLED_TRANSFER  4ca92313… COMPENSATING 상태로 멈춰 있다      ← 시나리오 D의 보상 실패
STRANDED_IDEMPOTENCY_KEY × 2                                    ← 접수 도중 죽은 흔적
```

앞서는 제가 어디를 봐야 할지 알고 조회해서 찾은 것들입니다. 이번엔 **배치가 알아서 짚어냈습니다.**

나머지 17건은 `BALANCE_MISMATCH`인데, 전부 **Step 5a 이전에 만들어진 계좌**입니다.
그때는 입출금이 원장에 남지 않았으니 원장 합이 실제로 비어 있는 게 맞습니다 — 오탐이 아니라
정탐입니다. 반면 이번 세션에서 새로 만든 계좌는 잔액 7000 = 원장 합 7000으로 **한 건도 잡히지
않았습니다.**

> 확인 중에 한 번 헛다리를 짚었습니다. 불일치 목록 맨 위 계좌를 이번 송금 것으로 착각하고
> "입금이 원장에 안 남았다"고 봤는데, 원장 문서의 `reason`이 `null`인 걸 보고 <b>Step 5a 이전에
> 쓰인 문서</b>임을 알았습니다. 데이터에 남은 스키마 흔적이 시점을 알려준 셈입니다.

#### 남은 것

- **묵은 불일치를 정리하는 방법이 없습니다.** 17건은 계속 잡힙니다. 실무라면 기준 시점(cutoff)을
  두거나 과거분을 개시 잔액으로 원장에 심어야 합니다.
- 발견을 **알리는 경로가 없습니다.** 지금은 API를 열어봐야 압니다. 메트릭·알람은 Phase 9 소재입니다.
- 발이 묶인 멱등성 키를 안전하게 풀려면 **키↔송금 연결**이 먼저 필요합니다.

---

### Step 6a — 원장 도입 이전 잔액을 한 줄로 이월 ✅

**목표**: Step 5b가 남긴 <b>묵은 불일치 17건</b>을 정리하되, 사각지대를 만들지 않기

#### 지울 수도 무시할 수도 없었던 17건

Step 5b e2e에서 `BALANCE_MISMATCH` 17건이 나왔고, 전부 Step 5a 이전에 만들어진 계좌였습니다.
그때는 입출금이 원장에 남지 않았으니 **원장 합이 비어 있는 게 맞습니다** — 오탐이 아니라 정탐입니다.
그래서 곤란했습니다. 오탐이면 규칙을 고치면 되는데, 정탐이라 고칠 규칙이 없습니다.

#### 결정 — cutoff 대신 개시 이월

두 가지가 후보였습니다.

| 방법 | 하는 일 | 대가 |
|---|---|---|
| 기준 시점(cutoff) | 그 전에 만들어진 계좌는 대사하지 않는다 | 그 계좌들이 **앞으로 진짜 어긋나도 영영 안 잡힌다** |
| 개시 잔액 이월 | 과거를 분개 한 줄로 요약해 원장에 심는다 | 이행 작업이 필요하고, 스냅샷 경합을 다뤄야 한다 |

**이월을 택했습니다.** cutoff는 17건을 안 보이게 만들 뿐 사각지대를 남깁니다. 회계에서 원장을
새로 열 때 하는 일이 정확히 개시 이월이기도 합니다 — 과거 전체를 한 줄로 적고 거기서부터 시작합니다.

```
계좌 A: 잔액 50000, 원장 합 0
  ↓ 개시 이월
OPENING_BALANCE +50000   ← 한 줄
  ↓
잔액 50000 = 원장 합 50000
```

#### 결정 — 금액을 받지 않고 관측값을 받는다

이월 금액은 "계좌 잔액 − 원장 합"이라 **양쪽을 다 봐야** 나옵니다. 그런데 그 둘을 함께 볼 수 있는 건
대사 서비스뿐이고, **대사는 남의 데이터를 고치지 않는다**는 게 이 저장소의 규칙입니다.

그래서 이렇게 갈랐습니다 — **본 값을 들고 와서 요청하고, 심는 건 주인이 한다.**

```
POST /internal/accounts/{id}/opening-balance
{ "observedBalance": 50000, "ledgerBalance": 0 }
```

금액을 그대로 받으면 이 엔드포인트는 *"남이 내 원장에 아무 숫자나 적을 수 있는 문"*이 됩니다.
관측값을 받아 계좌 서비스가 **직접 빼서** 정하면, 데이터를 바꾸는 주체가 끝까지 주인으로 남습니다.

> 대사 서비스는 이걸 **자동으로 부르지 않습니다.** 부르는 순간 결국 대사가 남의 데이터를
> 고치는 셈이 됩니다. 보고를 보고 이월할지 판단해 부르는 건 운영자입니다.

#### 겪은 문제 — 잔액 검사만으로는 안 닫히는 창

잔액과 원장 합은 **서로 다른 서비스에서 다른 순간에** 읽은 값입니다. 그 사이에 뭔가 움직였으면
계산해둔 차이가 이미 틀렸고, 그대로 심으면 맞추려던 원장이 오히려 어긋납니다.

처음엔 잔액 CAS 하나면 되겠다고 봤는데, **잔액은 그대로인데 원장만 뒤처진 경우**가 남습니다.
Outbox에 아직 안 나간 분개가 있으면 잔액에는 이미 반영됐지만 원장은 모르는 상태입니다.
지금 차이를 심으면 그 변경을 **이월분에 한 번, 뒤늦게 도착한 분개에 또 한 번** 세게 됩니다.

그래서 두 겹입니다.

| 검사 | 막는 것 |
|---|---|
| 잔액 CAS (본 잔액 ≠ 지금 잔액이면 거절) | 읽은 뒤 잔액이 움직인 경우 |
| 미발행 분개 검사 (Outbox에 안 나간 게 있으면 거절) | 잔액은 그대로인데 원장만 뒤처진 경우 |

**그래도 완전히 닫히지는 않습니다.** 발행은 됐지만 원장이 아직 소비하지 않은 이벤트가 있으면
그만큼 두 번 셉니다. 멈추지 않고서는 못 막는 종류라, 실무처럼 **한산한 시점에 돌리고 다음 대사
회차로 확인**하는 것으로 갈음했습니다 — 잘못 심었으면 바로 다음 회차에 다시 잡힙니다.

#### 겪은 문제 — 이월을 송금 다리로 세면 안 된다

원장은 "출금 줄과 입금 줄이 다 모였을 때" 원장 기록 완료를 알립니다. `OPENING_BALANCE`를
전송 단계로 세면 **출금 줄 하나 + 이월 줄 하나로 "둘 다 모였다"**고 판단해버립니다.
입금이 원장에 없는데도 송금이 COMPLETED가 됩니다. 이월은 다리가 아닙니다.

#### 검증

`./gradlew test` — **417건 전부 통과** (407 → 417).

| 테스트 | 확인하는 것 |
|---|---|
| `OpeningBalanceServiceTest` (account, 8건) | 빈 원장은 잔액만큼 / 일부만 있으면 모자란 만큼 / **잔액은 안 바뀜** / 두 번 심지 않음 / 이미 맞으면 안 심음 / 낡은 스냅샷 거절 / 미발행 분개 거절 / 원장이 더 많으면 반대 방향 |
| `BalanceChangedConsumerTest` (ledger, +2건) | 이월도 원장 합에 들어감 / 이월을 원장 기록 완료로 세지 않음 |

되돌려 red를 확인했습니다.

| 되돌린 것 | 빨개진 테스트 |
|---|---|
| 미발행 분개 검사 제거 | `발행되지_않은_분개가_남아_있으면_거절한다` |
| 한 번만 이월 검사 제거 | `두_번_이월해도_분개는_한_줄만_남는다` |
| 잔액 CAS 제거 | `잔액_스냅샷이_낡았으면_거절한다` |
| 이월분을 잔액에도 더함 | `이월해도_계좌_잔액은_그대로다` 외 1건 |
| `OPENING_BALANCE`를 송금 다리로 셈 | `개시_이월은_원장_기록_완료로_세지_않는다` |

**커밋**: `9f21759`

#### 남은 것

- 이월은 **계좌마다 한 번씩 불러야** 합니다. 계좌가 많아지면 훑어서 돌리는 도구가 필요합니다.
- 발이 묶인 멱등성 키를 안전하게 풀려면 여전히 **키↔송금 연결**이 먼저 필요합니다 (다음 Step).

---

### Step 6b — 접수 도중 죽은 멱등성 키를 안전하게 걷어내기 ✅

**목표**: Step 5b가 "구조상 못 한다"고 적어둔 일을 할 수 있게 만들기

#### 구분할 수 없어서 아무것도 못 했다

접수는 세 번의 커밋입니다.

```
① 키 선점(IN_PROGRESS)  →  ② 송금 저장 + transfer.requested  →  ③ 키에 결과 기록(COMPLETED)
```

①과 ② 사이에서 죽으면 송금이 없고, ②와 ③ 사이에서 죽으면 송금이 있습니다.
**남는 흔적은 똑같이 "IN_PROGRESS로 남은 키" 하나뿐인데, 처신은 정반대입니다.**

| 죽은 지점 | 해야 할 일 | 잘못하면 |
|---|---|---|
| ① → ② 사이 | 키를 놓아준다 | 안 놓아주면 그 키로는 영영 송금 못 함 |
| ② → ③ 사이 | 접수된 송금을 돌려준다 | 놓아주면 **두 번째 송금**이 생김 |

구분할 근거가 없어 **둘 다 영원히 409**로 뒀습니다. 안전하긴 하지만, ②→③에서 죽은 경우
사용자는 이미 시작된 자기 송금을 영영 돌려받지 못했습니다.

#### 결정 — 송금 쪽에 키를 남긴다

길이 한 방향뿐이었습니다. 키 → 송금은 있는데(그마저 ③에서야 채워짐) **송금 → 키가 없었습니다.**
`transfers.idempotency_key`를 추가해 반대 방향을 만들었습니다.

핵심은 이게 **②와 같은 트랜잭션**에 들어간다는 점입니다. 그래서 **송금이 있으면 키도 반드시
적혀 있습니다.** 이제 묶인 키를 만나면 송금 쪽에 물어보면 됩니다.

```
키 IN_PROGRESS + 그 키로 커밋된 송금이 있다  ─▶ ②→③에서 죽었다. 키를 마저 닫고 그 송금을 돌려준다
키 IN_PROGRESS + 송금이 없다 + 오래됐다      ─▶ ①→②에서 죽었다. 키를 놓아주고 새로 접수한다
키 IN_PROGRESS + 송금이 없다 + 방금 것       ─▶ 지금 접수 중일 수 있다. 409
```

#### 가장 조심한 것 — 살아 있는 접수의 키를 뺏지 않기

세 번째 줄이 없으면 **지금 ①을 막 끝내고 ②로 가는 중인 요청**의 키를 뺏게 됩니다.
그러면 같은 키로 두 건이 접수되어, 멱등성이라는 계약 자체가 무너집니다.
접수는 몇 밀리초면 끝나므로 기준(`ABANDON_AFTER`, 10분)을 넉넉히 잡아도 잃는 게 없습니다.

> 이 값은 대사의 `reconciliation.key-stranded-after`와 뜻이 같습니다. 한쪽만 바꾸면 대사가
> "묶였다"고 보고하는 키를 정작 재요청은 안 풀어주거나, 그 반대가 됩니다.

#### 안전망 — unique 제약

판정 로직에 기대는 것과 **구조적으로 불가능하게 만드는 것**은 다릅니다.
`transfers.idempotency_key`에 unique를 걸어, 판정이 어떤 이유로든 뚫려도 같은 키로
두 번째 송금이 저장되는 것 자체를 DB가 막게 했습니다.

#### 대사는 여전히 고치지 않는다 — 다만 구분해서 알린다

풀 수 있게 됐다고 대사가 풀면, 아무도 재요청하지 않은 키까지 건드리게 됩니다.
그건 사고를 없애는 게 아니라 **사고의 흔적을 없애는 것**입니다. 푸는 건 실제 재요청이 들어왔을 때
접수 경로가 합니다.

대신 보고를 갈랐습니다(`StrandedKeyView.committedTransferId`). 뭉뚱그려 적으면 보는 사람이
접수된 송금을 못 봤다고 착각해 **같은 송금을 두 번 보낼 수 있습니다.**

#### 겪은 문제 — 시간대 때문에 과거로 민 값이 미래가 됐다

"오래 묶인 키"를 테스트하려고 `created_at`을 30분 전으로 써넣었는데 계속 "방금 것"으로 판정됐습니다.
**Hibernate는 `Instant`를 UTC로 저장하는데 `Timestamp.from()`은 로컬 시각으로 씁니다.**
KST 기준 9시간 차이라, 30분 과거로 민 값이 실제로는 8시간 30분 미래가 되어 있었습니다.

절대 시각을 써넣는 대신 **저장된 값에서 빼는**(`created_at - INTERVAL ? SECOND`) 방식으로 바꿨습니다.
시간대와 무관해집니다.

#### 겪은 문제 — 되돌리기 실험이 아직 커밋 안 한 코드를 지웠다

되돌려 red를 확인하는 과정에서 `git checkout --`로 복원했는데, **그 파일들의 Step 6b 변경이
아직 커밋 전이라 통째로 날아갔습니다.** 다시 작성한 뒤로는 실험 전에 사본을 떠두고 그걸로 복원했습니다.

#### 겪은 문제 — 중복을 세지 못하는 검증

`findByIdempotencyKey`는 `Optional`이라 두 건이 있어도 1을 넘길 수 없습니다.
정작 확인하고 싶은 게 **"두 건이 생겼는가"**인데 그걸 못 세는 헬퍼로 검증하고 있었습니다.
목록으로 세도록 고치니 되돌리기 결과도 깨끗해졌습니다 — 그 전에는 엉뚱한 테스트들이 함께 빨개져
무엇이 무엇을 잡는지 읽히지 않았습니다.

#### 검증

`./gradlew test --rerun-tasks` — **424건 전부 통과** (417 → 424).

| 테스트 | 확인하는 것 |
|---|---|
| `IdempotencyRecoveryTest` (transfer, 6건) | 송금에 키가 남음 / 접수만 된 경우 그 송금을 돌려줌 / 키가 COMPLETED로 닫힘 / 묵은 키는 풀리고 새로 접수 / **방금 선점된 키는 안 뺏음** / 같은 키 두 번째 송금은 DB가 막음 |
| `ReconciliationServiceTest` (+1건) | 묶인 키가 접수까지 갔는지 구분해 보고 |

되돌려 red를 확인했습니다.

| 되돌린 것 | 빨개진 테스트 |
|---|---|
| 전진 복구(송금 쪽 조회) 제거 | 접수된 송금 반환 · 키 닫힘 (2건) |
| 방금 선점된 키 검사 제거 | `방금_선점된_키는_송금이_없어도_뺏지_않는다` |
| 송금에 키를 안 남김 | 묵은 키 해제 · 키 기록 (2건) |
| unique 제약 제거 | `같은_키로_두_번째_송금은_DB가_막는다` |

**커밋**: `2717f6e`

#### 남은 것

- 아무도 재요청하지 않는 묵은 키는 계속 남습니다. `expiresAt`을 실제로 쓰는 만료 정리는 아직 없습니다.
- 실제 크래시가 아니라 **크래시가 남긴 상태를 만들어** 검증했습니다. 진짜 중단 시점 주입은 안 했습니다.

---

## Phase 3 — 이벤트 기반 아키텍처 ✅

**목표**: 토픽을 넓히고 새 컨슈머를 붙여보기

Kafka를 Phase 2로 당겨 쓴 탓에 이 Phase의 세 항목(토픽 설계·프로듀서·컨슈머)은 이미 끝나 있었고,
남은 건 **알림 컨슈머** 하나였습니다.

### 왜 별도 서비스인가

`reconciliation-service`와 같은 이유입니다. 역할이 뚜렷하고, 서비스 수를 늘려보는 것 자체가
이 프로젝트의 목적 중 하나입니다. `notification-service`(8085, MySQL `notification_db`)로 냈습니다.

**Saga에는 끼어들지 않습니다.** 아무 이벤트도 발행하지 않으므로 이 서비스가 통째로 죽어도 송금은
정상 완료됩니다. 알림이 송금을 막을 수 있다면 그건 잘못 붙인 것입니다.

### 결정 — 성공만이 아니라 실패도 알린다

ROADMAP에는 `transfer.completed`만 적혀 있었지만 `transfer.failed`도 구독합니다.
**정작 사용자가 알아야 할 건 실패 쪽**입니다 — 성공은 잔액을 보면 알지만, 실패는 알려주지 않으면
"보낸 줄 알았는데 안 갔다"가 됩니다.

대신 실패는 **보낸 쪽에만** 갑니다. 받는 쪽에 알리면 있지도 않았던 거래를 알려주는 꼴입니다.
반대로 완료는 **두 사람 모두**에게 갑니다.

### 여기서 연습한 것 — 되돌릴 수 없는 부수효과

지금까지의 멱등성은 다 되돌릴 여지가 있었습니다. 원장은 같은 줄을 덮어쓰면 그만이고, 잔액은
처리 흔적으로 막을 수 있습니다. **그런데 이미 나간 알림은 회수할 수 없습니다.**
"10만원을 보냈습니다"가 두 번 가면 사용자는 두 번 나간 줄 압니다.

```
① 자리 잡기(PENDING 저장)  ─▶  ② 발송  ─▶  ③ SENT로 표시
```

| 죽은 지점 | 재배달이 오면 | 결과 |
|---|---|---|
| ①~② 사이 | PENDING을 보고 다시 보낸다 | 정상 |
| ②~③ 사이 | PENDING을 보고 다시 보낸다 | **한 번 더 나간다** |

두 번째가 남는 창입니다. 발송과 기록을 원자적으로 묶을 수 없는 이상 못 없애고,
**드물게 두 번 가는 것이 영영 안 가는 것보다 낫다**고 보고 이쪽을 택했습니다.

반대로 ①에서 곧바로 SENT로 적으면 ①~② 사이에 죽었을 때 재배달이 "이미 보냈다"로 읽고 건너뜁니다.
알림이 조용히 사라지고 아무도 모릅니다. **그래서 상태가 두 개 필요합니다.**

unique 제약은 (송금, 종류, **받는 사람**)입니다. 받는 사람까지 넣는 이유는 완료된 송금 한 건이
두 사람에게 가기 때문입니다 — 빼면 둘 중 한 명은 알림을 못 받습니다.

### 겪은 문제 — 테스트가 자기 호출 함정을 잡아냈다

처음엔 `claim()`과 `markSent()`를 `NotificationService` 안에 뒀습니다. `deliver()`가 같은 빈의
`markSent()`를 부르니 **`@Transactional` 프록시를 타지 않았고**, 더티 체킹이 안 걸려
상태가 저장되지 않았습니다.

증상이 고약합니다. 알림은 잘 나가고 행도 제대로 생기는데 **상태가 영원히 PENDING**이라,
재배달이 올 때마다 알림이 다시 나갑니다. 로그만 보면 아무 문제가 없어 보입니다.

`같은_소식을_두_번_받아도_알림은_한_번만_나간다`가 "행은 2건인데 발송은 4번"으로 잡아냈습니다.
기록을 `NotificationRecorder`라는 별도 빈으로 갈라 해결했습니다 —
account-service의 `BalanceMutationExecutor`와 같은 이유의 구조입니다.

> 이 저장소가 이미 아는 함정이었는데도 그대로 밟았습니다. AGENTS.md에 적혀 있어도
> **새 서비스를 만들 때는 다시 밟는다**는 뜻이라, 새 모듈의 `@Transactional` 경계는
> 테스트로 확인하는 게 낫습니다.

### 검증

`./gradlew test --rerun-tasks` — **429건 전부 통과** (424 → 429).

| 테스트 | 확인하는 것 |
|---|---|
| `TransferOutcomeConsumerTest` (5건) | 완료는 양쪽에 / 실패는 보낸 쪽에만 + 사유 포함 / **두 번 받아도 한 번만** / 발송 실패 시 재배달로 끝내 나감 / 컨텍스트 기동 |

되돌려 red를 확인했습니다.

| 되돌린 것 | 빨개진 테스트 |
|---|---|
| 이미 보냈는지 보는 가드 제거 | `같은_소식을_두_번_받아도_알림은_한_번만_나간다` |
| 보냈다고 적지 않음 | 위 + `발송이_실패하면_재배달로_다시_보낸다` |
| 실패를 받는 쪽에도 알림 | `실패한_송금은_보낸_쪽에게만_알린다` 외 1건 |
| unique 제약 + 기존 조회 제거 | 중복 알림 2건 |

**커밋**: `66b1350`

### 남은 것

- 발송은 **로그로 흉내**만 냅니다. 실제 푸시·SMS 연동은 이 프로젝트의 관심사가 아닙니다.
- 연락처가 없어 **계좌 ID를 받는 사람**으로 삼습니다. 사용자 개념이 들어오면 바뀔 자리입니다.
- 알림 실패가 DLT로 빠지면 아무도 모릅니다. 송금은 멀쩡해서 **대사에도 안 잡힙니다** —
  돈이 어긋난 게 아니기 때문입니다. DLT 관측은 Phase 9 소재입니다.

---

## CI 위생 — 빌드가 자기 커밋을 알게 하고, 테스트를 갈랐다 ✅

**Phase가 아닙니다.** 뒤 Phase의 전제라 먼저 해둔 것들입니다.

### 빌드에 커밋을 새긴다

`version = '0.0.1-SNAPSHOT'` 고정이라 **"지금 떠 있는 게 어느 커밋이냐"에 답할 수단이
없었습니다.** Phase 7에서 이미지를 굽고 Phase 8에서 배포하기 시작하면 그 순간부터 필요합니다.

**계획을 바꿨습니다.** 원래 ROADMAP에는 "Gradle 버전을 커밋 SHA로"라고 적었는데,
그건 답이 아니었습니다 — 버전을 바꿔도 **실행 중인 프로세스에 물어볼 수단은 여전히 없습니다.**
`bootBuildInfo`로 커밋·브랜치·빌드 시각을 새겨 `GET /actuator/info`가 답하게 했습니다.
역할을 이렇게 나눴습니다.

| | 무엇을 위한 것 |
|---|---|
| 아티팩트 버전 `0.0.1-SNAPSHOT` | 제품 버전 (그대로 둠) |
| 이미지 태그 `github.sha` | Phase 7에서 배포하는 쪽이 씀 |
| **build-info** | **런타임 식별** — 파드에 물어보면 답이 나옴 |

git이 없는 환경(소스 tarball 등)에서는 조용히 `unknown`으로 떨어집니다 —
버전 정보 때문에 빌드를 못 하는 건 본말전도라서요.

### 테스트를 갈랐다

Testcontainers 통합 테스트가 PR마다 전부 돌아 느렸습니다. Phase 5에서 부하 테스트까지 붙으면
더 나빠집니다.

```
./gradlew unitTest   331건   9초       Docker 불필요
./gradlew test       431건   1분 43초  전체
```

각 서비스의 `AbstractIntegrationTest`에 `@Tag("integration")`을 달았습니다.
**JUnit의 `@Tag`는 상속되므로** 그걸 상속한 테스트 36개가 자동으로 분류됩니다 —
테스트 파일을 하나도 안 건드렸습니다.

CI는 `unit` → `build` 순으로 돕니다(`needs: unit`). **오타 하나 때문에 MySQL·MongoDB·Redis·Kafka를
띄우고 있을 이유가 없습니다.**

### 겪은 것 — 컨테이너 경합을 회귀로 오해할 뻔했다

`./gradlew build --rerun-tasks`로 전체를 돌리자 `ledger-service`에서 11건이 실패했습니다.
`MongoSocketReadTimeoutException`과 Awaitility 타임아웃이었는데, **로직 실패가 아니라
자원 고갈**이었습니다. 모듈마다 컨테이너 스택이 뜨는데 앞 모듈 것이 아직 정리되기 전이라
Docker가 말라붙은 것입니다.

`:ledger-service:test` 단독은 24초에 통과했고, `clean test`로 다시 돌리니 **431건 전부
통과**했습니다. **테스트가 빨개졌다고 다 회귀는 아닙니다** — 실패 메시지를 먼저 읽어야 합니다.

### 검증

| 테스트 | 확인하는 것 |
|---|---|
| `BuildInfoTest` (account, 2건) | 빌드 결과물이 자기 커밋을 알고 있는지, 조용히 `unknown`으로 떨어지지 않는지 |

되돌려 red를 확인했습니다 — 루트 `build.gradle`의 `bootBuildInfo` 블록을 지우면 2건이 빨개집니다.

### 브랜치 보호를 걸었다

`main`에만 걸었습니다. **PR 필수 + CI 통과 필수 + force push·삭제 금지.**

혼자 쓰는 저장소라 설정을 잘못 걸면 본인이 잠깁니다. 두 군데를 조심했습니다.

| 설정 | 값 | 이유 |
|---|---|---|
| 필요 승인 수 | **0** | 1명 이상이면 **자기 PR을 자기가 승인 못 해** 완전히 막힙니다 |
| 관리자도 적용 | **아니오** | 실수로 push하는 건 막고, 정말 필요하면 설정을 끄고 할 수 있게 |
| `develop` | **보호 안 함** | 통합 브랜치라 지금처럼 직접 커밋하는 흐름을 유지 |

Phase 2·3을 e2e 없이 `main`에 올렸다 되돌린 일이 있었는데, **이제 실수로는 안 됩니다.**
다만 브랜치 보호가 강제하는 건 "CI가 통과했는가"까지입니다 — **"e2e를 돌렸는가"는 여전히
사람의 몫**입니다.

### 남은 것

- **변경된 모듈만 빌드**는 아직입니다. 모노레포인데 한 서비스만 고쳐도 5개를 다 돌립니다
- 정적 분석·커버리지가 없습니다

---

## Phase 5 Step 1 — 부하를 걸 수단 ✅

**목표**: 재현 가능한 부하 시나리오를 저장소에 넣기

### 설계에서 가장 중요했던 것 — 202는 성공이 아니다

`POST /transfers`는 접수만 하고 즉시 202를 줍니다. **이때 돈은 아직 안 움직였습니다.**
그래서 이것만 재면 "초당 수천 건"이 나오는데, 그건 `INSERT` 두 번의 속도지 송금 처리량이 아닙니다.

```
POST /transfers  →  202 (PENDING)
                    ↓
   Outbox 릴레이(500ms) → Kafka → account 컨슈머 → ledger → COMPLETED
   ↑ 진짜 병목은 이 뒤에 있다
```

모든 시나리오가 **접수 지연**과 **종결 지연**(`settle_duration`)을 나눠 잽니다.

### 결정 — 부하를 거는 쪽과 재는 쪽을 분리

부하를 거는 **모든** 요청에 대해 완료를 폴링하면 **폴링 자체가 부하**가 됩니다.
요청을 늘릴수록 조회도 같이 늘어 무엇을 재는지 알 수 없게 됩니다.

그래서 k6 시나리오 두 개를 동시에 돌립니다.

| | executor | 하는 일 |
|---|---|---|
| `load` | `ramping-arrival-rate` | 접수만. 종결은 안 기다림 |
| `prober` | `constant-arrival-rate` (1/s) | 끝까지 따라가 종결 지연을 잼 |

### 결정 — `ramping-arrival-rate` (VU 고정이 아니라)

VU 수를 고정하면 시스템이 느려질 때 **부하도 같이 줄어들어 문제가 가려집니다**
(coordinated omission). 초당 요청 수를 목표로 밀어붙여야 진짜 천장이 드러납니다.

### 시나리오 셋

| 파일 | 무엇을 드러내나 |
|---|---|
| `spread.js` | 계좌를 넓게 흩어 락 경합을 낮춤 → **파이프라인 병목** (가설: 약 50 TPS) |
| `hot-account.js` | 받는 계좌 하나로 고정 → **계좌 락 병목.** 서버를 늘려도 안 풀림 |
| `read-heavy.js` | 원장 조회 폭주 → WebFlux + MongoDB 선택이 값을 하는지 |

`hot-account`가 이 Phase의 핵심입니다. **접수(202)는 여전히 빠르고 HTTP 에러도 거의 안 납니다** —
락 경합이 비동기 파이프라인 뒤에서 벌어지기 때문입니다. 접수 지표만 보면 시스템이 멀쩡해 보입니다.

### 겪은 것 — PROGRESS.md가 경고한 실수를 그대로 했다

원장 조회 응답을 `transactions`로 읽었는데 실제 필드는 **`items`**입니다.
**Step 5b 기록에 "원장 조회 필드를 `items` 대신 `transactions`로 읽었던 것과 같은 종류의 실수"라고
적혀 있는 바로 그 실수**입니다. 코드에서 DTO를 직접 확인하고 고쳤고, 같은 함수 주석에 못 박아 뒀습니다.

> 기록해둔 함정도 다시 밟습니다. **문서에 적는 것과 확인하는 것은 다릅니다.**

### 검증 — smoke로 실제로 돌렸다

스크립트는 돌려보기 전엔 맞는지 알 수 없어서, `SMOKE=1`(20초, 낮은 부하) 모드를 넣고
세 시나리오를 전부 실제 서비스에 대고 돌렸습니다.

```
=== spread ===
  접수   11.75 req/s   p95 31.52 ms   실패율 0.00
  종결   p95 2063 ms   종결 성공률 1.00   COMPLETED 21
```

낮은 부하인데도 **종결까지 약 2초**가 걸립니다 — Outbox 폴링(500ms) + Kafka + 컨슈머 두 단계가
쌓인 값입니다. 부하를 올리면 여기가 어떻게 되는지가 Step 3의 관심사입니다.

돌려보고 두 가지를 고쳤습니다.

| 발견 | 고침 |
|---|---|
| `p99`가 항상 `—` | k6 기본 요약은 p99를 계산하지 않음 → `summaryTrendStats`에 추가 |
| 0건인 카운터가 `—`로 보임 | "실패 0건"과 "못 쟀다"가 구분 안 됨 → 카운터는 `0`으로 표시 |
| 조회 시나리오에 종결 섹션이 빈 칸으로 | 송금이 없는 시나리오에선 섹션 자체를 숨김 |

**`/actuator/info`도 실제로 확인**했습니다 — 커밋 `57c1cddfe490`을 정확히 보고합니다.
CI 위생에서 넣은 build-info가 살아서 동작합니다.

### 남은 것

- **Step 2(Prometheus + Grafana)가 먼저입니다.** k6는 클라이언트에서 본 숫자만 줍니다 —
  **어디가 막혔는지**(Outbox 적체, 락 대기, 커넥션 풀)는 서버 쪽 메트릭이 답합니다
- Step 3 baseline은 아직입니다. 측정 수단이 서고 나서 재야 의미가 있습니다

---

## 밀린 e2e를 몰아서 확인했다 ✅

**목표**: `main` 머지를 막고 있던 "e2e 미확인"을 걷어내기

Step 6a·6b와 Phase 3 알림은 테스트만 통과한 채로 남아 있었습니다. 그 상태로 Phase 5 Step 2
(Prometheus·Grafana)로 넘어가면, **baseline 숫자가 이상할 때 그게 병목인지 미확인 구간의
결함인지 가릴 수 없습니다.** 측정은 동작이 확인된 시스템 위에서 해야 의미가 있어서 먼저 돌렸습니다.

다섯 서비스를 실제로 띄우고(로컬 인프라 + `bootJar`, 커밋 `54a0da2`) 9건을 확인했습니다.

| # | 시나리오 | 확인한 것 | 결과 |
|---|---|---|---|
| A | 정상 송금 | 3초 만에 COMPLETED, 잔액 7000/3000, 원장 양쪽 분개, 알림 2건 | ✅ |
| B | 입금 실패 → 보상 | FROZEN 계좌로 송금 → FAILED, 출금 복구(5000), `TRANSFER_REFUND` 분개 | ✅ |
| C | 출금 실패 | 잔액 부족 → FAILED, **실패 알림이 보낸 쪽에만** (받는 쪽 0건) | ✅ |
| D | 보상 실패 → DLT | 릴레이 정지로 출금 직후 정지 → 양쪽 FROZEN → 재기동 | ✅ |
| E | 재시도 소진 → DLT | MySQL 정지 후 `transfer.debited` 발행 | ✅ |
| F | Step 6a 개시 이월 | 낡은 스냅샷 409 → 이월 SEEDED → 재호출 ALREADY_CARRIED | ✅ |
| G | Step 6b 전진 복구 | 키 IN_PROGRESS + 커밋된 송금 → **같은 송금을 돌려줌** | ✅ |
| H | Step 6b 묵은 키 / 살아 있는 키 | 30분 된 키는 풀려 새 접수, 방금 선점된 키는 409 | ✅ |
| I | Phase 3 알림 중복 | `transfer.completed` 재발행 → 알림이 **늘지 않음** | ✅ |

**D와 E는 Phase 2에서 이미 봤지만 다시 돌렸습니다.** Step 5a에서 `TransferSagaService`와
`SagaStepExecutor`가 바뀌었기 때문입니다(모든 잔액 변경을 원장에 기록). 보상 경로에 분개가
한 줄 더 생겼으니, 그 경로를 다시 안 밟으면 바뀐 코드를 확인하지 않은 셈입니다.

### 결함 1(토픽 1파티션 자동 생성)은 재발하지 않았다

콜드 스타트에서 모든 컨슈머가 3파티션을 전부 잡았습니다. Step 4d에서 브로커 자동 생성을 끄고
토픽을 발행 서비스가 소유하게 한 조치가 유지되고 있습니다.

```
transfer.debited-0, transfer.debited-1, transfer.debited-2   ← 10개 토픽 전부 동일
```

### D — 보상이 실패하면 여전히 사람이 볼 수 있게 남는다

Phase 2 때와 같은 결과입니다. 보상 시도는 **1회**(계좌 비활성은 재시도해도 같으므로 즉시 DLT),
`transfer.credit-failed.DLT` +1, 송금은 `COMPENSATING`으로 남고 출금 계좌는 4000 그대로입니다.

**다만 이 송금에는 알림이 하나도 가지 않습니다.** 완료도 실패도 발행되지 않았으니 당연한데,
사용자 입장에서는 **돈이 빠진 채 아무 소식이 없는 상태**입니다. 대사도 임계 시간(`unsettled-after`)
전에는 안 잡아서, 그 사이는 DLT를 보는 사람만 압니다.

### E — 134초는 백오프가 아니라 커넥션 타임아웃이다 (재확인)

MySQL을 내리고 `transfer.debited`를 발행하니 **약 136초 뒤** `transfer.debited.DLT`에 2건이
붙었습니다(transfer·account 양쪽). Phase 2에서 잰 134초와 사실상 같습니다 —
백오프 1+2+4초가 아니라 **HikariCP 커넥션 타임아웃 30초 × 4회**가 시간을 다 씁니다.
MySQL을 되살리자 5초 안에 다섯 서비스가 모두 회복했고, 새 송금이 3초 만에 COMPLETED까지 갔습니다.

### 확인 도구를 먼저 의심했다 — 대조군을 같이 발행했다

시나리오 I에서 `transfer.completed`를 재발행했더니 알림이 늘지 않았습니다. **그런데 이건
"중복을 막았다"와 "메시지가 애초에 안 갔다"가 똑같이 만드는 결과입니다.**

그래서 같은 배치에 **새 `transferId`로 조작한 이벤트를 하나 더** 넣었습니다.

| 발행한 것 | 기대 | 결과 |
|---|---|---|
| 기존 송금의 `completed` 재발행 | 알림이 안 늘어야 | 1건 → 1건 ✅ |
| 새 `transferId`의 `completed` | 알림이 나와야 (경로가 살아있다는 증거) | 발송 로그 2줄 ✅ |

토픽 오프셋도 2 늘었습니다. **대조군이 없었으면 "중복 차단됨"이라고 적고 넘어갔을 텐데,
그 근거는 없었을 것입니다** — Step 4d에서 `GetOffsetShell`로 "DLT 0건"을 적었다가 명령 자체가
제거된 것이었던 일과 같은 종류의 함정입니다.

### 발견 — DLT로 가는데 로그가 한 줄도 안 남는다 🔴

notification-service에 **본문이 깨진 메시지**를 넣어봤습니다. 결과는 설계대로였습니다.

- `transfer.completed.DLT`에 1건 적재 (`JacksonException`은 재시도 대상이 아니라 즉시)
- **뒤따르는 정상 메시지는 그대로 처리됨** — 파티션이 막히지 않습니다

그런데 그 서비스 로그의 **WARN·ERROR가 0건**입니다. 메시지 하나가 죽어서 DLT로 갔는데
로그에 흔적이 없습니다. account-service는 `TransferSagaService`가 직접
`보상 단계가 실패했다`를 ERROR로 남기지만, 그건 **애플리케이션이 자기 사정을 적은 것**이지
DLT 적재를 알리는 게 아닙니다. 네 서비스의 `KafkaErrorHandlingConfig`는 모두
`DeadLetterPublishingRecoverer`만 넘길 뿐 복구 사실을 로깅하지 않습니다.

> PROGRESS의 Phase 3 "남은 것"에 **"알림 실패가 DLT로 빠지면 아무도 모른다"**고 적어뒀는데,
> 실제로는 **생각보다 나쁩니다.** 메트릭이 없는 게 아니라 로그조차 없습니다.
> Phase 5 Step 2에서 심을 메트릭 목록에 **DLT 적재 건수**를 넣고, 그와 별개로
> **복구 시점에 WARN 한 줄**은 지금이라도 남기는 게 맞습니다 (네 서비스 모두).

### 남은 잔여물

대사를 돌리면 `BALANCE_MISMATCH` 17건이 아직 잡힙니다. **전부 08-16~08-20에 만들어진
이전 e2e 계좌**로, 오늘 만든 계좌는 하나도 걸리지 않았습니다. Step 6a의 개시 이월을 계좌마다
불러주면 사라지지만, 지금은 훑어서 돌리는 도구가 없어 한 건씩 불러야 합니다
(Step 6a "남은 것"에 적어둔 그대로입니다). 오늘 시나리오 D가 만든 `COMPENSATING` 송금 1건도
`UNSETTLED_TRANSFER`로 잡힐 예정입니다 — 일부러 만든 것이라 그대로 둡니다.

### 확인하지 못한 것

- **미발행 분개 검사**(Step 6a의 두 번째 겹)는 e2e로 못 밟았습니다. Outbox에 안 나간 분개가
  남은 순간을 잡아야 하는데, 릴레이가 500ms라 손으로 노릴 창이 없습니다. 단위 테스트
  (`발행되지_않은_분개가_남아_있으면_거절한다`)로만 확인된 상태입니다.
- **이월을 송금 다리로 세지 않는지**도 마찬가지입니다. 원장이 입금 줄을 아직 못 받은 찰나에
  `OPENING_BALANCE`가 있어야 하는데 그 타이밍을 만들 수단이 없습니다.
  대신 이월된 계좌에서 송금을 보내 잔액과 원장 합이 계속 일치하는 것까지는 봤습니다.
- 이번 e2e는 **여전히 손으로 돌렸습니다.** 스크립트로 굳히는 건 Phase 7·8에서 컨테이너·K8s로
  매번 새 환경을 띄우게 될 때 필요해집니다.

---

## Phase 5 Step 2 — 결과를 볼 수단 (전반부) ✅

**목표**: k6가 못 보는 것을 보는 눈 만들기

Step 1에서 부하를 걸 수단은 생겼지만, k6는 **클라이언트에서 본 숫자**만 줍니다.
접수가 느려졌을 때 그게 Outbox 적체인지, 락 대기인지, 커넥션 고갈인지는 서버 쪽만 압니다.

`docker compose up`만으로 Prometheus(9090) + Grafana(3000)가 서고, 대시보드가 이미 그려져 있게 했습니다.

### 결정 — Prometheus가 호스트를 거꾸로 긁는다

서비스는 아직 컨테이너가 아닙니다(컨테이너화는 Phase 7). 그래서 수집 방향이 반대입니다 —
컨테이너 안의 Prometheus가 **호스트의 8081~8085**를 긁습니다.

```
[Prometheus 컨테이너] ──> host.docker.internal:8081~8085/actuator/prometheus
```

`host.docker.internal`은 Docker Desktop에만 기본으로 있어서, compose에 `extra_hosts`로
`host-gateway`를 걸어 Linux에서도 같게 만들었습니다. Phase 7에서 서비스가 컨테이너로 들어오면
이 타깃은 서비스 이름으로 바뀝니다.

`service` 라벨도 수집 설정에서 붙입니다 — **Micrometer가 `spring.application.name`을 태그로
달아주지 않는다**는 걸 실제 출력에서 확인했습니다. 이게 없으면 대시보드에서 어느 서비스의
숫자인지 구분할 수 없습니다.

### 겪은 문제 — 히스토그램을 켜지 않으면 p95는 영원히 안 나온다

대시보드를 만들고 쿼리를 하나씩 돌려보니 **13개 중 6개가 빈 결과**였습니다.
`histogram_quantile()`을 쓰는 패널이 전부였습니다.

```
http_server_requests_seconds_count{...} 170     ← 이것과
http_server_requests_seconds_sum{...}   2.92    ← 이것만 나온다
http_server_requests_seconds_bucket             ← 없다
```

기본값으로는 `_count`와 `_sum`만 나옵니다. **그 둘로 구할 수 있는 건 평균뿐인데, 평균은 꼬리를
감춥니다** — 이 프로젝트가 보려는 게 정확히 그 꼬리입니다. 더 나쁜 건 조용하다는 점입니다.
`histogram_quantile()`은 버킷이 없어도 오류를 내지 않고 **빈 패널**을 냅니다.

> **대시보드를 만들었다는 것과 값이 나온다는 것은 다릅니다.** 열어보기 전엔 모릅니다.

### 겪은 문제 — 버킷 상한을 잘못 걸었더니 요청마다 500이 났다

버킷 수(=시계열 수)를 줄이려고 `maximum-expected-value: 10s`를 걸었습니다. 그랬더니
**모든 HTTP 요청이 500**이 됐습니다.

```
InvalidConfigurationException: maximumExpectedValue (1.0E10)
  must be equal to or greater than minimumExpectedValue (1.2E11)
```

이 설정은 **이름 접두사**로 걸리는데, `http.server.requests.active`(진행 중인 요청을 재는
LongTaskTimer)까지 함께 잡힙니다. 그 타이머의 기본 최솟값이 120초라 10초 상한과 충돌합니다.

고약한 점이 둘입니다.

| | |
|---|---|
| **기동은 멀쩡했다** | 예외는 요청이 올 때 서블릿 필터에서 터진다 |
| **기존 테스트가 하나도 안 빨개졌다** | 진짜 포트로 요청을 보내는 테스트가 없었다 |

`ServerHttpObservationFilter`에서 터지므로 MockMvc로는 재현되지 않습니다.
상한을 거는 대신 **버킷 경계를 직접 고르는(slo)** 방식으로 바꿨습니다 — 접두사 매칭이 아니라
**이름을 정확히 일치**시키므로 `.active`가 걸리지 않습니다.

### 겪은 문제 — 테스트의 application.yml이 운영 설정을 통째로 가린다

설정을 `application.yml`에 적고 테스트를 썼더니 계속 빨갰습니다. 원인은 제 설정이 아니었습니다.

**`src/test/resources/application.yml`과 `src/main/resources/application.yml`은 이름이 같아,
클래스패스에서 테스트 쪽 하나만 읽힙니다.** 운영 설정은 테스트에서 존재하지 않습니다.

이건 이 항목 하나의 문제가 아닙니다 — **`application.yml`에만 적은 설정은 무엇이든 테스트가
확인하지 못합니다.** 그래서 분포 설정을 `MetricsDistributionConfig`(코드)로 옮겼습니다.
컴포넌트 스캔을 타므로 테스트와 운영이 **같은 설정**을 씁니다.

> 엔드포인트 노출(`exposure.include`)은 여전히 YAML이라 이 방법으로 확인할 수 없습니다.
> 그건 Prometheus의 **수집 대상 상태(up) 패널**이 답합니다 — 노출이 꺼지면 타깃이 down으로 떨어집니다.

### 겪은 문제 — 죽은 줄 알았던 프로세스가 컨슈머 그룹을 붙들고 있었다

부하를 걸었는데 송금이 전부 `CREDIT_COMPLETED`에서 멈췄습니다. ledger의 파티션 1에만
lag 342가 얼어붙어 있었습니다.

원인은 제품이 아니라 환경이었습니다. `pkill`이 ledger 프로세스 하나를 못 잡았고,
**그 낡은 프로세스가 포트 8083과 컨슈머 그룹 자리를 붙들고** 있었습니다.
새로 띄운 프로세스는 그룹에 못 들어가 `(Re-)joining group`에서 멈춰 있었습니다.

**`/actuator/health`는 UP이라고 답했습니다** — 낡은 프로세스가 답한 것이었습니다.
`SIGKILL`로 정리하고 다시 띄우니 밀려 있던 339건이 전부 COMPLETED로 풀렸습니다.

> 확인해야 할 것은 "포트가 응답하는가"가 아니라 **"내가 띄운 그 프로세스가 응답하는가"**입니다.
> 파티션 할당 로그(`partitions assigned`)를 함께 보는 편이 낫습니다.

### 결정 — 빈 패널과 0을 구분한다

에러율·컨슈머 실패 패널은 **실패가 없으면 계열 자체가 사라집니다.** 그러면
"실패 0건"인지 "수집이 끊겼는지" 화면만 보고는 알 수 없습니다.

```promql
sum by (service) (rate(...{result="failure"}[1m]))
  or sum by (service) (rate(...[1m])) * 0      ← 0을 명시적으로 그린다
```

Step 1에서 k6 요약에 "0건인 카운터를 `—`로 찍지 않는다"고 고친 것과 같은 원칙입니다.
Kafka lag의 `NaN`(끊긴 선)은 반대로 **살려뒀습니다** — 그건 그 컨슈머가 한가하다는 뜻이라
0으로 덮으면 거짓말이 됩니다. 대신 패널 설명에 적어뒀습니다.

### 검증 — 대시보드의 모든 쿼리를 실제로 돌렸다

패널을 눈으로 보는 것으로는 부족해서, 대시보드 JSON에서 쿼리를 뽑아 Prometheus API에
직접 던지는 스크립트로 확인했습니다. 부하(k6 smoke)를 흘린 뒤 **13개 쿼리 전부 값을 냈습니다.**

| | |
|---|---|
| 수집 대상 | 6개 전부 up (5개 서비스 + Prometheus 자기 자신) |
| 빈 결과 쿼리 | **0개** (처음엔 6개였다) |
| 버킷 | `http.server.requests` 24개 계열, `spring.kafka.listener`·`hikaricp.connections.acquire`도 확인 |

테스트는 `MetricsExposureTest`(transfer, 5건)로 고정했습니다.

| 테스트 | 확인하는 것 |
|---|---|
| `분포_설정이_잘못되면_요청이_죽으므로_진짜_포트로_때려본다` | 실제 포트로 GET → 200 (500이면 분포 설정 오류) |
| `대시보드가_쓰는_타이머에는_히스토그램_버킷이_붙는다` (3건) | 세 타이머 이름에 버킷이 실제로 붙는가 |
| `prometheus_엔드포인트가_버킷을_실제로_내보낸다` | 레지스트리가 아니라 **스크랩되는 본문**에 버킷이 있는가 |

`MeterFilter` 빈을 떼고 되돌려 **4건이 빨개지는 것**을 확인했습니다.
`./gradlew test` — **436건 전부 통과** (431 → 436).

### 이번에 안 심은 것 — 공짜로 나오는 게 꽤 있었다

ROADMAP에 "직접 심어야 한다"고 적어둔 것 중 둘은 이미 나오고 있었습니다.

| 항목 | 결론 |
|---|---|
| HikariCP 커넥션 사용률·대기 | ✅ `hikaricp_connections_*` 그대로 있음 (버킷만 켜면 됨) |
| Kafka consumer lag | ✅ `kafka_consumer_fetch_manager_records_lag_max` |
| Outbox 적체 · 락 대기·실패 · 낙관적 락 충돌 · DLT 적재 | 🔴 없음 — 직접 심어야 한다 |

**먼저 무엇이 있는지 보고 나서 심는 편이 낫습니다.** 있는 걸 다시 만들 뻔했습니다.

### 남은 것 (Step 2 후반부)

- **직접 심을 메트릭 4종** — Outbox 미발행 적체, 분산 락 대기·획득 실패, 낙관적 락 충돌,
  **DLT 적재 건수**(e2e에서 로그조차 없다는 걸 확인한 그것)
- **정합성 대사 결과를 메트릭으로** — 지금은 API를 열어봐야 안다
- `spring_kafka_listener_seconds`의 `name` 라벨이 `KafkaListenerEndpointContainer#0-0`이라
  **어느 토픽인지 읽히지 않습니다.** `@KafkaListener(id = ...)`를 주면 라벨이 읽을 수 있게 바뀝니다

---

## Phase 5 Step 2 — 직접 심은 메트릭 (후반부) ✅

**목표**: 공짜로 나오지 않는 것들을 심어, **병목이 어디인지** 말할 수 있게 하기

전반부에서 세운 화면은 접수·컨슈머·자원까지만 보여줍니다. 정작 이 시스템 고유의 병목
(Outbox 릴레이, 계좌 락, 상태 전이 경합)은 아무 데도 안 나옵니다. 다섯 가지를 심었습니다.

| 메트릭 | 무엇을 말해주나 |
|---|---|
| `remittance.outbox.backlog` | 릴레이가 부하를 못 따라가는가 |
| `remittance.lock.wait{outcome}` | 계좌 락을 얼마나 기다리는가 / 못 잡고 포기했는가 |
| `remittance.optimistic.lock.conflict{entity,outcome}` | 락이 막지 못한 경합이 있는가 |
| `remittance.kafka.dlt.published{topic}` | 죽어서 사람을 기다리는 메시지가 있는가 |
| `remittance.reconciliation.*` | 대사가 무엇을 찾았나 — 그리고 **돌긴 했나** |

### 결정 — 실패 횟수를 별도 카운터로 두지 않는다

락 획득 실패는 `remittance.lock.wait{outcome="timeout"}` 타이머의 count가 그대로 답합니다.
카운터를 따로 두면 **둘이 어긋났을 때 어느 쪽이 맞는지 알 수 없습니다.**
같은 사건을 두 곳에서 세는 건 지표를 늘리는 게 아니라 신뢰를 나누는 일입니다.

### 결정 — 대사에서 가장 중요한 값은 발견 건수가 아니라 경과 시간

`remittance.reconciliation.last.run.age.seconds`가 이 묶음의 핵심입니다.
발견 건수만 보면 **"어긋난 게 없었다"와 "대사가 아예 안 돌았다"가 똑같이 0**입니다.
배치가 죽은 걸 깨끗하다고 오해하는 게 어긋남 자체보다 위험합니다 —
`ReconciliationRun`을 회차로 남긴 이유와 같은 이야기입니다.

한 번도 안 돌았으면 0이 아니라 **NaN**을 냅니다. 0을 내면 "방금 돌았다"는 거짓말이 되고,
그게 이 지표가 막으려던 오해 그 자체입니다.

### 결정 — 게이지 안에서 예외를 삼킨다

Outbox 적체는 스크랩할 때마다 `COUNT` 한 번을 던집니다. 그 쿼리가 실패하면 예외를 밖으로
내보내지 않고 NaN을 냅니다. **스크랩 하나가 통째로 실패하면 JVM·커넥션 풀·컨슈머 지표까지
전부 사라지기 때문**입니다. DB가 죽은 순간이 바로 그 지표들이 가장 필요한 때인데,
Outbox 하나 때문에 다 잃는 건 손해가 큽니다.

반대로 대사 지표는 스크랩할 때 DB를 **안 읽습니다.** 회차가 끝날 때 갱신해 메모리에 둡니다.
대신 재기동하면 값이 비므로 기동 시 마지막 회차를 한 번 읽어 채웁니다 —
그러지 않으면 재기동 직후가 "대사가 한 번도 안 돌았다"처럼 보입니다.

### 겪은 문제 — 평소에 0인 지표는 사전에 만들어 둬야 한다

대시보드 쿼리를 전부 돌려보니 **낙관적 락 충돌만 빈 결과**였습니다. 버그가 아니라
카운터의 성질입니다 — **카운터는 처음 증가할 때 생깁니다.** 충돌이 한 번도 없으면
시계열 자체가 없고, 화면에서는 "충돌 0건"과 "수집이 안 됨"이 똑같이 빈 칸입니다.

처음엔 쿼리에서 `or ... * 0`으로 메우려 했는데, 없는 메트릭은 fallback도 못 만듭니다.
**소스에서 고쳤습니다** — 기동 시 `retried`·`exhausted` 카운터를 미리 만들어 둡니다.
이 지표는 평소에 0인 게 정상이라, **0을 그릴 수 있어야 값어치가 있습니다.**

### 검증 — DLT 로그가 이제 실제로 남는다

e2e에서 "DLT로 가는데 WARN·ERROR가 0건"이라고 적었던 그 자리를 다시 밟았습니다.
본문이 깨진 메시지를 넣으니 이번에는 남습니다.

```
WARN c.r.n.config.KafkaErrorHandlingConfig : 메시지를 DLT로 보낸다 - 사람이 봐야 한다
  (topic=transfer.completed, partition=1, offset=409, key=poison-metrics, reason=...)
```

카운터도 함께 올라갔습니다 (`remittance_kafka_dlt_published_total{topic="transfer.completed"} 1`).
**로그는 사람이 볼 때만 보이지만 카운터는 그래프에서 튑니다.** 둘 다 필요합니다.

### 검증 — 화면의 모든 쿼리와, 되돌리기

부하(k6 `hot-account` smoke)를 흘린 뒤 대시보드 JSON에서 쿼리를 뽑아 전부 던졌습니다.
**23개 쿼리 전부 값을 냅니다.**

| 확인한 값 (실측) | |
|---|---|
| `remittance_lock_wait_seconds_count` | acquired 346건 / timeout 0건 |
| `remittance_reconciliation_findings` | BALANCE_MISMATCH 17 · UNSETTLED_TRANSFER 4 · STRANDED_KEY 3 |
| `remittance_reconciliation_last_run_age_seconds` | 44.5초 (주기 60초이므로 정상 톱니) |
| `remittance_outbox_backlog` | account 0 · transfer 0 (릴레이가 따라가고 있다) |

테스트는 9건 늘었습니다 — **445건 전부 통과** (436 → 445).

| 테스트 | 확인하는 것 |
|---|---|
| `OutboxBacklogMetricsTest` (2건) | 미발행이 쌓이면 오르고, 발행되면 내려간다 |
| `DistributedLockTest` (+2건) | 기다린 시간을 재고, 못 잡고 포기한 것도 센다 |
| `AccountServiceTest`·`TransferServiceTest` (+4건 상당) | 충돌을 `retried`/`exhausted`로 갈라 센다 |
| `ReconciliationMetricsTest` (5건) | 유형별 분리 · 지난 회차가 안 샌다 · 실패 표시 · 경과 시간 NaN/재개 |
| `KafkaErrorHandlingTest` (+1 검증) | DLT로 갈 때 카운터가 오른다 |

### 남은 것

- **Step 3 baseline 측정** — 이제 잴 수단과 볼 수단이 다 있습니다. ROADMAP의 네 가지 가설
  (송금 TPS 50 근처, 같은 계좌 상한, 락 대기 3초 초과 시 대량 실패, 커넥션 10개 고갈)을
  숫자로 확인할 차례입니다
- `spring_kafka_listener_seconds`의 `name` 라벨이 아직 `KafkaListenerEndpointContainer#0-0`이라
  어느 토픽인지 안 읽힙니다 (`@KafkaListener(id = ...)`로 고칠 수 있음)
- 알림(Alertmanager)은 아직 없습니다. 지금은 **화면을 열어야** 보입니다

---

## Phase 5 Step 3 — baseline 측정 ★ ✅

**목표**: 지금 시스템의 천장을 숫자로 박아두기. 이후 모든 개선은 이 값과 비교된다.

### 측정 조건

| | |
|---|---|
| 커밋 | `28b7a15` (측정 전 `/actuator/info`로 다섯 서비스 전부 확인) |
| 환경 | 로컬 노트북 12코어. **서비스 5개 · 인프라 · k6가 같은 머신** |
| 데이터 | 시나리오마다 MySQL·Mongo·Kafka 볼륨을 **새로 만들어** 같은 출발선에서 시작 |
| 부하 | `load` 50→100→200→400 req/s (각 1분) + `prober` 1 req/s |

### 결과

**시나리오 A — spread (계좌를 넓게 흩음)**

| | 값 |
|---|---|
| 접수 처리량 | 평균 180 req/s, **최대 298 req/s** (목표 400에 못 미침) |
| 접수 지연 | p95 1,631ms · p99 2,839ms (임계 200ms를 크게 넘김) |
| 접수 실패율 | 0.00 |
| **종결 처리량 (지속)** | **초당 12~14건** |
| 종결 지연 | p95 52.1초 · p99 58.0초 · 성공률 0.36 · 시간초과 114건 |
| Outbox 적체 최대 | account 46,012 · transfer 17,423 (**합 약 55,000**) |
| HikariCP | transfer **pending 최대 193** · 획득 대기 p99 4.3초 |
| 락 대기 / 실패 | p99 0.00초 / 0건 |
| consumer lag 최대 | account 815 · ledger 401 |
| DLT | 0건 |

**시나리오 B — hot-account (받는 계좌 하나로 고정)**

| | 값 |
|---|---|
| 접수 처리량 | 평균 191 req/s, 최대 325 req/s |
| 접수 지연 | p95 26ms · p99 100ms — **A보다 오히려 좋다** |
| 종결 처리량 (지속) | 초당 12건 (A와 같음) |
| 종결 지연 | p95 49.2초 · 성공률 0.35 · 시간초과 114건 |
| **락 획득** | 29,060회 · **평균 대기 0.3ms · 최대 22ms · 실패 0건** |
| Outbox 적체 최대 | account 46,032 |

**시나리오 C — read-heavy (원장 조회 폭주)**

| | 값 |
|---|---|
| 조회 처리량 | 평균 211 req/s, 최대 268 req/s |
| 조회 지연 | p95 3,435ms · p99 4,324ms (임계 300ms 초과) |
| ledger 프로세스 CPU | **4.9%** |
| 호스트 CPU | **91%** |

### 가설 검증 — 넷 중 하나만 맞았다

| ROADMAP의 가설 | 결과 |
|---|---|
| 송금 TPS가 **50 근처**에서 막힌다 | 🔴 **틀렸다.** 실제로는 **12~14건/s** — 예상의 1/4 |
| 같은 계좌는 **초당 수십~수백**이 상한 | ⬜ **관측되지 않았다.** 락 대기 최대 22ms |
| 락 대기 3초를 넘기면 **대량 실패** | ⬜ **관측되지 않았다.** 실패 0건 |
| 커넥션 10개가 **금방 고갈** | ✅ **맞았다.** transfer pending 193, 획득 대기 p99 4.3초 |

### 가장 중요한 발견 — 핫 계좌 병목은 파이프라인 뒤에 가려 보이지 않는다

핫 계좌(B)를 400 req/s로 때렸는데 **락 경합이 사실상 없었습니다**(평균 대기 0.3ms).
락이 튼튼해서가 아닙니다. **그 앞에서 이미 막혀 부하가 락까지 닿지 않기 때문**입니다.

```
접수 300/s  ──▶  Outbox 릴레이  ──▶  account 컨슈머  ──▶  계좌 락
   (여기까지 빠름)      ↑ 여기서 막힌다        약 12건/s만 도착      한가하다
```

접수는 초당 300건을 받아 넘기는데 릴레이가 그걸 못 내보내서 5만 5천 건이 쌓이고,
account 컨슈머에는 초당 12건만 도착합니다. **같은 계좌라도 초당 12건이면 경합이 날 수 없습니다.**

> **ROADMAP의 Phase 6 순서를 바꿔야 합니다.** "Step 1 핫 계좌가 이 Phase의 본론"이라고
> 적어뒀지만, 지금 손대면 **효과를 측정할 수 없습니다** — 개선해도 숫자가 안 움직입니다.
> 파이프라인(릴레이·커넥션 풀)을 먼저 뚫어 부하가 락까지 닿게 만든 뒤에 핫 계좌가 본론이 됩니다.
> **이게 "먼저 재고 나서 고친다"가 실제로 값을 한 지점입니다.** 측정 없이 갔으면
> 핫 계좌를 몇 주 고치고도 아무 변화가 없는 걸 보고 있었을 것입니다.

### 두 번째 발견 — 접수가 느려지는 이유는 락이 아니라 커넥션 풀

A에서 접수 p99가 2.8초까지 늘었는데 **HikariCP pending이 193**이었습니다.
커넥션 10개를 두고 193개 요청이 줄을 서 있었다는 뜻입니다. 접수는 `INSERT` 두 번이 전부인데도
느려지는 건 일이 무거워서가 아니라 **자리가 없어서**입니다.

B에서 접수가 오히려 빨랐던 것(p99 100ms)도 같은 이유로 설명됩니다 — B는 받는 계좌가 하나라
`prober`가 만드는 조회 부하가 A보다 가벼웠고, pending도 59에 그쳤습니다.

### 시나리오 C는 원장의 천장이 아니다

조회 p99가 8초까지 갔지만 **ledger 프로세스의 CPU는 4.9%**였고 **호스트 CPU가 91%**였습니다.
원장이 느린 게 아니라 **k6(VU 800개)와 JVM 다섯 개가 같은 노트북에서 CPU를 두고 싸운** 결과입니다.
`load-test/README.md`의 "클라이언트가 먼저 죽음 — 앱은 한가한데 지연이 큼"이 정확히 이 신호입니다.

**그래서 C는 baseline으로 쓰지 않습니다.** (아래에서 부하 생성기를 제한해 다시 쟀습니다.)

### A·B는 CPU 경쟁 때문이 아니다 — 확인했다

C가 호스트 CPU 91%에서 나온 값이라면, **A·B도 같은 의심을 받아야 합니다.** 확인했습니다.

| 구간 | 호스트 CPU | 그때 접수 TPS |
|---|---|---|
| A (spread) | 38~44% | 265 |
| B (hot-account) | 29~50% | 266 |
| C (read-heavy) | 51~66% (순간 91%) | — |

**A·B는 CPU에 여유가 있는 상태에서 막혔습니다.** 297 req/s를 받으면서도 42%였습니다.
즉 12건/s라는 천장은 노트북이 힘들어서 나온 숫자가 아니라 **시스템의 실제 성질**입니다.

> 이 확인이 없으면 baseline 전체가 "노트북 탓 아니냐"는 한 마디에 무너집니다.
> **측정값을 기록할 때는 그 값을 의심하는 방법도 함께 남겨야 합니다.**

### 부하 생성기를 굶겼더니 오히려 빨라졌다 — C 재측정

C를 `--cpus="4"`로 k6를 제한하고 같은 조건에서 다시 쟀습니다.

| | 제한 없음 | `--cpus="4"` |
|---|---|---|
| 처리량 | 211 req/s | **346 req/s** (+64%) |
| p95 | 3,435 ms | **1,516 ms** (−56%) |
| p99 | 4,324 ms | **1,608 ms** (−63%) |
| 서버 쪽 조회 TPS 최대 | 268 | **584** |

**부하를 거는 쪽을 제한했더니 처리량이 64% 올랐습니다.** 그전엔 k6가 CPU를 가져가
정작 측정 대상이 일을 못 하고 있었던 것입니다.

> `--cpuset-cpus`(코어 고정)는 macOS에서 기대만큼 듣지 않습니다. 컨테이너는 Docker Desktop의
> 리눅스 VM 안에서 돌고 **그 VM의 vCPU를 다시 하이퍼바이저가 호스트 코어에 스케줄**합니다.
> 실제로 듣는 손잡이는 총량 제한(`--cpus`)입니다.

**그래도 C는 여전히 원장의 천장이 아닙니다.** 재측정에서도 ledger 프로세스 CPU는 **4.1%**인데
호스트 CPU는 **89%**였습니다. 원장은 리액티브 스택을 제대로 쓰고 있고
(`spring-boot-starter-data-mongodb-reactive` + `Mono` 반환 — 블로킹 드라이버를 이벤트 루프에
올린 게 아닙니다), 남은 병목은 원장 바깥의 공유 자원입니다.

**"WebFlux + MongoDB가 값을 하는가"에 답하려면 부하 생성기를 다른 머신으로 빼야 합니다**
(Phase 11 소재). 다만 이제 그 전까지 쓸 수 있는 **비교 가능한 값**은 생겼습니다.

### 겪은 문제 — 방금 만든 코드가 아니라 낡은 jar를 쟀다

1차 측정을 마치고 서버 지표를 보니 리스너 라벨이 여전히 `KafkaListenerEndpointContainer#2-0`이었습니다.
**리스너 `id`를 붙인 커밋 이전의 jar로 돌고 있었습니다.** 측정값 자체는 비슷했겠지만
라벨이 갈라지면 Phase 6의 재측정과 비교할 수 없어 **전부 버리고 다시 쟀습니다.**

`/actuator/info`에 물어보니 `2229d0f1b1c7`, HEAD는 `28b7a1543546`이었습니다.
**CI 위생에서 build-info를 심어둔 이유가 정확히 이건데 쓰지 않았습니다.**

> 재측정 전에 다섯 서비스의 `/actuator/info`가 전부 HEAD와 같은지 먼저 확인하는 절차를 넣었습니다.
> 앞선 세션에서 "죽은 줄 알았던 프로세스가 답하고 있던" 일과 같은 종류입니다 —
> **떠 있는 것이 내가 만든 것인지 확인하지 않으면, 무엇을 쟀는지 알 수 없습니다.**

### 남은 것

- **Phase 6은 파이프라인부터.** 릴레이 처리량(폴링 500ms × 100건)과 transfer 커넥션 풀이
  첫 두 표적입니다. 핫 계좌는 그 뒤에 본론이 됩니다
- C를 제대로 재려면 **부하 생성기를 분리**해야 합니다
- 종결 시간초과가 114건인데 **DLT는 0건**입니다 — 유실이 아니라 밀려 있을 뿐이라는 뜻이고,
  실제로 부하를 멈추면 계속 처리됩니다. 다만 사용자 입장에서 52초는 실패나 다름없습니다

---

## baseline 재측정 — 측정 전용 홈서버에서 ✅

**목표**: 노트북에서 잰 값이 "노트북을 잰 것"인지 가리고, 앞으로의 기준을 확정하기

노트북 baseline에는 알려진 오염이 있었습니다. 부하 생성기와 측정 대상이 같은 CPU를 두고
싸웠고, 시나리오 C는 그 때문에 통째로 버려야 했습니다. 측정 전용 리눅스 머신
(Ryzen 5 3600 6코어/12스레드, 32GB — 절차는 `HOMELAB.md`)으로 옮겨 다시 쟀습니다.

### 측정 조건

| | |
|---|---|
| 커밋 | `b6d78af` (다섯 서비스의 `/actuator/info`로 확인) |
| 서비스 | **0~9번 코어**에 고정, 힙은 노트북과 동일 |
| 부하 | **서버 안에서** k6, **10~11번 코어**에 고정 |
| 데이터 | 시나리오마다 MySQL·Mongo·Kafka 볼륨을 새로 생성 |

**부하를 노트북이 아니라 서버 안에서 걸었습니다.** 노트북은 WiFi로 붙어 있어 RTT가
접수 지연에 그대로 더해지는데, 그 접수 지연(커넥션 풀 고갈)이 baseline의 핵심 발견
중 하나였기 때문입니다. 리눅스에서는 코어 고정이 실제로 들어 자원 분리가 가능합니다.

### 결과

| | A: spread | B: hot-account | C: read-heavy |
|---|---|---|---|
| 접수 처리량 (평균/최대) | 99.6 / **111** req/s | 99.5 / 115 | — |
| 접수 p95 / p99 | 7,086 / 8,930 ms | 7,105 / 8,929 | — |
| **종결 처리량 (지속)** | **19건/s** | **19건/s** | — |
| 종결 p95 · 성공률 | 52.2초 · 0.36 | 49.1초 · 0.35 | — |
| Outbox 적체 최대 | tr 8.5k · ac 8.6k | tr 8.3k · ac 13.1k | — |
| HikariCP pending | **193** | **193** | — |
| 커넥션 대기 p99 | 4.87초 | 4.86초 | — |
| 락 대기 | p99 0.06초 | **평균 0.4ms · 최대 51ms · 실패 0** | — |
| 낙관적 충돌(retried) | 550 | 116 | — |
| 조회 처리량 / p99 | — | — | **880 req/s · p99 9ms** |
| ledger 프로세스 CPU | — | — | **9.2%** |
| 호스트 CPU | 42% | 45% | 42% |
| DLT | 0 | 0 | 0 |

### 노트북과 무엇이 달랐나

| | 노트북 | 홈서버 |
|---|---|---|
| 접수 최대 | 298 req/s | **111 req/s** |
| **종결 처리량** | 12~14건/s | **19건/s** |
| 조회 p99 (C) | 4,324 ms | **1.8 ms** |

**세 숫자가 각각 다른 것을 말합니다.**

### ① 종결 처리량이 올랐다 — 노트북 값은 오염되어 있었다

12~14건/s → **19건/s**. 코드는 그대로인데 40% 올랐습니다.
노트북에서는 k6가 서비스와 CPU를 나눠 쓰면서 파이프라인이 함께 느려지고 있었습니다.
**부하 생성기를 격리한 것만으로 측정 대상이 제 속도를 냈습니다** —
시나리오 C에서 `--cpus`로 k6를 묶었더니 처리량이 211→346으로 오른 것과 같은 현상입니다.

**앞으로의 기준은 19건/s입니다.**

### ② 접수는 홈서버가 오히려 3배 느리다 — 그리고 그게 병목의 정체를 알려준다

접수 최대가 298 → 111 req/s로 떨어졌습니다. Ryzen 5 3600의 단일코어 성능이
M 시리즈보다 낮아서인데, **여기서 더 중요한 게 드러났습니다.**

**두 머신 모두 HikariCP pending이 정확히 193이었습니다.**

Tomcat 기본 최대 스레드가 200입니다. 즉 **요청 스레드 200개 중 193개가 커넥션 10개를
기다리며 줄을 서 있는 상태**입니다. 접수는 `INSERT` 두 번이 전부인데도 느린 이유가
일이 무거워서가 아니라 **자리가 없어서**라는 게 두 머신에서 같은 숫자로 확인됐습니다.

그래서 접수 처리량은 `커넥션 10개 ÷ 커넥션을 쥐고 있는 시간`으로 정해지고,
CPU가 느릴수록 쥐고 있는 시간이 길어져 그대로 처리량이 됩니다.
**Phase 6의 첫 표적이 커넥션 풀인 근거가 하나 더 생겼습니다.**

### ③ 시나리오 C는 노트북에서 1000배 틀린 값을 내고 있었다

조회 p99가 **4,324ms → 1.8ms**입니다. 오차가 아니라 **다른 것을 재고 있었습니다.**

노트북에서는 k6 컨테이너가 `host.docker.internal`을 통해 Docker Desktop의 리눅스 VM을
거쳐 호스트로 나갔습니다. 홈서버에서는 `--network host`라 그 경유가 없습니다.
CPU 경쟁만의 문제가 아니라 **macOS Docker의 네트워크 경로가 지배적이었던 것**입니다.

> 앞서 "`--cpus`로 묶었더니 211→346이 됐다"를 CPU 문제로만 해석했는데,
> **절반만 맞았습니다.** 더 큰 원인은 네트워크 경유였습니다.

**그리고 이번에도 원장의 천장은 못 찾았습니다 — 이번엔 이유가 반대입니다.**
원장이 880 req/s를 p99 9ms로, **CPU 9.2%만 쓰고** 처리했습니다.
k6에 코어를 2개→4개로 늘려봐도 처리량이 400.6→402.8로 그대로였습니다.
**k6가 한계가 아니라, 시나리오가 요구한 부하를 서버가 여유롭게 소화한 것**입니다.

`read-heavy.js`의 부하 수준(200→500→1000 req/s)은 **노트북에서 왜곡된 숫자를 보고
잡은 값**이었습니다. 원장을 실제로 괴롭히려면 훨씬 높여야 합니다.

### ④ 핫 계좌 진단은 그대로 유지된다

B에서 락 획득 16,423회, **평균 대기 0.4ms · 최대 51ms · 실패 0건**.
노트북에서와 같은 결론입니다 — **락이 튼튼해서가 아니라 앞에서 막혀 부하가 락까지
닿지 않습니다.** 환경을 바꿔도 같은 결과가 나왔으므로 진단이 환경 탓이 아님이 확인됐습니다.

### 정정 — 힙은 줄이지 않았다

메모리가 15GB이던 시절 "account·transfer 힙을 1g→512m로 줄이자"고 적었지만,
32GB가 되면서 **줄일 이유(메모리 부족)가 사라졌습니다.** 힙을 바꾸면 GC 동작이 달라져
baseline에 변수를 하나 더 넣게 되므로 **그대로 뒀습니다.**

### 남은 것

- **`read-heavy.js`의 부하 수준을 올려야 합니다.** 지금 값으로는 원장이 놀고 있어
  "WebFlux + MongoDB가 값을 하는가"에 답할 수 없습니다
- Phase 6의 표적 순서는 그대로입니다 — **커넥션 풀 → 릴레이**. pending 193이
  두 머신에서 재현되면서 근거가 더 단단해졌습니다

---

## Phase 6 Step 2 — ① 커넥션 풀 (10 → 30) ✅

**목표**: baseline이 지목한 첫 병목을 뚫고, **예상한 대로 되는지** 확인하기

### 고치기 전에 가설을 적었다

| 지표 | baseline | 예상 |
|---|---|---|
| 접수 지연 p99 | 8.9초 | 크게 개선 |
| 접수 처리량 | 111 req/s | 오름 |
| **종결 처리량** | **19건/s** | **거의 그대로** |
| Outbox 적체 | 8.5k | **오히려 늘어남** |

근거: **접수와 종결의 병목이 다르다.** 커넥션을 늘리면 접수는 빨라지지만
그만큼 Outbox에 더 빨리 쌓일 뿐이고, 종결 속도는 릴레이가 정한다.

### 왜 30인가 — 접수 한 건은 커넥션을 세 번 잡는다

```
① 키 선점(IN_PROGRESS)  ②  송금 저장 + transfer.requested  ③ 키에 결과 기록(COMPLETED)
   각각 다른 트랜잭션 = 각각 커넥션을 한 번씩
```

Step 6b가 **크래시 지점을 구분하려고** 나눈 구조라 합칠 수 없습니다.
그래서 접수 111 req/s는 커넥션 입장에서 **초당 333 트랜잭션**이었고, 10개가 그걸 처리했으니
트랜잭션 하나가 커넥션을 약 **30ms** 쥡니다. 같은 점유 시간이면 30개로 초당 약 1,000건,
즉 접수 300 req/s 언저리가 계산됩니다.

**`transfer`만 바꿨습니다.** account·reconciliation·notification은 pending이 0이었습니다.
한 번에 하나씩 바꿔야 무엇이 효과를 냈는지 알 수 있습니다.

### 결과 — 예상대로였다

| 지표 | before | after | |
|---|---|---|---|
| 접수 처리량 (k6) | 99.6 req/s | **165.1** | **+66%** |
| 접수 처리량 (서버 최대) | 111 | **273** | **+146%** |
| 접수 p95 | 7,086 ms | **2,524** | **−64%** |
| 접수 p99 | 8,930 ms | **3,495** | **−61%** |
| **종결 처리량 (지속)** | **19건/s** | **19건/s** | **그대로** |
| 종결 p95 | 52.2초 | 53.4초 | 그대로 |
| Outbox 적체 (transfer) | 8.5k | **17.2k** | **2배** |
| HikariCP pending | 193 | 173 | 여전히 대기 |

**네 가지 예상이 전부 맞았습니다.** 접수는 크게 나아졌고, **종결은 1건도 안 움직였으며**,
적체는 오히려 두 배가 됐습니다.

### 이게 이 Phase의 교훈 그 자체다

> **병목 하나를 뚫으면 처리량이 오르는 게 아니라 다음 병목이 드러납니다.**

접수를 두 배 이상 빠르게 만들었지만 **시스템이 실제로 처리하는 송금은 초당 19건 그대로**입니다.
빨라진 접수는 그저 **Outbox에 더 빨리 쌓았을 뿐**입니다(8.5k → 17.2k).

사용자 입장에서 나아진 것은 있습니다 — **202를 8.9초 만에 받던 것이 3.5초가 됐습니다.**
하지만 그 뒤 종결까지는 여전히 52초입니다. **"접수가 빨라졌다"를 "시스템이 빨라졌다"로
읽으면 안 됩니다.**

### 아직 pending이 173이다 — 풀은 여전히 모자라다

풀을 3배로 키웠는데 대기가 193 → 173으로 조금 줄었을 뿐입니다.
**수요가 여전히 공급을 넘습니다** (부하 목표 400 req/s에 273까지 도달).

더 키울 수도 있지만 **지금은 의미가 없습니다.** 접수를 더 빠르게 해봐야
종결이 19건/s인 이상 적체만 더 쌓입니다. **다음 표적은 릴레이입니다.**

> 커넥션을 더 늘리는 건 릴레이를 뚫은 뒤에 다시 판단합니다. 커넥션은 결국 MySQL의
> 동시 실행 수라, 무작정 키우면 경합이 DB 안으로 옮겨갈 뿐입니다.

### 부수적으로 관찰된 것

- `account`의 Outbox 적체는 오히려 **줄었습니다**(8.6k → 5.3k). account는 컨슈머가
  받는 속도에 묶여 있어 접수가 빨라진 것과 무관하고, transfer 쪽 적체만 커졌습니다
- 낙관적 락 충돌(retried)이 550 → 218로 줄었습니다. 접수가 빨라지며 이벤트 분포가 달라진
  영향으로 보이는데, **이번 변경의 목표가 아니라 단정하지 않습니다**
- 락 대기·DLT는 변화 없음 (p99 0.06초, DLT 0건)

### 다음

**릴레이 처리량** — 500ms 폴링 × 100건에 건마다 `send().join()`으로 직렬입니다.
19건/s라는 진짜 천장이 거기서 정해집니다.

---

## Phase 6 Step 2 — ② 릴레이가 건마다 기다리지 않게 (19 → 35건/s) ✅

**목표**: 종결 처리량의 진짜 천장을 뚫기

### 무엇이 문제였나

```java
for (OutboxEvent event : pending) {
    kafkaTemplate.send(...).join();   // ← 한 건 보내고 응답을 기다린 뒤 다음 것
    event.markPublished();
}
```

100건이면 **브로커와 100번 왕복**합니다. baseline의 19건/s가 여기서 나왔습니다.

**전부 보내고 마지막에 한 번 기다리도록** 바꿨습니다. 프로듀서가 알아서 묶어 보내므로
왕복이 사실상 한 번이 됩니다.

### 순서를 어떻게 지켰나

애그리거트 ID가 키라 같은 송금의 이벤트는 **같은 파티션**으로 갑니다. 그리고 실행 중인
프로듀서 설정을 확인하니 `acks=all` + `enable.idempotence=true`였습니다 —
**재시도가 나도 파티션 안 순서가 뒤바뀌지 않습니다**(어긋나면 브로커가 거절합니다).

남는 창은 있습니다. 앞의 것이 끝내 실패하면 **뒤의 것은 이미 보내진 뒤**라 중복과 순서 어긋남이
생길 수 있습니다. 다만 둘 다 이 시스템이 이미 견딥니다 — 컨슈머가 멱등하고(§6),
송금 상태 전이가 **단조(monotonic)**라 순서가 뒤바뀌어도 갇히지 않습니다(§4-④).
**Step 4d에서 그걸 만들어둔 게 여기서 값을 했습니다.**

> 배치 하나가 트랜잭션 하나여야 해서 `OutboxBatchPublisher`로 갈랐습니다.
> 같은 빈에서 자기 메서드를 부르면 `@Transactional` 프록시를 안 타는데,
> 이 저장소가 이미 두 번 밟은 함정입니다(`BalanceMutationExecutor`, `NotificationRecorder`).
> **세 번째는 밟기 전에 피했습니다.**

### 결과

| 지표 | 커넥션 풀만 | 릴레이까지 | |
|---|---|---|---|
| **종결 처리량 (지속)** | 19건/s | **35건/s** | **+84%** |
| 종결 성공률 | 0.38 | **0.54** | |
| 종결 p95 | 53.4초 | **38.9초** | **−27%** |
| 시간초과 | 110건 | **83건** | |
| **Outbox 적체 (account)** | 5.3k | **560 → 30 근처** | **거의 사라짐** |
| 접수 처리량 | 165 req/s | 152 | 변화 없음(노이즈) |

**적체가 사라진 게 핵심입니다.** 예전에는 부하가 끝나도 계속 쌓여 있었는데
(15k~17k), 이제 릴레이가 30초 만에 비웁니다.

### 가설은 방향만 맞고 이유가 틀렸다

바꾸기 전에 이렇게 적었습니다.

> 19건/s에서 오르되 **약 50건/s에서 다시 막힌다.** 폴링 500ms × 배치 100 =
> 초당 200 이벤트가 구조적 상한이고 송금 1건이 account에서 이벤트 4개를 만든다.

**오르긴 했지만 35건/s에서 막혔고, 이유는 예상한 폴링 상한이 아니었습니다.**
적체가 0 근처라는 건 **릴레이가 더 이상 상한이 아니라는 뜻**입니다.
계산해둔 천장에 닿기도 전에 다른 것이 먼저 막혔습니다.

### 다음 병목이 어디인지는 지표가 바로 알려줬다

| | |
|---|---|
| `transfer.requested` consumer lag | **파티션당 4,000~5,000 = 약 13,800건** |
| 송금 상태 `PENDING` | **13,440건** (lag과 일치) |
| `transfer.requested-0` 리스너 처리량 최대 | **52건/s** |

**account-service가 `transfer.requested`를 못 따라갑니다.** 원인은 짐작이 갑니다 —
**리스너 스레드가 1개**입니다. 파티션은 3개인데 한 스레드가 순차로 처리하고 있습니다.

**다음 표적은 `concurrency`입니다.** 파티션이 3개이므로 스레드를 3개까지 늘릴 수 있고,
그 이상은 파티션 수를 먼저 늘려야 합니다.

### 여전히 남아 있는 것

- **접수 쪽 `pending`이 173** — 커넥션 풀은 아직 모자랍니다. 다만 종결이 35건/s인 이상
  접수를 더 빠르게 해봐야 큐만 길어집니다. **종결을 먼저 올리는 게 맞습니다**
- 락 대기 p99 0.07초, DLT 0건 — 아직 조용합니다

---

## Phase 6 Step 2 — ③ 리스너 `concurrency` (1 → 3) ⚠️ 목표 실패

**목표**: `transfer.requested`에 쌓인 lag을 없애 종결 처리량을 올리기

파티션은 3개인데 리스너 스레드가 1개였습니다. 파티션을 나눈 의미가 없던 셈입니다.

### 무엇이 바뀌었나

| | 이전 | 이후 |
|---|---|---|
| `transfer.requested` 컨슈머 | 1개가 파티션 3개 담당 | **파티션당 1개** |
| `transfer.requested` lag | 밀림 | **0** |
| account Outbox 적체 | — | 0 (당시) |

**account는 완전히 따라잡았습니다.** 의도한 대로 됐습니다.

### 그런데 목표는 실패했습니다

| 지표 | 릴레이까지 | concurrency 3 |
|---|---|---|
| **종결 처리량** | 35건/s | **33건/s** |
| 종결 p95 | 38.9초 | **47.5초** |
| 종결 성공률 | 0.54 | 0.54 |
| 접수 처리량 | 152 req/s | 146 req/s |

**전체 처리량은 안 올랐고 p95는 오히려 나빠졌습니다.** 병목이 account가 아니라
그 다음에 있었기 때문입니다.

> **되돌리지 않았습니다.** account가 실제로 밀리고 있던 것도, 지금 lag이 0인 것도 사실입니다.
> 앞이 뚫리면 값을 하는 변경이라, 남겨두고 다음으로 갔습니다.

### 설정을 yml이 아니라 애노테이션에 둔 이유

`spring.kafka.listener.concurrency`로 줬다면 `src/test/resources/application.yml`이
운영 설정을 가려 **기존 448개 테스트가 전부 스레드 1개로** 돌았을 겁니다. 그러면 이 변경의
진짜 위험(동시 처리에서의 순서·멱등성)을 하나도 밟아 보지 못합니다.
`MetricsDistributionConfig`에서 이미 밟은 함정입니다.

검증은 설정값을 되읽지 않고 **실제로 만들어진 자식 컨테이너 수**를 셉니다.
기본값을 1로 되돌리면 실패하는 것을 확인했습니다.

### 측정 중에 lag을 잘못 읽었습니다

`kafka-consumer-groups --describe` 출력에서 **`$5`(LOG-END-OFFSET)를 LAG으로 읽었습니다.**
그래서 "lag이 7,955 → 27,729로 늘고 있다"고 보고했는데, 그건 lag이 아니라 **발행된 총
이벤트 수**였습니다. 제대로 세니 **lag은 0**이었습니다 — 정반대 결론이었습니다.

`$6`이 LAG입니다. 진단 명령도 검증 대상이라는 걸 잊었습니다.

---

## Phase 6 Step 2 — ④ 원장 인덱스가 선언만 되어 있었다 ✅

**목표**: ledger가 왜 초당 40건에서 멈추는지 밝히기

### 애노테이션은 있는데 인덱스가 없었다

`concurrency`가 왜 소용없었는지 찾다가, MongoDB CPU가 **89%**인 것을 봤습니다.
컬렉션을 직접 열어 보니:

```
ledger_db.transactions   문서 52,188건   인덱스: ["_id_"]   ← 이게 전부
```

`Transaction`에는 `@Indexed`가 **넷이나** 붙어 있습니다 — `transactionId`(unique),
`transferId`, `accountId`, 복합 인덱스까지. 그런데 하나도 만들어지지 않았습니다.
**Spring Data MongoDB는 3.0부터 자동 인덱스 생성이 기본으로 꺼져 있습니다.**
켜 준 적이 없으니 그 애노테이션들은 **전부 장식이었습니다.**

`BalanceChangedConsumer`는 메시지마다 `findByTransferId`를 한 번씩 부릅니다.
인덱스가 없으니 **한 건마다 59,034건짜리 컬렉션을 통째로 훑었습니다.**

> **문서가 쌓일수록 느려지는 구조입니다.** 부하를 오래 걸수록 나빠지는데,
> 테스트에서는 컬렉션이 작아 절대 드러나지 않습니다. 그래서 여기까지 살아남았습니다.

### 속도만의 문제가 아니었습니다

`transactionId`의 unique 인덱스는 **원장에 같은 줄이 두 번 들어가는 것을 막는 장치**입니다.
그게 아예 없었습니다. 확인해 보니 중복은 0건이었지만, **막아주고 있었던 게 아니라
운이 좋았던 것**입니다.

### 고친 방법

정의는 도메인 애노테이션에 그대로 두고 `IndexResolver`가 읽게 했습니다.
명시한 것은 **정의가 아니라 "만든다"는 사실**뿐입니다.

`auto-index-creation: true` 한 줄이 더 짧지만, 테스트용 yml이 운영 yml을 가려
**테스트는 인덱스 없이 돌게 됩니다.** 방금 놓친 것을 또 못 잡는다는 뜻입니다.

검증은 **애노테이션이 아니라 서버에 직접 묻습니다** — 실행 계획이 `IXSCAN`인지,
`unique` 속성이 실제로 붙었는지. 애노테이션을 확인하는 검증이었다면 **원래도 통과했을
겁니다.** 애노테이션은 처음부터 멀쩡했으니까요. 초기화를 끄면 셋 다 실패합니다.

### 결과

| 지표 | concurrency 3 | 인덱스까지 | |
|---|---|---|---|
| **MongoDB CPU** | 89% | **6%** | **−93%** |
| **ledger 리스너 처리량** | 40.6건/s | **87.3건/s** | **2.1배** |
| ledger `account.balance-changed` lag | 9,756 | **49** | 사라짐 |
| ledger 서비스 CPU | — | 7.8% | |
| **종결 처리량** | 33건/s | **32건/s** | **그대로** |

**ledger는 2배 이상 빨라졌는데 전체 처리량은 또 안 움직였습니다.**

### 병목이 릴레이로 돌아왔습니다 — 이번엔 숫자가 맞습니다

| | |
|---|---|
| account Outbox 적체 | **29,746건** |
| **account 릴레이 실측 발행 속도** | **173 이벤트/s** |
| 이론상 상한 (500ms × 100건) | **200 이벤트/s** |

account가 `transfer.requested`를 초당 90건(30 × 3스레드) 처리하며 이벤트를 쏟아내는데,
릴레이는 **초당 200건까지밖에 못 내보냅니다.**

**이건 ②에서 제가 예측했다가 틀렸던 바로 그 천장입니다.** 그때는 "폴링 500ms × 배치 100 =
초당 200 이벤트에서 막힌다"고 썼는데 적체가 0이라 빗나갔습니다. 앞이 막혀 있어 **거기까지
가지도 못했던 것**이고, 두 단계를 뚫고 나서야 그 천장에 닿았습니다.

### 세 번의 변경을 돌아보면

| 변경 | 종결 처리량 |
|---|---|
| baseline | 19건/s |
| ① 커넥션 풀 | 19건/s (그대로) |
| ② 릴레이 배치 | **35건/s** |
| ③ 리스너 concurrency | 33건/s (그대로) |
| ④ 원장 인덱스 | 32건/s (그대로) |

**전체 숫자를 움직인 건 ②뿐입니다.** 나머지 셋은 각자 담당 구간에서는 분명히 효과가
있었지만(접수 2.5배, lag 0, ledger 2.1배), **그 구간이 병목이 아니었습니다.**

> 이게 이번 Phase의 교훈입니다. **구간 지표가 좋아지는 것과 전체가 빨라지는 것은 다릅니다.**
> 그리고 ①③④가 낭비였던 것도 아닙니다 — ④는 **정합성 구멍**이었고,
> ①③은 릴레이를 뚫는 순간 필요해집니다.

### 다음

릴레이의 **배치 크기와 폴링 간격**입니다. 지금 500ms × 100건이고, 이번엔 처음으로
**병목이라는 증거(적체 29,746 · 실측 173/s가 상한 200/s에 붙어 있음)를 손에 쥐고** 갑니다.

---

## Phase 6 Step 2 — ⑤ 릴레이가 적체를 다음 주기까지 미루지 않게 ✅ (그래도 종결은 그대로)

**목표**: 실측이 지목한 폴링 상한(200 이벤트/s)을 없애기

### 무엇을 바꿨나

주기마다 배치를 **딱 하나**만 비우고 있었습니다. 500ms에 100건이면 **초당 200건이
구조적 상한**입니다. 지금은 **배치가 가득 찼으면 이어서 비웁니다** — 가득 찼다는 건
아직 남아 있다는 뜻이고, 남아 있는데 다음 주기까지 노는 건 낭비입니다.

`OutboxBatchPublisher`가 처리 건수를 돌려주도록 만들어 둔 계약을 **이제야 썼습니다.**

> **배치 크기는 100 그대로 뒀습니다.** 500으로 올려도 상한은 5배가 되지만 트랜잭션 하나가
> 그만큼 길어지고 UPDATE도 커집니다. 작은 트랜잭션을 여러 번 도는 편이 락을 짧게 쥡니다.
> **상한을 없애는 데는 어느 쪽이든 되는데, 대가가 다릅니다.**

무한히 돌지는 않습니다(한 주기 최대 20배치). `@Scheduled`는 스케줄러 스레드를 빌려 쓰므로
끝나지 않으면 같은 스케줄러의 다른 일이 굶습니다. **지금 이 서비스들에 다른 예약 작업이
없지만, 없다는 사실에 기대는 코드는 나중에 조용히 깨집니다.**

### 릴레이 천장은 확실히 사라졌습니다

| 지표 | 인덱스까지 | 릴레이 루프까지 | |
|---|---|---|---|
| **account Outbox 적체** | 29,746건 | **100 근처** | **사라짐** |
| 릴레이 발행 속도 | 173 이벤트/s (상한 200) | 상한 없음 | |

### 그런데 종결은 32 → 30건/s

| 지표 | 인덱스까지 | 릴레이 루프까지 |
|---|---|---|
| **종결 처리량** | 32건/s | **30건/s** |
| 종결 p95 | 41.5초 | 48.9초 |
| 종결 성공률 | 0.54 | 0.56 |
| 접수 처리량 | 146 req/s | 142 req/s |

**다섯 번째 변경인데 전체 숫자는 또 안 움직였습니다.**

### 이번엔 다음 관문이 아주 선명합니다

60초 동안 lag이 어떻게 변하는지 쟀습니다.

| 컨슈머 | lag 변화 | |
|---|---|---|
| account `transfer.requested` | 0 → 0 | **놀고 있음** |
| account `transfer.debited` | 0 → 0 | **놀고 있음** |
| ledger `account.balance-changed` | 0 → 0 | **놀고 있음** |
| **transfer `transfer.ledger-recorded`** | 16,255 → 13,755 | **−41/s** |
| **transfer `transfer.credited`** | 13,476 → 9,476 | −66/s |
| **transfer `transfer.debited`** | 10,837 → 8,337 | −41/s |
| notification `transfer.completed` | 8,772 → 11,199 | **+40/s (밀리는 중)** |

**앞의 셋은 할 일이 없어서 놀고 있고, 뒤처리를 transfer-service 혼자 하고 있습니다.**
그리고 **transfer-service의 리스너 세 개가 전부 스레드 1개**입니다.
종결은 `transfer.ledger-recorded`가 처리돼야 나는데, 그게 **초당 41건** — 실측 종결
처리량 30건/s와 같은 자릿수입니다.

> transfer-service CPU는 24%, 커넥션 풀은 이미 30입니다. **자원이 모자란 게 아니라
> 스레드가 하나라 순서대로 하고 있을 뿐입니다.**

notification은 밀리고 있지만 **종결 경로가 아닙니다** — 송금은 이미 COMPLETED가 된 뒤에
알림이 나갑니다. 나중에 따로 봅니다.

### 다음

**transfer-service 리스너 `concurrency`.** ③에서 account에 같은 것을 했다가 목표를
실패했는데, 그때는 **그 구간이 병목이 아니었습니다.** 이번엔 다릅니다 —
막힌 구간의 처리량(41건/s)이 **전체 종결 처리량(30건/s)과 같은 자릿수**입니다.

---

## Phase 6 Step 2 — ⑥ transfer-service 리스너 `concurrency` (1 → 3) ★ 30 → 80건/s ✅

**목표**: 앞단이 놀고 있는데 혼자 뒤처리하던 구간을 뚫기

### 이번엔 왜 여기라고 봤나

③에서 account에 같은 것을 했다가 목표를 실패했습니다. 차이는 하나였습니다.

| | account (③) | transfer (⑥) |
|---|---|---|
| 그 구간 처리량 | 90건/s | **41건/s** |
| 전체 종결 처리량 | 32건/s | **30건/s** |
| | **자릿수가 다르다** → 병목이 아니다 | **같은 자릿수다** → 병목이다 |

**막힌 구간의 처리량이 전체 숫자와 같은 자릿수인지**가 판단 기준이었습니다.

### 결과

| 지표 | 릴레이 루프까지 | **concurrency 3** | |
|---|---|---|---|
| **종결 처리량** | 30건/s | **80건/s** | **2.7배** |
| **종결 성공률** | 0.56 | **0.76** | |
| **종결 p95** | 48.9초 | **32.8초** | **−33%** |
| 종결 p99 | 50.6초 | 42.4초 | |
| 시간초과 | 79건 | **46건** | |
| 접수 처리량 | 142 req/s | 129 req/s | 조금 내려감 |

**baseline 19건/s에서 4.2배입니다.**

### 파이프라인이 처음으로 전부 비워졌습니다

부하가 끝난 뒤 60초 동안:

| 컨슈머 | lag |
|---|---|
| account `transfer.requested` | 3,507 → **0** |
| account `transfer.debited` | 383 → **0** |
| ledger `account.balance-changed` | 9 → **0** |
| transfer `transfer.credited` | 2,218 → **0** |
| transfer `transfer.debited` | 2,443 → **0** |
| transfer `transfer.ledger-recorded` | 3,001 → 1,052 |
| **notification `transfer.completed`** | 19,825 → **24,612** | **혼자 밀린다** |

CPU도 전부 한산합니다 — MySQL 20%, transfer 9.7%, 나머지는 한 자릿수.
**이제 자원이 남습니다.**

### 순서는 왜 안 깨지나

스레드 하나가 파티션 하나를 맡고 같은 송금은 키가 같아 같은 파티션으로 가므로,
**한 리스너 안에서는** 여전히 한 스레드가 순서대로 처리합니다.

**리스너끼리는 원래부터 별개 스레드였습니다** — `credited`와 `ledger-recorded`가 같은 송금 행을
동시에 건드리는 일은 스레드가 1개일 때도 있었습니다. 즉 **이 변경으로 새로운 종류의 경합이
생기지 않습니다.** 낙관적 락과 단조 상태 전이(§4-④)가 이미 막고 있던 것입니다.

### 테스트가 취약했던 것도 함께 고쳤습니다

`OutboxRelayDrainTest`가 "정확히 250건이 쌓여 있어야 한다"를 사전조건으로 걸고 있었는데,
기동 직후 스케줄러가 한 번 돌며 1건을 먼저 가져가 249가 됐습니다.
이 검증에 필요한 사전조건은 **"한 배치보다 많이 쌓여 있다"**뿐이라 그렇게 고쳤습니다.
**단독으로 돌 때는 통과하고 전체를 함께 돌려서야 드러났습니다.**

### 다음 — notification

종결 경로 밖이지만 이제 **여기만 밀립니다**. 처리 **8.7건/s**에 도착 **79건/s**라
lag이 24,612까지 쌓였고, 이 속도면 **비우는 데 50분**이 걸립니다.
실제 서비스라면 "송금은 끝났는데 알림이 50분 뒤에 오는" 상태입니다.

원인은 짐작이 갑니다 — 메시지 하나가 **커밋 4번**입니다(수신자 2명 × `claim` + `markSent`).
메시지당 p95가 **247ms**입니다. 스레드를 늘리는 것만으로는 26건/s라 모자라고,
**커밋 수 자체를 줄여야** 합니다.

---

## Phase 6 Step 2 — ⑦ notification 리스너 `concurrency` (1 → 3) — 18 → 75건/s ✅

**목표**: 종결 경로가 다 비워진 뒤에도 혼자 밀리던 알림을 따라잡게 하기

### 먼저 숫자를 다시 쟀습니다

⑥에서 "notification 8.7건/s"라고 적었는데, **그건 정산 파이프라인이 MySQL을 붙잡고
있을 때 잰 값**이었습니다. 종결 경로가 전부 비워진 뒤 혼자 돌 때 다시 재니 **18건/s**였습니다.

**경합 중에 잰 값을 그 컴포넌트의 성능이라고 부르면 안 됩니다.** 고치기 전에 다시 잰 게
다행이었습니다 — 안 그랬으면 잘못된 출발선과 비교할 뻔했습니다.

### 무엇이 느렸나 — 자원이 아니라 순서였습니다

| | |
|---|---|
| MySQL CPU | **8.7%** |
| notification CPU | **3.3%** |
| 메시지당 | 커밋 **4번** (수신자 2명 × `claim` + `markSent`) |
| 커밋 1번 | 약 14ms |
| **메시지 하나** | **55ms → 18건/s** |

**아무것도 포화가 아니었습니다.** 스레드 하나가 커밋 네 번을 **순차로 기다린** 결과입니다.

### 측정 방법 — 부하를 다시 걸지 않았습니다

적체 **15,515건이 그대로 남아 있어서** 그걸 측정대로 썼습니다.
같은 데이터, 같은 머신, 다른 스레드 수. **부하 생성기가 개입하지 않아 오히려 깨끗한 비교**입니다.

### 결과

| 지표 | 스레드 1 | **스레드 3** | |
|---|---|---|---|
| **처리량** | 18건/s | **75건/s** | **4.2배** |
| notification CPU | 3.3% | 24.8% | |
| MySQL CPU | 8.7% | 31.8% | |

**스레드는 3배인데 처리량은 4.2배**입니다. 커밋이 겹치면서 MySQL이 **그룹 커밋으로 묶었기**
때문입니다 — 순차로 기다릴 때는 커밋 하나하나가 따로 디스크를 기다렸습니다.

### 동시에 돌려도 알림은 정확했습니다

```
알림 57,638건 (전부 SENT)
종결 송금 28,819건 × 2 = 57,638건   ← 정확히 일치
```

**중복 0건, 누락 0건.** 스레드를 3배로 늘렸는데도 "같은 소식으로 두 번 알리지 않는다"가
지켜졌습니다 — unique 제약과 `claim` → 발송 → `markSent` 순서가 설계대로 버텼습니다.

> 이 서비스의 클래스 주석에 **"동시에 들어오는 일은 없지만 그럼에도 unique 제약을 둔 이유는
> 그 전제가 깨져도 중복 알림만은 막히게 하기 위해서"**라고 적어 뒀었습니다.
> **오늘 그 전제를 실제로 깼고, 준비해둔 것이 값을 했습니다.**

### 남은 것

메시지당 커밋 4번은 그대로입니다. 지금은 75건/s로 도착(79건/s)을 거의 따라잡아
**당장 급하지 않습니다.** 종결 처리량을 더 올리면 그때 다시 볼 자리입니다.

---

## SLO를 정하고 용량을 쟀다 — **40 TPS** ★

**목표**: "초당 몇 건 나오나"에서 **"약속한 지연 안에 몇 건을 서비스할 수 있나"**로 옮기기

### 왜 필요했나

⑦까지 끝내고 나니 종결 처리량이 19 → 80건/s로 4.2배가 됐는데, **같은 측정에서 종결 p95가
32.8초**였습니다. 처리량만 보면 성공이고 사용자 입장에서는 실패입니다.

문제는 `spread.js`가 **초당 400건까지 밀어 올린다**는 것이었습니다. 시스템 능력이 80건/s인데
**감당 못 하는 5배를 부어놓고 지연을 재고 있었습니다.** 그건 시스템 지연이 아니라
**대기열 길이**입니다.

### 정한 목표 (`docs/SLO.md`)

| 지표 | 목표 | 근거 |
|---|---|---|
| 접수 지연 **p99 < 500ms** | 사용자 체감 | 커밋 3번 = 이론상 50ms, **10배 여유** |
| 종결 지연 **p99 < 5초** | 사용자 체감 | Kafka 3홉 × 릴레이 200ms = **바닥 1초**, 5배 여유 |
| 접수 오류율 **< 0.1%** | 가용성 | 재시도로 회복됨(`Idempotency-Key`) |
| 종결 실패(시스템) **0** | 정합성 | 돈이 갇히는 것은 허용 못 함 |
| 유실·중복·갇힘 **0** | 타협 불가 | 목표가 아니라 전제 |

**p99를 쓴 이유**: 하루 100만 건이면 1%가 **1만 명**입니다.

**종결이 밀리초가 아니라 초인 이유**: 비동기 Saga라 Kafka를 세 번 거치고 그 사이마다
릴레이가 최대 200ms를 기다립니다. **구조적으로 1초 아래로 못 내려갑니다.**
smoke에서 10 TPS일 때 종결 p99가 **1,693ms**로 나온 게 정확히 그 바닥값이었습니다.

### 측정 — 계단을 올리며 깨지는 지점을 찾는다

`capacity.js`를 새로 만들었습니다. 20 → 120 TPS를 한 단계씩(2분씩) 올리고,
**단계 사이에 60초씩 큐를 비웁니다** — 안 그러면 뒤 단계가 남의 적체까지 처리하게 되어
진짜 한계보다 낮게 나옵니다.

| 도착률 | 접수 p99 | 종결 p99 | 종결 성공률 | 판정 |
|---|---|---|---|---|
| 20 TPS | 165ms | 2,173ms | 100% | ✅ |
| **40 TPS** | **258ms** | **2,611ms** | **100%** | **✅** |
| 60 TPS | 358ms | **7,721ms** | 100% | ❌ |
| 80 TPS | 392ms | 42,844ms | 100% | ❌ |
| 100 TPS | 387ms | 59,578ms | 82.98% | ❌ |
| 120 TPS | 546ms | 59,881ms | 26.89% | ❌ |

### 세 가지가 보입니다

**① 접수는 병목이 아닙니다.** 120 TPS에서도 546ms입니다. ①에서 커넥션 풀을 10 → 30으로
올렸을 때 "종결이 1건도 안 움직였다"고 실망했는데, **그게 지금 여기서 값을 하고 있습니다.**

**② 60 TPS가 무릎입니다.** 40 → 60에서 2.6 → 7.7초로 세 배, 그다음은 42초로 폭발.
평평하다가 급격히 꺾이는 전형적인 포화 곡선입니다.

**③ 성공률만 보면 80 TPS까지 멀쩡해 보입니다** — 100%입니다. 다들 60초 안에는 끝났으니까요.
**지연을 같이 보지 않았으면 못 잡았습니다.** 목표를 셋으로 나눠 건 이유가 이것입니다.

### 80건/s와 40 TPS는 왜 다른가

80건/s는 **이미 밀린 큐를 비우는 속도**입니다. 지연을 약속하려면 큐가 짧아야 하고,
그러려면 여유가 필요합니다. 40 / 80 = **가동률 50%** — 대기행렬은 가동률이 100%에
가까워질수록 대기 시간이 급증하므로, **절반 남짓에서 운영하는 것이 정상**입니다.

> **이제 이 저장소에는 정직한 숫자가 하나 생겼습니다: 용량 40 TPS.**
> 앞으로의 개선은 "처리량이 올랐다"가 아니라 **"용량이 40 → N TPS가 됐다"**로 말합니다.

---

## Phase 6 Step 1 관문 — 부하가 계좌 락까지 닿는가 ✅ 닿는다

ROADMAP에 이렇게 걸어뒀습니다.

> *"파이프라인을 뚫어 부하가 락까지 닿게 만든 뒤가 핫 계좌의 차례입니다."*

그 조건을 만들려고 Step 2에서 일곱 번을 고쳤습니다. **이제 확인할 차례입니다.**

### ① 핫 계좌가 확실히 더 나쁩니다

같은 부하(400 TPS까지)로 두 시나리오를 돌린 결과입니다.

| 지표 | `spread` (계좌 분산) | `hot-account` (한 계좌로 몰림) |
|---|---|---|
| 접수 처리량 | 129 req/s | 142 req/s |
| **종결 성공률** | **0.76** | **0.56** |
| **종결 p95** | **32.8초** | **42.4초** |
| 시간초과 | 46건 | **79건** |

**접수는 오히려 핫 계좌 쪽이 빠릅니다.** 시나리오 주석에 적어둔 그대로입니다 —
*"접수 지표만 보고 있으면 시스템이 멀쩡해 보인다."*

### ② 락 지표가 baseline과 다릅니다

| | baseline | 지금 |
|---|---|---|
| 락 대기 p99 | 0.07초 | **0.09~0.41초** |
| **락 타임아웃** | **0건** | **발생 (3초를 기다려도 못 잡음)** |
| 낙관적 락 충돌 | 0 | 0.1~0.7/s |

**타임아웃이 0에서 떨어져 나온 게 핵심**입니다. 이전에는 락이 아무것도 막고 있지 않았습니다.

## 그런데 대기만 봐서는 원인을 못 가릅니다 — 보유 시간을 심었다

대기 p99가 95~100ms로 나왔는데, **왜 100ms인지는 대기 시간만으로 알 수 없었습니다.**
두 가지가 섞여 있기 때문입니다.

| | 무엇 | 처방 |
|---|---|---|
| **보유** | 앞사람이 임계 구역을 붙들고 있는 시간 | **임계 구역을 줄인다** |
| **넘겨받는 지연** | 놓은 것을 뒷사람이 알아채기까지 | **폴링을 그만둔다** (Redisson은 pub/sub) |

이 구현은 `RETRY_INTERVAL = 50ms`마다 Redis에 다시 물어봅니다. **락이 5ms 만에 풀려도
최대 50ms를 더 기다립니다.** 대기 p99 ≈ 100ms가 정확히 그 두 배라
**"폴링 간격이 지배한다"고 짐작했습니다.**

그래서 `remittance.lock.hold`를 심고 다시 쟀습니다.

### 짐작이 틀렸습니다 — 보유가 더 큽니다

| | |
|---|---|
| **보유 p50** | **38ms** |
| **보유 p99** | **223ms** |
| 대기 p99 | 93ms |
| 락 통과 | 89/s |

**보유 p99가 대기 p99의 2.4배**입니다. 폴링 간격이 아니라 **임계 구역 자체가 길어서**
줄이 서는 것이었습니다. 50ms 폴링을 고쳤다면 별 효과 없이 시간만 썼을 겁니다.

> **가르지 않고 고쳤으면 어느 쪽을 고친 건지도 몰랐을 것**입니다.

### 보유 38ms가 뜻하는 것

**한 계좌의 처리량 상한이 초당 약 26건**입니다. 임계 구역이 직렬이라
**서버를 몇 대로 늘리든 이 숫자는 안 변합니다.** Phase 6이 말하는
*"서버를 늘려도 안 풀리는 병목"*이 숫자로 나온 것입니다.

안에서 도는 것은 JPA 트랜잭션 하나입니다 — 처리 흔적 INSERT, 계좌 SELECT,
잔액 UPDATE, Outbox INSERT, 분개 INSERT, 그리고 **커밋(약 14ms)**.
다섯이 한 트랜잭션이어야 하는 이유는 `SagaStepExecutor` 주석에 있습니다.

### 측정을 한 번 망쳤습니다

보유 지표를 심고 처음 잰 실행은 **볼륨을 초기화하지 않고 돌렸습니다.**
앞 실행의 송금 31,180건과 적체 27,000건 위에 새 부하를 얹은 것이라, 종결 성공률이
**0.00**으로 나왔습니다. 그건 시스템 성질이 아니라 **앞 실행의 큐 뒤에 줄 선 결과**입니다.

초기화 후 다시 재니 성공률 0.56으로 **첫 실행과 같은 값**이 나왔습니다(재현됨).

> 락 지표(보유·대기)는 두 실행에서 거의 같았습니다 — **건당 비용이라 큐 깊이와 무관**하기
> 때문입니다. 반대로 종결 지표는 큐 깊이에 통째로 좌우됩니다.
> **같은 실행의 숫자라도 어떤 건 오염되고 어떤 건 멀쩡합니다.**

### 다음

**임계 구역을 줄이는 것**입니다. 그런데 다섯 문장이 한 트랜잭션이어야 해서 빼낼 게 없습니다.
그래서 다음 항목이 진짜 지렛대입니다 — **분산 락을 빼고 낙관적 락 + 재시도만으로.**
DB 행 락은 UPDATE부터 커밋까지만 잡으므로 **임계 구역이 훨씬 짧아집니다.**

ROADMAP에 예상까지 적어뒀습니다: *"충돌이 적은 계좌는 빨라지고 핫 계좌는 오히려 나빠진다."*
**그 예상이 맞는지가 다음 측정입니다.**

---

## Phase 6 Step 1 — 분산 락을 빼면 어떻게 되나 (비교 실험) ★

**같은 jar에 프로퍼티만 바꿔** 두 전략을 각각 쟀습니다(`account.lock.strategy`).
`/actuator/info`에 전략을 실어, 측정할 때마다 **어느 쪽이 떠 있는지 물어볼 수 있게** 했습니다.

### 결과

| 지표 | DISTRIBUTED | OPTIMISTIC | |
|---|---|---|---|
| 접수 처리량 | 142.7 req/s | 145.2 req/s | 차이 없음 |
| **종결 p95** | 40.8초 | **17.0초** | **절반 이하** |
| 종결 p99 | 54.7초 | 42.4초 | 개선 |
| 종결 성공률 | 0.56 | 0.49 | 나빠짐 |
| 낙관적 락 충돌 | 0 | **retried 1,617 · exhausted 288** | |
| **영구히 갇힌 송금** | **9건** | **168건** | **19배** |

### ROADMAP의 예상이 맞았습니다 — 한 실행 안에 둘 다 보입니다

> *"충돌이 적은 계좌는 빨라지고 **핫 계좌는 오히려 나빠진다**"*

`hot-account` 시나리오에는 **두 종류의 계좌가 함께** 있습니다.

- **보내는 60개 계좌** — 충돌이 거의 없다 → 락 대기가 사라져 **p95가 절반 이하로**
- **받는 계좌 하나** — 전부 몰린다 → **재시도 소진 288건**

**빠른 쪽은 더 빨라지고, 막히는 쪽은 아예 못 끝냈습니다.**
그래서 결론은 "어느 쪽이 낫다"가 아니라 **"계좌 성격에 따라 다르다"**입니다.

### 대가가 예상보다 무거웠습니다

`exhausted` 288건은 그냥 실패가 아니었습니다. 컨슈머에서 예외로 올라가 재시도 3회를 더 쓰고
**DLT로 죽었습니다.** DLT 메시지는 자동으로 되살아나지 않습니다.

```
COMPLETED: 28,904
DEBIT_COMPLETED: 168   ← 출금은 됐는데 입금이 안 됨. 사람이 손대기 전엔 안 풀린다
DLT: 168건             ← 정확히 일치
```

`SLO.md`에 **"출금은 됐는데 입금은 안 된 상태로 남는 것이 최악"**이라고 적어둔 그 일입니다.
**지연은 절반이 됐지만 타협 불가로 걸어둔 항목을 깼습니다.**

### 그런데 DISTRIBUTED도 9건이 갇혔습니다 ⚠️

**이건 이번 실험과 별개로 지금 운영 설정의 결함입니다.**
락 대기 3초를 넘긴 요청이 `LockAcquisitionException`으로 올라가고,
그게 재시도 가능 예외로 분류돼 있어 **재시도를 다 쓰면 DLT로 갑니다.**

168건에 가려 보이지 않았을 뿐, **0이어야 하는 숫자가 0이 아닙니다.**
`spread` 시나리오에서는 안 보이고 **핫 계좌에서만 드러납니다.**

### 다음

1. **`LockAcquisitionException`을 DLT로 보내지 않는다** — 락을 못 잡은 것은 "지금 붐빈다"는
   뜻이지 처리할 수 없는 메시지가 아니다. 재시도 횟수를 넘겨도 **오프셋을 커밋하지 않고
   계속 붙들어야** 한다. 지금은 세 번 만에 포기하고 죽인다
2. **계좌별 전략 분리** — 이번 실험이 근거다. 일반 계좌는 낙관적 락, 핫 계좌는 분산 락
3. **잔액 샤딩** — 핫 계좌 자체의 26건/s 상한을 깨려면 결국 이것

---

## Phase 6 Step 1 — 붐빈다는 이유로 돈을 버리지 않는다 ✅ 갇힘 9 → 0

**목표**: 비교 실험에서 발견한 결함 고치기 — **지금 운영 설정에서도 송금 9건이 갇혔다**

### 무엇이 문제였나

```
락 3초 대기 실패 → LockAcquisitionException
                 → 재시도 1초·2초·4초 → DLT (죽음)
                 → 송금이 DEBIT_COMPLETED로 영영 멈춤
```

**락을 못 잡은 것은 "지금 붐빈다"는 뜻이지 처리할 수 없는 메시지가 아닙니다.**
잠시 뒤에 다시 하면 됩니다. 여기에 횟수 제한을 두면 **붐빈다는 이유로 돈을 버리는 셈**입니다.

이제 경합 계열(`LockAcquisitionException`·`ConcurrentUpdateException`)은
**횟수 제한 없이** 1초 간격으로 재시도합니다.

### 결과

| 지표 | 고치기 전 | 고친 뒤 |
|---|---|---|
| **영구히 갇힌 송금** | **9건** | **0건** ✅ |
| **DLT** | 9건 | **0건** (지표 자체가 안 생김) |
| 락 타임아웃 | 53건 | **262건** |
| 종결 성공률 | 0.56 | 0.57 |
| 종결 p95 | 40.8초 | 46.5초 |

**경합은 오히려 5배 늘었는데(53 → 262건) 죽은 건 하나도 없습니다.**
이게 정확히 의도한 그림입니다 — 붐비는 것 자체는 막을 수 없지만,
**붐볐다고 해서 돈이 사라지지는 않습니다.**

p95가 조금 나빠진 것(40.8 → 46.5초)은 **포기하던 것을 끝까지 하기 때문**입니다.
예전에는 9건을 버려서 그만큼 빨랐던 것이고, **그건 빠른 게 아니라 안 한 것**입니다.

### 대가와 그게 괜찮은 이유

**그 파티션이 그동안 막힙니다.** 그런데 그게 맞는 동작입니다 — 처리할 수 있는 것보다
많이 들어오는 중이니 **받는 속도를 늦추는 것(배압)**이 옳습니다.

**영영 막히지도 않습니다.** 락에 TTL 3초가 있어 **붙들려 있는 상태 자체가 지속될 수 없습니다.**
이 근거가 없었으면 무한 재시도는 위험한 선택이었을 겁니다.

**경합이 아닌 실패는 그대로 세 번 뒤 DLT로 갑니다.** 거기까지 무한 재시도하면 진짜 못 고치는
메시지가 파티션을 영영 막고, **DLT를 둔 이유 자체가 없어집니다.**

### 놓치기 쉬웠던 것

리스너 예외는 spring-kafka가 `ListenerExecutionFailedException`으로 **감싸서** 올립니다.
맨 바깥만 보면 경합인 줄 모르는데, **실제 운영에서 오는 모양이 그쪽**입니다.
원인 사슬을 따라가고 그 경우를 테스트로 따로 걸었습니다.

### 검증을 정책 단위로 건 이유

무한 재시도가 실제로 일어나는지는 부하를 걸어야 보입니다. 하지만 **정책이 잘못 붙으면
그 부하 시험도 볼 것이 없습니다.** 무엇보다 **이건 틀려도 아무 증상이 없습니다** —
평상시에는 경합이 없어 두 정책이 똑같이 보입니다.
고치기 전 동작으로 되돌리면 정확히 셋이 실패하는 것을 확인했습니다.

---

## Phase 6 Step 1 — 포화에서 잰 값으로 설계를 정할 뻔했다 ★★

**목표**: 측정 한 사이클이 20분이라 못 견디겠어서 부하를 용량 근처로 낮춤 — 그런데 **결론이 뒤집혔다**

### 먼저: 측정이 20분 걸리던 이유

| 단계 | 시간 |
|---|---|
| 빌드·기동 | ~3분 |
| k6 실행 | 4분 32초 |
| **적체 드레인 대기** | **10~15분** |

`hot-account.js`가 **초당 400건까지** 밀어 올리는데 이 시스템 용량은 **40 TPS**입니다.
**감당 못 하는 10배를 4분간 부어놓고 그게 빠지기를 기다리고 있었습니다.**
그 10분은 아무것도 알려주지 않습니다 — 이미 아는 사실(포화된다)을 다시 확인할 뿐입니다.

`SLO.md`에 *"감당 못 하는 부하를 부어놓고 잰 지연은 대기열 길이"*라고 적어놓고,
**핫 계좌 쪽은 옛 시나리오를 그대로 쓰고 있었습니다.** 진단은 해놓고 처방을 한쪽에만 쓴 셈입니다.

`RATE=30`으로 2분만 돌게 했더니 **한 사이클이 20분 → 2분 40초**가 됐습니다.

### 그리고 결론이 뒤집혔습니다

같은 두 전략, 같은 jar, 도착률만 다릅니다.

| | **400 TPS (포화)** | | **30 TPS (용량 근처)** | |
|---|---|---|---|---|
| | DISTRIBUTED | OPTIMISTIC | DISTRIBUTED | OPTIMISTIC |
| 종결 p95 | 40.8초 | **17.0초** | **2.1초** | 5.6초 |
| 종결 p99 | 54.7초 | 42.4초 | **2.6초** | 7.0초 |
| 종결 성공률 | 0.56 | 0.49 | 1.00 | 1.00 |
| 갇힘 | 9건 | 168건 | **0** | **0** |
| | **낙관적이 2.4배 빠름** | | **분산 락이 2.7배 빠름** | |

**포화에서는 낙관적이 이기고, 용량 안에서는 분산 락이 이깁니다.** 정반대입니다.

### 왜 뒤집히나

| | 분산 락 | 낙관적 락 |
|---|---|---|
| 경합하면 | **기다린다** | **일을 처음부터 다시 한다** |
| 포화 상태 | 대기 줄이 끝없이 길어짐 → 불리 | 기다리지 않음 → 유리 |
| 용량 안 | 락 타임아웃 **0건**, 보유 평균 21ms → 유리 | 충돌 **1,123건**을 전부 다시 함 → 불리 |

**낙관적 락은 헛일이 늘어나는 방식으로 대가를 치릅니다.** 부하가 감당 가능한 구간에서는
그 헛일이 락 대기보다 비쌉니다. 포화 상태에서는 락 대기가 워낙 길어져서 역전됩니다.

### 그래서 무엇이 달라지나

**SLO 기준으로는 분산 락만 통과합니다.**

| | 목표 | DISTRIBUTED | OPTIMISTIC |
|---|---|---|---|
| 접수 p99 | < 500ms | 142ms ✅ | 153ms ✅ |
| **종결 p99** | **< 5초** | **2.6초 ✅** | **7.0초 ❌** |

**그리고 "계좌별로 전략을 나눈다"는 계획의 근거가 사라졌습니다.**
원래 근거는 *"낙관적이 빠른데 돈을 가둔다"*였는데,
- 가두는 문제는 **경합 무한 재시도로 이미 해결**했고(갇힘 0/0),
- 빠르다는 것은 **포화 상태에서만 참**이었습니다.

**실제로 서비스하는 구간에서는 분산 락이 더 빠르고 SLO도 지킵니다.**
나눌 이유가 없어졌습니다 — 두 전략 중 하나를 고르면 되고, 답은 분산 락입니다.

> **하마터면 정반대로 갈 뻔했습니다.** 포화에서 잰 값 하나만 보고
> "낙관적이 2.4배 빠르다"로 설계를 정했다면, **실제 운영 구간에서 2.7배 느린 쪽**을
> 고르고 계좌별 분기라는 복잡도까지 떠안았을 겁니다.

### 남는 것

낙관적 전략은 **지우지 않고 스위치로 남깁니다.** 포화 상태에서 유리한 건 사실이고,
`AccountLockPolicy`는 나중에 필요해지면 계좌별로 고르는 자리가 됩니다.
**지금은 근거가 없어서 안 하는 것**이지 틀린 발상이라서가 아닙니다.

---

## Phase 6 — 스키마를 앱이 만들지 않게 했다 (`ddl-auto` → Flyway) ✅

`DECISIONS.md` 교체 4번. 원래 "Phase 7 전"으로 잡아뒀는데 **잔액 샤딩이 스키마를 바꾸므로
그 직전인 지금** 넣었습니다. 샤딩부터 하면 첫 마이그레이션이 곧 첫 실전 변경이 되는데,
**도구를 처음 쓰는 날과 어려운 변경을 하는 날이 겹치면** 무엇 때문에 깨졌는지 못 가립니다.

### 무엇이 문제였나

`ddl-auto: update`는 **더하기만 하고 빼지 않습니다.**

```
Transfer.idempotencyKey의 unique를 떼고 테스트 → red
  그런데 red가 난 이유가 "코드가 틀려서"가 아니라 "컨테이너가 새로 떠서"였다.
  기존 DB였다면 인덱스가 남아 있어 green이었을 것이다.
```

**검증 결과가 코드가 아니라 DB 상태에 달려 있었습니다.** 그리고 스키마를 만드는 주체가
앱(`ddl-auto`) · 초기화 스크립트(`01-databases.sql`) · 사람 손 셋으로 갈라져 있었는데,
셋 다 "이미 있으면 안 한다"라서 **어긋난 상태를 아무도 못 봅니다.**

### 베이스라인은 설계하는 게 아니라 뜨는 것이다

이게 이번에 가장 헷갈렸던 지점입니다. V1을 쓸 때 자연스럽게 "이왕 하는 김에 이름 정리하자"는
생각이 듭니다 — `UKc662f7lm5ec167m89rp50kb1d` 같은 Hibernate의 흔적이 그대로 보이니까요.

**하면 안 됩니다.** 지금 DB는 두 종류가 됩니다.

| DB | Flyway가 하는 일 |
|---|---|
| 새 DB (테스트 컨테이너, 볼륨 초기화 후) | **V1을 실행한다** |
| 기존 DB (홈서버) | **도장만 찍고 V1을 건너뛴다** |

V1에서 이름을 고치면 **이 둘이 갈라집니다.** 그래서 `mysqldump --no-data`로 받아
`AUTO_INCREMENT=N`(스키마가 아니라 데이터 상태)만 지우고 그대로 넣었습니다.
고치고 싶으면 지금부터는 V2입니다.

### `baseline-on-migrate`는 안전장치가 아니다

기존 DB가 안 깨지게 하려면 `baseline-on-migrate: true`가 필요한데, 이건 **도장을 찍을 뿐
내용을 보지 않습니다.** 실제 스키마가 V1과 전혀 달라도 통과합니다.

그래서 `ddl-auto: validate`를 짝으로 켰습니다. **한쪽만으로는 부족합니다.**

```
baseline-on-migrate  → 기존 DB에서 기동이 되게 한다        (내용은 안 본다)
ddl-auto: validate   → 엔티티와 실제 테이블을 대조한다      (기동을 멈춘다)
```

### 확인 — green만으로는 아무것도 못 말한다

테스트가 통과했을 때 로그에 **Flyway 줄이 한 줄도 없었습니다.** 그러면 green이
"Flyway가 만들었다"인지 "어쨌든 테이블이 있었다"인지 구분이 안 됩니다.

**V1의 컬럼 이름 하나를 일부러 틀리게 했습니다.**

```
message → message_XXX
  → notification 테스트 7개 전부 SchemaManagementException으로 red
```

Flyway가 그 테이블을 만들었고 validate가 그걸 대조하고 있다는 뜻입니다. 되돌리고 다섯 서비스 green.

그다음 **진짜 위험한 쪽** — 데이터가 든 홈서버 DB에 배포했습니다.

| 확인 | 결과 |
|---|---|
| 다섯 서비스 기동 | 전부 UP (= validate가 실제 운영 스키마를 통과) |
| 네 DB의 `flyway_schema_history` | 전부 `<< Flyway Baseline >>` · `type=BASELINE` · `success=1` |
| V1 실행 여부 | **안 됨** (도장만) |
| 데이터 | accounts 61 · transfers 2521 · notifications 5042 — 그대로 |

### 남는 것

원장(ledger)은 MongoDB라 여기서 빠집니다. **같은 문제가 그대로 남아 있습니다** —
`MongoIndexInitializer`가 기동할 때 인덱스를 만듭니다. Phase 7에서 Mongock으로 맞춥니다.
ROADMAP의 "Flyway와 함께 옮긴다"는 문장을 **"JPA 쪽만 먼저 갔다"**로 고쳐뒀습니다.

---

## Phase 6 Step 1 — 잔액 샤딩: 핫 계좌 용량 25 → 50 TPS ✅ ★★

**한 계좌의 잔액이 한 행이라 그 계좌의 입금이 전부 그 행에 줄을 섭니다.**
서버를 늘려도 안 변하는 병목이고, 이 Phase의 본론이었습니다.

### 두 단계로 나눴습니다

| | 무엇 | 왜 나눴나 |
|---|---|---|
| ① `366812e` | 잔액을 `account_balance_shards`로 **옮기기만** | 여기서 숫자가 나빠지면 그건 샤딩이 아니라 **테이블을 옮긴 대가**다 |
| ② `f754b58` | 입금을 **조각별 락**으로 가르기 | 락이 계좌 하나면 조각을 나눠도 거기서 다시 줄을 선다 |

②가 없으면 ①은 아무 효과가 없습니다. **쪼개는 것의 절반은 락을 쪼개는 것**입니다.

```
입금   lock:account:{id}:s{n}      고른 조각 하나만
출금   lock:account:{id}:s0..N-1   전부
```

조각이 하나인 계좌(대부분)는 두 경우가 같은 키 하나로 떨어져 **전과 완전히 같습니다.**

### 결과 — 종결 p99 (ms), SLO는 5,000ms

**고정 도착률**입니다. 처음에 잰 값은 램프였고, 그건 아래 "두 번 틀렸습니다"에 적었습니다.

| 도착률 | 1조각 | 8조각 |
|---|---|---|
| 20 TPS | 2,115 ✅ | |
| 25 TPS | 2,723 ✅ | |
| 30 TPS | **7,770 ❌** | 2,118 ✅ |
| 45 TPS | | 2,600 ✅ |
| 50 TPS | | **4,112 ✅** |
| 60 TPS | | **24,145 ❌** |

**핫 계좌 용량 25 → 50 TPS, 정확히 2배.** 30 TPS에서 1조각 대비 **p99 7.8초 → 2.1초.**

쪼개기 전 상한 25 TPS는 예전에 락 보유 시간(38ms)으로 추정했던 **"한 계좌 초당 26건"**과
맞아떨어집니다. **추정과 실측이 처음으로 만났습니다** — 그때의 계산이 맞았다는 뜻입니다.

### 60 TPS에서 무너지는 이유는 계좌가 아닙니다

쪼갠 목적은 달성됐고 병목이 옮겨갔습니다. **어디로 옮겼는지는 아래 "다음 병목"에 있습니다** —
처음엔 접수라고 적었는데 그것도 틀렸습니다.

### ⚠️ 측정에서 두 번 틀렸습니다 ★★

#### ① 식은 실행과 데워진 실행을 비교했다

처음 잰 1조각 값은 **p95 32.0초**였습니다. 8조각이 2.1초로 나오자 **15.4배**라는 숫자가
나왔고, 그대로 적을 뻔했습니다.

**그 실행만 재기동 직후 첫 실행이었습니다.** JVM·커넥션 풀·컨슈머 리밸런스가 안 풀린 상태를
데워진 실행과 비교한 것입니다. 다시 재니 **2,119ms** — 예전 기록(2.1초)과 같았습니다.

들킨 계기는 <b>숫자가 너무 좋아서</b>가 아니라 **락 지표가 안 맞아서**였습니다.
보유 평균이 20.1ms로 예전(21ms)과 같았는데, 임계 구역이 그대로인데 15배가 좋아질 수는 없습니다.

> **규칙으로 남깁니다: 재기동 직후 첫 실행은 버린다.** 그리고 A/B는 반드시
> **같은 온도**에서 잰다. 이번엔 락 지표가 잡아줬지만, 지표를 안 봤으면 못 잡았습니다.

측정 사이에 **드레인을 기다리는 것**도 함께 스크립트로 굳혔습니다(`scripts/measure-hot-account.sh`) —
앞 실행의 적체가 다음 숫자를 오염시킵니다.

#### ② `RATE`는 고정 도착률이 아니라 램프였다

여기까지 오고 나서, **다음 병목을 보려고 숫자를 다시 들여다보다가 발견했습니다.**

`hot-account.js`는 `ramping-arrival-rate`를 쓰는데 `startRate: 10`이 박혀 있습니다.
단계가 `[{target: 60}]` 하나뿐이어도 **10에서 60까지 2분간 올라갑니다.** 평균은 35입니다.

**그런데 요약은 "고정 도착률 60 TPS"라고 찍고 있었습니다.** 그 값으로
"핫 계좌 용량 60 TPS"라고 적었고, 위 표를 전부 다시 재서 고쳤습니다
(30 → 60이 아니라 **25 → 50**).

넘겨짚은 것은 이것입니다 — **단계가 하나면 고정일 거라고 생각했습니다.**
`startRate`는 시작점이지 "첫 단계의 값"이 아닙니다.

`capacity.js`는 `constant-arrival-rate`라 영향이 없습니다. **용량 40 TPS는 그대로 유효합니다.**

#### 그리고 요약 자체가 틀린 걸 재고 있었습니다

`처리량`이 `http_reqs` 전체였습니다. 거기엔 **prober의 폴링 GET과 setup의 계좌 생성까지**
섞여 있습니다. 그 값을 "접수 처리량"으로 읽고 **없는 병목을 쫓을 뻔했습니다.**

이제 `name:accept`만 셉니다. 그리고 **`미발사`(dropped_iterations)**를 함께 찍습니다 —
0이 아니면 요청한 부하가 실제로 안 걸린 것이라 **그 실행의 지연 값 자체가 의미가 없는데,
지금까지 아예 보고 있지 않았습니다.**

> **세 번 다 같은 실수입니다.** 도구가 뭘 재고 있는지 확인하지 않고 이름만 믿었습니다.
> "고정 도착률"이라 적혀 있으니 고정이겠거니, "처리량"이라 적혀 있으니 접수겠거니.
> **숫자보다 그 숫자의 정의를 먼저 봐야 합니다.**

### 돈이 맞는가 — 이게 먼저입니다

지연은 깎아서 타협할 수 있지만 이건 못 합니다.

| 확인 | 결과 |
|---|---|
| 계좌 잔액 합계 | **660,000,000,000.00** |
| 원장 합계 (CREDIT − DEBIT) | **660,000,000,000** |
| 대사 어긋남 (드레인 후) | **0건** (회차 715·716·717) |
| 8조각 계좌의 분포 | 28,100 ~ 33,700 — 고르게 흩어짐 |

부하 중에는 `BALANCE_MISMATCH`가 뜹니다. 잔액은 이미 움직였는데 원장이 아직 안 따라온
**일시적인 차이**이고, 드레인되면 0으로 돌아옵니다. 쪼개기 전(2026-08-23)에도 같았습니다.

### 대가 — 출금은 오히려 느려집니다

출금은 합을 봐야 모자란지 알 수 있어 **조각을 전부 읽고 전부 잠급니다.**
조각 하나만 보고 거절하면 **잔액이 있는데 실패하는** 일이 생깁니다.

핫 계좌는 **받는 쪽**이라 이 대가를 치를 만하다고 봤습니다. 보내는 쪽이 붐비는 계좌라면
이 설계는 맞지 않습니다 — 그때는 조각을 미리 채워두고 조각별로 빼는 다른 방식이 필요합니다.

그리고 **쪼갠 계좌의 "변경 후 잔액"은 근사치**가 됩니다. 입금이 조각 하나만 읽으므로
나머지 합은 읽은 시점의 값입니다. 정합성 대사는 이 값을 쓰지 않아 영향이 없지만,
"원장만 보고 잔액 추이를 재구성한다"는 원래의 약속은 쪼갠 계좌에서 깨집니다.

### 조각 수는 어디에 두나

`accounts.shard_count`가 진실이고, `ShardRouter`가 **쪼갠 계좌만** 메모리에 들고 있습니다.
입금마다 계좌를 한 번 더 읽으면 커넥션을 한 번 더 잡는데, Step 2의 첫 병목이 정확히
커넥션 대기였으므로 되살릴 이유가 없습니다.

다른 인스턴스가 쪼갠 것은 최대 60초 뒤에 반영됩니다. **늦게 알아도 안전합니다** —
조각은 늘리기만 하고 줄일 수 없으므로(`Account#widenShards`), 예전 수로 고른 번호는
반드시 존재합니다. 늦게 아는 대가는 "아직 안 빨라짐"이지 "틀림"이 아닙니다.

---

## 다음 병목 — 접수인 줄 알았는데 입금 리스너였다 (초당 57건) ★

### 또 분모에 속았습니다

요약의 `접수 처리량`이 60 TPS 실행에서 **50~55 req/s**로 나와서 "접수가 포화했다"고 적었습니다.
`http_reqs{name:accept}`만 세도록 이미 고친 뒤였는데도 틀렸습니다.

**k6의 `rate`는 분모가 부하 구간이 아니라 전체 실행 시간입니다.** 부하는 2분인데 prober의
꼬리까지 합쳐 2분 13초가 걸리므로, 7,320건 ÷ 133초 = 55입니다.

원본 출력을 통째로 보니 답이 한 줄에 있었습니다.

```
load ✓ [ 100% ] 000/050 VUs  2m0s  60.00 iters/s
7319 complete and 0 interrupted iterations   ·   실패율 0.00   ·   미발사 0
```

**k6는 60건/s를 정확히 넣었고 전부 202를 받았습니다.** 접수 p99는 300ms입니다.
접수는 병목이 아니었습니다. 요약에 **건수를 함께 찍게** 고쳤습니다.

> 오늘만 **세 번째로 같은 실수**입니다 — 도구가 뭘 재는지 확인하지 않고 이름만 믿었습니다.
> 이번엔 "처리량"이 rate이고 그 rate의 분모가 무엇인지를 안 봤습니다.

### 진짜 병목은 컨슈머 lag가 알려줬습니다

60 TPS 부하 중 7초마다 네 그룹의 lag를 찍었습니다.

```
04:14:48  transfer=43   account=75    ledger=70  notification=3590
04:15:16  transfer=138  account=371   ledger=0   notification=4176
04:15:45  transfer=86   account=633   ledger=61  notification=5050
```

**account만 계속 증가합니다** (75 → 633). transfer·ledger는 안정입니다.
notification은 5,000까지 밀리지만 **종결 경로가 아니라** 종결 지연에 영향을 주지 않습니다
(잎사귀입니다). 나중에 볼 자리로 남겨둡니다.

### 상한이 그대로 계산됩니다

| 리스너 | 평균 처리 시간 | 스레드 | 상한 |
|---|---|---|---|
| `transfer.requested` (출금) | 38.1ms | 3 | **78.7건/s** |
| **`transfer.debited` (입금)** | **52.5ms** | **3** | **57.1건/s** |

**필요한 건 60건/s인데 상한이 57입니다.** 그래서 50 TPS는 통과하고 60은 무너집니다 —
경계가 정확히 그 사이에 있는 이유가 이것입니다.

### 자원이 남는데 못 씁니다

같은 구간에서:

| | 값 |
|---|---|
| 서비스별 JVM CPU | **3~4%** (코어 하나도 못 채움) |
| 호스트 CPU | 34% |
| HikariCP 대기 | **0** |
| 락 타임아웃 | 0 |

**아무것도 포화가 아닌데 밀립니다.** 스레드가 3개뿐이기 때문이고,
**스레드는 파티션 수를 넘을 수 없어서** 3개입니다. 모든 토픽이 파티션 3입니다.

다음 두 가지가 표적입니다.

1. **파티션 3 → N** — 상한의 직접 원인. 다만 파티션을 늘리면 키 → 파티션 배정이 바뀌므로
   **토픽이 빈 상태에서** 해야 합니다
2. **입금 한 건 52.5ms 줄이기** — 파티션을 늘려도 결국 이 값이 상한을 정합니다

---

## 파티션 3 → 6 — 용량 50 → 60 TPS, 그런데 2배가 아니라 1.2배다 ★

스레드를 두 배로 늘렸는데 용량은 20%만 올랐습니다. **왜 그런지가 이 기록의 요점입니다.**

### 결과

| | 파티션 3 | 파티션 6 |
|---|---|---|
| 60 TPS 종결 p99 | **13,250 ~ 24,145ms ❌** | **4,299ms ✅** |
| 70 TPS 종결 p99 | — | 13,279ms ❌ |
| **핫 계좌 용량** | **50 TPS** | **60 TPS** |

60 TPS는 확실히 통과합니다. 하지만 이론상으로는 훨씬 더 올라갔어야 합니다.

### 건당 처리 시간이 늘었습니다

| 리스너 | 스레드 3개일 때 | 스레드 6개일 때 |
|---|---|---|
| `transfer.requested` (출금) | 38.1ms | **57.0ms** |
| `transfer.debited` (입금) | 52.5ms | **80.0ms** |
| 상한 (입금) | 57건/s | **75건/s** |

스레드를 2배로 했는데 상한은 **1.3배**만 올랐습니다. 건당 시간이 **52.5 → 80ms**로
늘었기 때문입니다. 그리고 상한 75건/s에 60 TPS면 **가동률 80%**라, 그 이상에서
지연이 부푸는 것은 대기 이론이 말하는 그대로입니다.

### 늘어난 시간이 어디로 갔나

| | 스레드 3 | 스레드 6 |
|---|---|---|
| **락 보유** | 20.0ms | **51.1ms** |
| 락 대기 | 17.6ms | 16.2ms |
| **커넥션 획득 대기** | — | **0.00ms** |

**임계 구역 자체가 2.5배 느려졌습니다.** 락 대기는 그대로고, 커넥션은 남습니다
(풀을 10 → 30으로 함께 올린 것이 효과가 있었습니다 — 대기가 0입니다).

락 안에서 도는 것은 JPA 트랜잭션 하나뿐이므로, **경합이 MySQL 안으로 옮겨간 것**입니다.
동시에 도는 트랜잭션이 두 배가 되니 각자가 그만큼 느려집니다.

> **이게 스레드를 늘리는 것의 한계입니다.** 자원이 남을 때는(CPU 3~4%, 커넥션 대기 0)
> 스레드가 답이지만, **일 자체가 공유 자원을 두드리면 늘린 만큼 서로 느려집니다.**
> 다음 지렛대는 스레드가 아니라 **건당 80ms를 줄이는 것**입니다.

### 다음에 볼 곳 — 입금 한 건 80ms

한 트랜잭션 안에서 도는 것:

```
processed_events INSERT + flush     멱등 보장
accounts SELECT + 조각 SELECT        (샤딩하며 한 번 늘었다)
조각 UPDATE + flush
outbox INSERT × 2                    다음 단계 이벤트 + 분개장
commit (fsync)
```

의심 순서: **커밋 fsync** → `flush` 두 번 → Outbox INSERT 2회.
group commit이 되고 있는지부터 봐야 합니다.

### 돈은 그대로입니다

| 확인 | 결과 |
|---|---|
| 계좌 잔액 합계 | **1,680,000,000,000.00** |
| 원장 합계 | **1,680,000,000,000** |
| 대사 어긋남 (드레인 후) | **0건** (회차 831) |

부하 중에는 37 → 22 → 1 → 0으로 줄어듭니다. 잔액은 움직였는데 원장이 아직
안 따라온 일시적인 차이입니다.

### 테스트가 세 곳에서 red가 났는데, 전부 같은 이유였습니다

기대값을 상수로 박아둬서 파티션을 바꿀 때마다 **테스트도 함께 고쳐야** 했습니다.
그건 검증이 아니라 **같은 숫자를 두 곳에 적어둔 것**입니다.

- 리스너 테스트 → **브로커에 물어본 파티션 수**에서 끌어옵니다
- `KafkaTopicPartitionTest` → **운영 상수** `KafkaTopicsConfig.PARTITIONS`를 읽습니다

검증하는 명제도 "지금 3인가"에서 **"스레드가 파티션을 남김없이 쓰고 있나"**로 바뀌었습니다.

---

## 입금 80ms를 내부 구간으로 갈랐다 — 아직 최적화하지 않았다 ★

**커밋**: `d51449e`

파티션과 리스너 스레드를 3 → 6으로 늘리자 입금 한 건이 52.5 → 80ms로 느려졌습니다.
지금 필요한 것은 추측으로 `flush`를 지우는 게 아니라, **80ms 중 어느 구간이 동시성 2배에서
부푼 것인지 같은 부하로 확인하는 것**입니다.

`SagaStepExecutor`를 다음 다섯 구간으로 나눠 Micrometer Timer를 심었습니다.

| stage | 실제로 재는 것 |
|---|---|
| `deduplication_flush` | `processed_events` INSERT + flush. 중복을 잔액 변경 전에 잡는 구간 |
| `balance_load` | account와 필요한 잔액 조각 SELECT |
| `balance_flush` | 잔액 조각 UPDATE + flush |
| `outbox_enqueue` | 다음 단계와 분개 이벤트를 영속성 컨텍스트에 넣고 직렬화하는 시간 |
| `deferred_writes_and_commit` | 메서드 본문 뒤로 밀린 Outbox INSERT와 commit 완료까지 |

### Outbox를 INSERT 시간이라고 부르지 않았다

JPA의 `save()`는 SQL을 즉시 보내지 않고 flush/commit까지 미룰 수 있습니다. 호출부만 감싸서
`outbox_insert`라고 이름 붙이면 값은 거의 0인데, 실제 INSERT는 마지막 구간에서 일어나는
**틀린 그래프**가 됩니다. 그래서 앞은 `enqueue`, 실제 DB 지연 쓰기와 커밋은
`deferred_writes_and_commit`으로 갈랐습니다.

전체 트랜잭션은 `remittance.account.saga.transaction`으로 따로 재고, `outcome=committed|rolled_back`을
붙였습니다. 같은 이벤트 재전송이 PK 중복으로 롤백되는 것은 정상 동작인데, 그 짧은 값을 성공 지연과
섞으면 p95가 실제보다 좋아 보이기 때문입니다. transferId·accountId는 태그로 넣지 않았습니다 —
요청마다 값이 달라 Prometheus 시계열 수가 폭발합니다.

Grafana에는 다음 두 패널을 추가했습니다.

- `transfer.debited`의 내부 구간별 p95
- 이벤트별 커밋된 Saga 트랜잭션 전체 p95

### 검증

- 정상 입금에서 다섯 stage와 `outcome=committed`가 각각 정확히 한 번 증가
- 중복 입금 이벤트에서 `outcome=rolled_back`만 증가하고 잔액 조회에는 진입하지 않음
- `markWorkFinished()`를 일부러 제거하자 새 테스트가 red가 되는 것을 확인한 뒤 복원
- `./gradlew test` — **482건 전부 통과**
- Grafana dashboard JSON 파싱 성공, 쿼리 수 23 → **25개**

### 다음 — 홈서버에서 숫자를 채운다

이 커밋은 **보는 수단만 만들었고 성능을 개선하지 않았습니다.** 홈서버에 같은 jar를 올리고,
재기동 직후 실행은 버린 뒤 60 TPS를 같은 조건으로 걸어 다섯 구간의 p95를 채웁니다.
가장 큰 구간 하나만 바꾸고 다시 재는 것이 다음 독립 Step입니다. 새 패널 두 개가 실제 값을
내는지도 그때 확인합니다.

---

## Saga 트랜잭션 내부의 91%는 지연 쓰기+commit이었다 ★★

**측정 커밋**: `ccef131` · **환경**: `home2`, 서비스 CPU 0~9 / k6 CPU 10~11

다섯 서비스가 모두 `ccef131ca52e`인지 `/actuator/info`로 확인하고, account의 락 전략이
`DISTRIBUTED`인지까지 확인했습니다. 60 TPS·8조각을 두 번 돌려 첫 실행은 버렸습니다.

> ### ⚠️ 이 절의 숫자는 한 번 버렸다가 다시 쟀습니다
>
> 처음 잰 값(`ccef131`, 16:12~16:27)은 **MySQL에 `binlog_group_commit_sync_delay=1000`이
> 걸려 있는 상태**에서 나왔습니다. 저장소 설정에 없는 값(기본 0)이고, 앞선 실험에서 켜둔 채
> 되돌리지 않은 것이었습니다. **측정한 쪽은 그게 켜져 있는 줄 몰랐습니다.**
>
> 아래는 **되돌린 뒤(`delay=0`, `07d741c`) 다시 잰 값**입니다. 결론은 그대로였고 숫자만
> 조금 내려갔습니다 — 평균 49.48 → **47.83ms**, p95 114.92 → **102.27ms**.
>
> **교훈은 숫자가 아니라 절차입니다.** 서버 설정을 손으로 바꿨으면 **되돌리는 것까지가 실험**이고,
> 측정 전에 **지금 설정이 저장소와 같은지 확인**해야 합니다. 앱 커밋 해시는 확인했는데
> DB 설정은 아무도 확인하지 않았습니다.

| 실행 | 접수 p99 | 종결 p95 | 종결 p99 | 미발사·시간초과 |
|---|---:|---:|---:|---:|
| 워밍업 — 버림 | 344ms | 23,968ms | 25,734ms | 0 · 0 |
| **본 측정** | **381ms** | **3,173ms** | **3,354ms** | **0 · 0** |

첫 실행을 버리는 규칙이 다시 값을 했습니다. 같은 jar·같은 부하인데 워밍업 p99는 25.7초,
본 측정은 3.4초입니다. 워밍업을 결과로 썼다면 60 TPS가 다시 실패했다고 잘못 결론 냈습니다.

### 같은 시각의 내부 구간 (`delay=0`)

Prometheus의 같은 2분 창입니다. **출금도 함께 재보니 거의 같았습니다** —
이건 입금만의 문제가 아니라는 뜻입니다.

| stage | 입금 평균 | 입금 p95 | 출금 평균 | 출금 p95 |
|---|---:|---:|---:|---:|
| `deduplication_flush` | 0.77ms | 1.55ms | 0.92ms | 1.65ms |
| `balance_load` | 1.79ms | 3.79ms | 1.45ms | 1.97ms |
| `balance_flush` | 0.75ms | 0.97ms | 0.78ms | 0.98ms |
| `outbox_enqueue` | 1.25ms | 1.76ms | 1.47ms | 1.91ms |
| **`deferred_writes_and_commit`** | **47.83ms** | **102.27ms** | **48.35ms** | **98.07ms** |
| **트랜잭션 전체** | **52.40ms** | **119.18ms** | **52.99ms** | **116.00ms** |

다섯 구간의 합이 전체와 정확히 맞습니다(52.39 ≈ 52.40ms).
**지연 쓰기+commit 하나가 평균의 91.3%**이고, 나머지 넷은 p95에서도 전부 4ms 아래입니다.
처리 흔적 flush나 샤드 SELECT를 먼저 고치는 것은 틀린 순서였습니다.

중복 이벤트의 `outcome=rolled_back`은 이 측정 창에 없었습니다. 실패한 짧은 트랜잭션이
성공 지연을 낮춰 보이게 만든 결과도 아닙니다.

### 그래서 commit의 47.8ms는 무엇인가 — 이제 답할 수 있습니다

부하 전후 델타를 함께 잡았습니다(136초 창).

| | 델타 | 초당 |
|---|---:|---:|
| `Com_commit` | +76,854 | **565/s** |
| `Innodb_os_log_fsyncs` | +14,009 | 103/s |
| `Innodb_data_fsyncs` | +39,659 | 292/s |
| `Innodb_log_waits` | **+0** | — |

**group commit은 이미 잘 되고 있습니다 — 커밋 5.5건당 로그 fsync 1회.**
`Innodb_log_waits=0`이라 로그 버퍼가 차서 기다린 것도 아닙니다.

그러면 47.8ms는 **fsync 한 번의 비용이 아니라 줄 서 있는 시간**입니다.
초당 565커밋 × 47.8ms = **항상 27개의 스레드가 커밋 안에 머물러 있습니다.**
`sync_binlog=1` + `innodb_flush_log_at_trx_commit=1`이면 커밋이 3단계(flush→sync→commit)로
직렬화되므로, 여기가 이 시스템의 **공유 관문**입니다.

### 실험 — 그룹 커밋을 더 뭉치게 하면?

`binlog_group_commit_sync_delay`를 줘서 일부러 기다렸다가 더 크게 묶어봤습니다.
**내구성은 그대로**인 설정이라 공짜로 보였습니다.

| 설정 | 60 TPS 종결 p99 |
|---|---:|
| 기본 (`delay=0`) | **3,354ms** |
| `delay=1000µs` | **3,792ms** ← 오히려 나빠짐 |

**이 레버는 죽었습니다.** 이미 5.5건씩 묶고 있어서 더 기다려봐야 지연만 더해집니다.
"뭉치면 빨라진다"는 group commit이 <b>안 되고 있을 때</b>의 처방이었습니다.

### 남은 레버는 하나뿐입니다 — 커밋 수를 줄인다

같은 창에서 송금 1건이 **커밋 10.5회**를 씁니다(7,320건에 76,854커밋).

| DB | 송금 1건당 | 어디서 |
|---|---:|---|
| `transfer_db` | **6.05** | 접수 3(키 선점·송금+Outbox·키 결과) + 상태전이 3 |
| `notification_db` | 3.2 | 알림 2건 × (선점 + 발송) |
| `account_db` | 2.07 | 출금 1 + 입금 1 |

커밋 하나가 47.8ms짜리 관문을 통과해야 하므로, **커밋을 하나 줄이면 그만큼 관문이 비어납니다.**
입금 트랜잭션 자체는 이미 커밋 1회라 더 줄일 수 없습니다 —
**표적은 입금이 아니라 `transfer_db`의 6.05회**입니다.

내구성을 낮추는 선택지(`sync_binlog=0` 등)는 **보지 않습니다.** 커밋했다고 답한 송금이
장비 사고로 사라지는 것은 이 시스템에서 가장 나쁜 결말이고, 그건 지연으로 바꿀 수 있는
종류의 것이 아닙니다.

---

## 목표를 바꿨다 — 배포보다 Saga 용량과 정합성을 먼저 ★

**결정일**: 2026-08-25

Kubernetes까지 빨리 가는 것을 Phase 6의 종료 기준으로 삼지 않기로 했습니다. 현재 병목은
Pod 수가 아니라 송금 한 건이 만드는 **커밋 10.5회와 MySQL의 공유 commit 관문**입니다.
이 상태에서 컨테이너와 K8s를 먼저 붙이면 배포 경험은 얻지만, Pod가 같은 DB 대기열을 더 길게
만들 수 있습니다.

그래서 이 프로젝트의 당면 목표를 다음처럼 좁혔습니다.

> **Choreography Saga와 강한 내구성을 유지하면서 접수·종결 SLO를 만족하는 최대 TPS를 높이고,
> 부하가 끝난 뒤 유실·중복·갇힘·잔액–원장 불일치가 모두 0임을 증명한다.**

TPS만 높으면 성공이 아닙니다. Kafka에 더 많이 쌓는 것은 처리 용량이 아니고,
`sync_binlog=0`처럼 커밋의 의미를 약하게 만드는 것도 선택지에서 제외합니다. `spread`와
`hot-account`를 따로 재고, 과부하 뒤 Kafka·Outbox가 끝까지 drain되는지도 함께 봅니다.

### 대사도 역할을 나눈다

현재 구현은 60초마다 모든 계좌 현재 잔액과 원장 현재 합을 읽습니다. 초기에는 결함을 빨리
발견하는 데 유용했지만, 고TPS 부하 중에는 읽기 부하를 만들고 진행 중인 정상 Saga를 순간적인
불일치로 볼 수 있습니다. 최종 목표는 다음 둘로 분리하는 것입니다.

| 역할 | 실행 방식 |
|---|---|
| 잔액–원장 공식 대사 | `cutoffAt`을 가진 EOD 전체 배치. 스냅샷/`asOf`와 cutoff 이전 이벤트 drain을 보장 |
| 운영 이상 조기 탐지 | 미종결·갇힌 키는 짧은 주기, Kafka lag·Outbox 연령·DLT는 상시 메트릭 |

cron 시각만 바꾸지는 않습니다. account와 ledger를 서로 다른 순간에 읽으면 새벽에 실행해도
거래가 계속 들어오는 시스템에서는 같은 문제가 남습니다. 먼저 기준 시각의 데이터를 양쪽에서
동일하게 읽을 방법을 설계합니다. 대사는 지금처럼 **읽기 전용**이고 자동 보정하지 않습니다.

성능 측정에서는 전체 대사 조건을 A/B 양쪽에 같게 두고, 새 정책으로 바꾼 뒤에는 baseline을
다시 잽니다. 부하를 멈추고 Kafka·Outbox를 drain한 다음 정식 대사를 실행해 발견 0건이어야
그 TPS를 용량으로 인정합니다.

---

## 접수를 한 트랜잭션으로 — 커밋 1회를 줄였는데 용량은 안 움직였다 ★

**커밋**: `7db3622`

### 무엇을 합쳤나

접수는 트랜잭션 셋이었습니다. 뒤의 둘은 **같은 `transfer_db`인데 갈라져 있어 커밋이 두 번**이었습니다.

| | 전 | 후 |
|---|---|---|
| 키 선점 (`reserve`) | 커밋 1 | 커밋 1 (그대로) |
| 송금 + Outbox 저장 | 커밋 2 | **커밋 2로 합침** |
| 키 결과 기록 (`complete`) | 커밋 3 | 〃 |

**키 선점은 합치지 않았습니다.** 별도 커밋이어야 동시에 같은 키로 들어온 요청이 PK 충돌로
곧바로 실패해 재요청 경로로 갑니다. 같은 트랜잭션에 넣으면 충돌 대신 **행 잠금에서 기다리게**
되어, 접수 지연이 상대편 트랜잭션 길이에 묶입니다.

### 합쳐서 실패 모드가 하나 사라졌습니다

갈라져 있을 때는 **"송금은 커밋됐는데 키에는 아직 안 적힌"** 창이 있었습니다. 그 사이에 죽으면
키가 `IN_PROGRESS`로 남고, 재요청이 `recoverInProgress`의 전진 복구를 타야 했습니다.
한 트랜잭션이 되면 **그 상태 자체가 만들어지지 않습니다.**

복구 코드는 **지우지 않았습니다.** 이 변경 전에 만들어진 행이 아직 있을 수 있고,
방어선을 없애는 것과 필요 없게 만드는 것은 다릅니다.

### 커밋은 정확히 1회 줄었습니다

`performance_schema`로 DB별 COMMIT을 셌습니다 (송금 7,320건 기준).

| DB | 전 | 후 |
|---|---:|---:|
| **`transfer_db`** | **6.05** | **5.06** ← 예측대로 |
| `account_db` | 2.07 | 2.07 |
| `notification_db` | 3.2 | **3.93** |

`notification_db`가 늘어난 것은 **줄어들 게 늘어난 게 아니라 전에 덜 세어진 것**입니다.
그때는 notification lag가 5,050까지 밀려 있어서 커밋이 측정 창 밖으로 나갔습니다.
파티션 6으로 따라잡게 되니 이제 제값(4회 = 알림 2건 × 선점+발송)이 나옵니다.

**그래서 실제 총합은 10.5가 아니라 12.05였고, 지금 11.07입니다** (81,037커밋 ÷ 송금 7,320건).

### 그런데 용량은 안 움직였습니다

| | 종결 p99 | 판정 |
|---|---:|---|
| 60 TPS | 3,130ms | ✅ |
| **70 TPS** | **11,613ms** | ❌ |

**용량은 그대로 60 TPS입니다.** 60 TPS의 p99도 실행 간 3,130~4,376ms로 흔들려서
개선분이 편차에 묻힙니다.

당연한 결과입니다 — **12회 중 1회를 줄였으니 8%**이고, 그 정도는 재현 편차보다 작습니다.
**커밋 하나를 줄이는 것으로는 안 됩니다. 자릿수를 바꿔야 합니다.**

### 다음 표적이 분명해졌습니다

지금 커밋 11.07회의 구성입니다.

| DB | 회 | 성격 |
|---|---:|---|
| `transfer_db` | 5.06 | 접수 2 + 상태전이 3 |
| **`notification_db`** | **3.93** | **알림 2건 × (선점 + 발송)** |
| `account_db` | 2.07 | 출금 1 + 입금 1 |

**`notification_db`가 전체의 35.5%를 씁니다.** 그런데 알림은 **종결 경로가 아닙니다** —
잎사귀인데 같은 MySQL의 커밋 관문을 39% 쓰고 있습니다. 여기가 가장 큰 덩어리입니다.

---

## 커밋을 18% 줄여도 안 움직였는데, MySQL 설정 한 줄로 3배 빨라졌다 ★★★

**커밋**: `51bdc3c`(알림) · `dc4d0a5`(redo)

### ① 알림 커밋을 반으로 줄였다

알림이 커밋 관문의 35.5%를 쓰고 있었습니다. **종결 경로도 아닌 잎사귀인데** 완료된 송금
한 건에 커밋을 네 번(수신자 2명 × 자리잡기 + 보냄표시) 썼습니다.

자리잡기와 보냄표시의 2단계는 **그대로 뒀습니다** — "안 가는 것보다 두 번 가는 게 낫다"는
의도적인 선택이라 여기서 바꿀 것이 아닙니다. 대신 **같은 단계의 두 건을 묶었습니다.**

```
claim × 2 + markSent × 2  (커밋 4)   →   claimAll + markSentAll  (커밋 2)
```

실측으로 `notification_db` 커밋이 **28,781 → 14,488, 정확히 절반**이 됐습니다.
송금당 총 커밋 **11.07 → 9.12회 (−18%)**.

**묶으면서 잃을 뻔한 성질이 하나 있습니다.** 발송은 묶을 수 없어서 보낸 사람에게는 나갔는데
받은 사람에게 실패할 수 있는데, 그때 둘 다 `PENDING`으로 남기면 **재배달 때 이미 나간 알림이
한 번 더 나갑니다.** 알림은 회수할 수 없으니 사고입니다. 그래서 `markSentAll`을 `finally`에 두고
**실제로 나간 것만** 표시한 뒤 예외를 올립니다. 묶기 전에는 첫째를 보내고 바로 표시했기 때문에
자연히 안전했던 성질입니다. `finally`를 무력화하면 새 테스트 2건이 red가 되는 것을 확인했습니다.

### ② 그런데 용량이 1건도 안 움직였습니다

| | 70 TPS 종결 p99 |
|---|---:|
| 알림 커밋 줄이기 전 | 11,613ms ❌ |
| 줄인 뒤 | **11,789ms ❌** |

**커밋 18%를 줄였는데 아무 일도 안 일어났습니다.** 커밋 수가 진짜 제약이 아니라는 뜻입니다.
여기서 방향을 틀어 **커밋 자체**를 봤습니다.

### ③ fsync는 2.9ms였습니다

NVMe에 4KB 동기 쓰기 300회를 직접 재봤습니다.

```
300 records, 1.2 MB copied, 0.879936 s   →  건당 2.93ms
```

커밋 평균이 32.5ms인데 fsync는 그중 **9%**뿐입니다. 나머지 30ms가 어디서 오는지 찾다가
MySQL 설정을 봤습니다 — **redo log 100MB, `io_capacity` 200.**
둘 다 **회전 디스크 시절의 기본값**입니다.

### ④ 넷을 바꾼 뒤 하나씩 되돌려 원인을 갈랐다

한꺼번에 바꿔 70 TPS를 통과시킨 다음, **하나씩 되돌리며** 무엇이 효과였는지 찾았습니다.

| 설정 | 70 TPS 종결 p99 |
|---|---:|
| 기본 (redo 100MB) | 11,789ms ❌ |
| `io_capacity` 2000만 | **15,845ms ❌** ← 원인 아님 |
| 버퍼 풀 4GB까지 포함 | 3,608ms ✅ |
| **redo 1GB만** | **3,590ms ✅** ← 이 한 줄이 전부 |

커밋마다 redo를 쓰는데 100MB가 금방 차고, **차면 InnoDB가 체크포인트를 강제로 밀어내느라
커밋을 붙잡습니다.** 커밋 수를 줄여도 이건 안 줄어듭니다 — **총량이 아니라 용량이 문제**였기
때문입니다. ②에서 아무 일도 안 일어난 이유가 여기 있습니다.

**내구성은 그대로입니다.** redo log 크기는 "얼마나 자주 체크포인트를 하느냐"이지
"커밋을 디스크에 쓰느냐"가 아닙니다. `innodb_flush_log_at_trx_commit=1`과 `sync_binlog=1`은
손대지 않았습니다. 대가는 디스크 1GB와 크래시 복구 시간입니다.

> **버퍼 풀은 올리지 않았습니다.** 4GB로 실험했지만 원인이 아니었고, 무엇보다
> **컨테이너 한도가 1.465GiB**라 그대로 뒀으면 OOM으로 죽었을 겁니다.
> `SET GLOBAL`로 바꾼 값이 컨테이너 한도를 넘을 수 있다는 것을 이번에 알았습니다.

### 결과 — 핫 계좌 용량 60 → 70 TPS

| 도착률 | 종결 p99 | 판정 |
|---|---:|---|
| 70 TPS | **3,094ms** | ✅ 지연·회복·정합성 모두 통과 |
| 80 TPS | 7,641ms | ❌ 지연 |

쪼개기 전 25 TPS에서 **2.8배**입니다.

### 배운 것

**"많이 하니까 느리다"와 "한 번이 비싸다"는 다른 문제입니다.**
커밋 수를 18% 줄여도 안 움직였던 것은 병목이 **총량이 아니라 용량**이었기 때문입니다.
숫자를 줄이기 전에 **한 번이 왜 그 값인지**를 먼저 물었어야 했습니다.

그리고 **fsync를 직접 재본 것이 방향을 틀어줬습니다.** 2.9ms라는 값이 없었으면
"커밋은 원래 비싸다"로 넘어가고 계속 커밋 수만 줄이고 있었을 겁니다.

---

## Kotlin으로 무엇을 만들 것인가 — 한도가 아니라 상대 은행 ★

**결정일**: 2026-08-26

### 계기

*"입금과 출금을 지금 같은 account에서 하니까, 다른 은행의 역할 같은 걸 Kotlin으로
구현하는 게 어떨까"* — 이 한 문장이 계획을 바꿨습니다.

맞는 지적입니다. **지금 구조는 송금이 아니라 한 은행 안의 계좌 이체입니다.**
받는 계좌가 같은 DB의 다른 행이라, 사실상 로컬 트랜잭션 옆자리입니다.

### 무엇이 없었나

실제 송금은 상대가 **우리가 제어할 수 없는 남**입니다. 그러면 이 상태가 생깁니다.

```
입금 요청 전송  ─▶  타임아웃
                     들어갔나? 안 들어갔나?
```

**지금 저장소에 이 상태가 존재하지 않습니다.** 그리고 이게 실제 송금에서 가장 어려운
부분입니다 — 재시도하면 이중 입금이고, 실패로 처리하면 돈이 사라집니다.
**답은 재시도가 아니라 조회입니다.**

### 한도 서비스와 비교

| | 한도 서비스 (원래 계획) | **상대 은행** |
|---|---|---|
| 새로 생기는 것 | 홉이 하나 늘어난다 | **결과를 모르는 상태** |
| 문제의 성격 | 이미 있는 문제(지연·용량)의 **양** | **종류가 다른 문제** |
| 실패의 종류 | 업무적 실패 하나 | **타임아웃·5xx·업무적 거절 셋** |
| Kotlin의 명분 | "한도 로직에 `when`이 잘 맞아서" | **"남이 만든 시스템이니까"** |

마지막 줄이 특히 큽니다. 앞의 것은 *"그럼 Java로 못 쓰나요?"*를 받지만,
**다른 조직의 시스템이 다른 언어인 건 설명이 필요 없습니다.**
그게 MSA에서 언어를 섞는 진짜 이유이기도 합니다.

### 부르는 방식은 HTTP 동기

| 후보 | 판단 |
|---|---|
| **HTTP 동기 + 타임아웃** | ← 채택 |
| Kafka 이벤트 | 지금 구조와 일관되지만 **브로커가 재전송을 책임져 불확실성이 사라진다** |

Kafka로 하면 지금까지 만든 것과 잘 어울리지만, 그러면 **이 작업을 하는 이유가 없어집니다.**
"보냈는데 결과를 모른다"가 생기려면 응답을 기다리다 포기하는 구조여야 합니다.
외부 조직과 토픽을 공유하는 것도 현실적이지 않습니다.

### 흉내만 내면 의미가 없다

가장 중요한 조건입니다. **잘 응답하는 서비스를 하나 더 만들면 홉만 늘어난 한도 서비스와
똑같아집니다.** 상대 은행 쪽에 지연·타임아웃·5xx·업무적 거절 스위치를 두고
**일부러 나쁘게 굴게** 만듭니다.

그래야 우리 쪽에 `CREDIT_UNKNOWN`과 **조회 기반 확인 루프**가 생기고,
**재시도해도 되는 실패와 하면 안 되는 실패를 구분**하는 연습이 됩니다.
지금 시스템은 실패가 한 종류뿐이라 그 구분을 할 자리가 없었습니다.

### 남는 것

한도 서비스는 **Phase 6.6으로 미룹니다.** 지우지 않습니다.
다만 **Kotlin으로 할 이유는 사라졌습니다** — 언어를 섞는 이유가 "조직이 다르다"인데
한도는 우리 조직 안이라, Java로 만드는 편이 맞습니다.

그리고 Phase 6 Step 4의 Circuit Breaker·Bulkhead가 **여기서 진짜 근거를 얻습니다.**
지금까지는 "있으면 좋다" 수준이었는데, 느린 상대가 우리 스레드를 묶기 시작하면
그게 실제 문제가 됩니다.

### 대가

**Phase 6에서 잰 용량 숫자가 비교 대상이 아니게 됩니다.** 외부 호출이 지연을 지배하므로,
70 TPS는 "우리 안의 한계"로 남기고 여기서부터는 다른 기준선입니다.

---

## Phase 6 종료 — 용량 40 → 100 TPS, 핫 계좌 25 → 70 TPS ★★★

**종료일**: 2026-08-26 · **커밋**: `ae15f81`

### 종료 조건을 다섯 개 다 통과했다

08-25에 강화한 조건입니다. **TPS 하나만 올리면 실패**로 정해뒀습니다.

| 조건 | 결과 |
|---|---|
| 접수·종결 지연 | ✅ 100 TPS에서 접수 p99 329ms · 종결 p99 4,673ms |
| 유실·중복·갇힘 0 | ✅ **알림 956,864 = COMPLETED 478,432 × 2 정확히 일치**. DLT 0, 갇힌 키 0 |
| 잔액–원장 불일치 | ✅ drain 뒤 정식 대사 **어긋남 0건** (계좌 3,903건 대조) |
| 과부하 회복 | ✅ 요청을 끊으면 미종결·Outbox·Kafka lag이 전부 0까지 빠진다 |
| 숫자의 재현성 | ✅ `measure-hot-account.sh`가 워밍업·드레인·DB 설정을 강제한다 |

**내구성은 한 번도 낮추지 않았습니다.** `innodb_flush_log_at_trx_commit=1`,
`sync_binlog=1` 그대로입니다.

### 용량 — 40 → 100 TPS (2.5배)

같은 시나리오(`capacity.js`), 같은 절차입니다.

| 도착률 | 2026-08-23 | 2026-08-26 |
|---|---:|---:|
| 40 TPS | 2,611ms ✅ | 2,107ms ✅ |
| 60 TPS | **7,721ms ❌** | 2,656ms ✅ |
| 80 TPS | **42,844ms ❌** | 2,694ms ✅ |
| 100 TPS | **59,578ms ❌** (성공률 82.98%) | **4,673ms ✅ (100%)** |
| 120 TPS | 59,881ms ❌ (성공률 **26.89%**) | 18,874ms ❌ (100%) |

**성공률이 전 구간 100%가 된 것도 큰 변화입니다.** 전에는 100 TPS에서 17%,
120 TPS에서 73%가 60초 안에 못 끝났습니다. 지금은 120 TPS에서도 **늦을 뿐 다 끝납니다.**

핫 계좌는 따로 **25 → 70 TPS (2.8배)**. 입금이 한 계좌로 몰리면 여전히 더 낮습니다.

### 무엇이 얼마나 기여했나

| 단계 | 핫 계좌 용량 | 무엇을 알았나 |
|---|---:|---|
| baseline | 25 TPS | 한 계좌 잔액이 한 행이라 입금이 줄을 선다 |
| 잔액 샤딩 | **50 TPS** | 쪼개는 것의 절반은 **락을 쪼개는 것** |
| 파티션 3 → 6 | **60 TPS** | 스레드를 2배로 해도 **건당 시간이 함께 늘어** 1.2배뿐 |
| 접수 커밋 −1 | 60 TPS | **12분의 1은 편차에 묻힌다** |
| redo 100MB → 1GB | **70 TPS** | **총량이 아니라 용량이 문제였다** |

### 여기서 멈추는 이유

80 TPS(핫 계좌)에서 무엇이 막는지는 알아냈습니다.

| | 값 |
|---|---:|
| 입금 리스너 건당 | **60.7ms** × 6스레드 = 상한 **99건/s** |
| 그중 지연 쓰기+commit | 38.1ms (**63%**) |
| 80 TPS의 가동률 | **81%** → 대기행렬이 부푼다 |

**더 가는 길은 둘 다 이미 답을 봤습니다.**

- **스레드를 더 늘린다** — 3 → 6에서 건당 시간이 52.5 → 80ms로 함께 늘어 **1.2배뿐**이었습니다.
  6 → 12도 같은 벽에 부딪힙니다.
- **건당 60.7ms를 줄인다** — 63%가 커밋이라 **커밋을 자릿수로 줄여야** 하고,
  그건 Saga의 트랜잭션 경계를 다시 긋는 일입니다. **Phase 6의 범위를 넘습니다.**

그래서 여기서 닫습니다. **한계를 못 넘어서가 아니라, 다음 한 걸음이 다른 종류의 작업이라서**입니다.

### Phase 6이 남긴 것 — 숫자보다 방법

이 Phase에서 배운 것 중 **틀렸던 것을 찾아낸 방식**이 더 오래 남을 것 같습니다.

| 무엇이 틀렸나 | 어떻게 들켰나 |
|---|---|
| 식은 실행과 데워진 실행을 비교 (15.4배!) | **락 지표가 안 맞았다** — 임계 구역이 그대로인데 15배가 좋아질 수 없다 |
| `RATE`가 고정이 아니라 램프였다 | 다음 병목을 보려고 숫자를 다시 들여다보다가 |
| "처리량"이 접수가 아니라 전체 요청이었다 | k6 원본 출력을 통째로 봤더니 `60.00 iters/s`라고 적혀 있었다 |
| 실험용 DB 설정을 켜둔 채 측정 | 다음 사람이 같은 조건인 줄 알고 쟀다 |
| 스크립트가 SLO 실패를 "통과"라고 찍었다 | 70 TPS가 p99 11.7초인데 ✅가 떴다 |

**다섯 번 다 같은 뿌리입니다 — 도구가 무엇을 재는지 확인하지 않고 이름만 믿었습니다.**
그래서 규칙을 문서가 아니라 **스크립트에** 넣었습니다. 사람이 기억할 필요가 없어야 합니다.

### 다음

Phase 6.5 **상대 은행**(Kotlin, HTTP)입니다. 지금 입금과 출금이 같은 DB에서 일어나
**"보냈는데 결과를 모른다"는 상태가 아예 없는데**, 그게 실제 송금에서 가장 어려운 부분입니다.

**여기서 잰 용량은 그때 비교 대상이 아니게 됩니다** — 외부 호출이 지연을 지배하므로,
100 TPS·70 TPS는 **"우리 안의 한계"**로 남깁니다.

---

## 상대 은행이 섰다 — "돈은 들어갔는데 응답이 없다"를 처음으로 만들어봤다 ★★

**커밋**: `c0a6251`(서비스) · `37fb412`(배선) · **Phase 6.5 Step 1**

### 무엇을 만들었나

`external-bank-service` — **Kotlin**, 포트 8086, `external_bank_db`. 계약은 둘뿐입니다.

```
POST /transfers/{transferId}/credit    입금 요청 (멱등성 키 = transferId)
GET  /transfers/{transferId}           거래 조회 — 타임아웃 뒤 결과를 아는 유일한 방법
POST /faults                           얼마나 나쁘게 굴지 (런타임)
```

### 홈서버에서 실제로 확인한 것

**Testcontainers가 아니라 진짜 HTTP, 진짜 타임아웃으로** 봤습니다.

```
$ curl -X POST localhost:8086/faults -d '{"timeoutRate":1.0}'
$ curl --max-time 3 -X POST localhost:8086/transfers/b8fec984-.../credit ...
   curl 종료코드=28 (타임아웃)   응답코드=[000]   걸린시간=3011ms

★ 그런데 돈은?
   b8fec984-5f08-415e-b17f-be2dd1dc0a9e | 50000.00 | ACCEPTED | 08:08:48
```

**보내는 쪽은 아무 답도 못 받았는데 돈은 들어가 있습니다.** 이 저장소에 이 상태가 생긴 것은
처음입니다. 그리고 **조회하면 알 수 있습니다** — 그게 유일한 수단입니다.

### 세 실패가 서로 다른 뜻을 갖는다

| 결함 | 응답 | **DB에 남았나** | 보내는 쪽이 해야 할 일 |
|---|---|---|---|
| `timeoutRate` | 없음 (curl 28) | **남았다** | 재시도 금지 · **조회** |
| `errorRate` | 503 | **0건** | 그대로 재시도 → 확인함 |
| `rejectRate` | 200 REJECTED | 남았다 (확정) | 다시 보내도 REJECTED → 확인함 |

지금까지 이 시스템의 실패는 **한 종류(업무적 실패)뿐**이라 이 구분을 연습할 자리가 없었습니다.

### 테스트가 설계 결함을 잡았다 ★

처음에는 "응답을 삼킨다"를 **서비스 안에서 예외로 던졌습니다.** 그랬더니
**트랜잭션이 함께 롤백되어 입금이 남지 않았습니다.**

그러면 타임아웃이 *"아무 일도 안 일어남"*이 되고, 보내는 쪽은 그냥 다시 보내면 됩니다 —
**이 서비스의 존재 이유가 통째로 사라집니다.**

```
지연 ─▶ 5xx?(업무 전) ─▶ [입금 커밋] ─▶ 응답 삼키기?(커밋 후)
                            ↑ 커밋을 사이에 두는 것이 전부다
```

결함 주입을 트랜잭션 밖(컨트롤러)으로 옮겼습니다. 층으로 보면 당연합니다 —
**삼키는 것은 전송의 문제이지 업무의 문제가 아닙니다.**
되돌리면 그 두 테스트가 red가 되는 것을 확인했습니다.

### 띄워보길 잘했다 — 세 개가 막혀 있었다

Step 2로 바로 갔으면 Saga 변경과 한꺼번에 터졌을 것들입니다.

| 막힌 것 | |
|---|---|
| `external_bank_db`가 서버에 **없었다** | `01-databases.sql`은 **볼륨이 비었을 때만** 돈다. `notification_db` 때 데인 함정에 **두 번째로** 걸렸다 |
| 배포 스크립트가 다섯 개만 안다 | `SERVICES`·`bootJar` 목록 둘 다 |
| Prometheus가 8086을 안 긁는다 | 설정을 고쳤는데도 안 됐다 — git이 파일을 새로 써서 **바인드 마운트가 옛 inode를 붙들고 있었다.** 컨테이너를 다시 만들어야 했다 |

마지막 것은 몰랐던 함정입니다. **파일 하나를 바인드 마운트하면 내용을 고쳐도 반영되지 않습니다.**
`/-/reload`를 불러도 소용없습니다 — Prometheus는 자기가 보는 옛 파일을 다시 읽을 뿐입니다.

### Kotlin에 대해

- `allWarningsAsErrors`가 **첫날부터 값을 했습니다.** Testcontainers의 옛 `MySQLContainer`가
  deprecated인데 Java 모듈들은 경고만 나서 아직 쓰고 있습니다.
- `MetricsDistributionConfig`의 **여섯 번째 복사본**을 Kotlin으로 만들었습니다.
  **언어가 달라도 정책은 같아야 한다**는 것이 오히려 확인하려던 것이기도 합니다.
  이 서비스만 버킷 상한이 60초인데, **일부러 30초를 매달아 두는 상대**라 그 구간을 봐야 합니다.

### 아직 안 한 것

**우리 쪽은 하나도 안 건드렸습니다.** 상대 은행은 혼자 서 있고 아무도 부르지 않습니다.
Step 2가 진짜 어려운 쪽입니다 — `CREDIT_UNKNOWN`, 조회 기반 확인 루프,
계좌의 은행 코드, 대사의 "우리도 상대도 모르는 돈".

---

## 외부 송금이 끝까지 갔다 — 그리고 목으로는 못 잡는 버그를 잡았다 ★★★

**커밋**: `c502fbb`(Step 2a) · `5f06fb0`(Step 2b) · `16c5ad5`(수정) · **환경**: `home2`

### 마이그레이션이 실전 데이터를 지나갔다

| DB | 마이그레이션 | 걸린 시간 |
|---|---|---:|
| account | V3 정산 계좌 | 0.6초 |
| account | V4 확인 대기 | 0.1초 |
| **transfer** | **V2 외부 목적지** (478,432행) | **39.2초** |
| transfer | V3 CREDIT_UNKNOWN | 0.0초 |

**47만 행 `ALTER TABLE`이 39초입니다.** Phase 7에서 무중단 배포를 다룰 때 필요한 숫자입니다 —
이만큼 테이블이 잠긴다는 뜻이라, 그 사이 접수가 멈춥니다.

### 행복 경로 — 설계대로 정확히

```
접수 → 출금 → 상대 은행 호출 → 정산 계좌 입금 → 원장 두 다리 → COMPLETED   (2초)
```

| 확인 | 값 |
|---|---|
| 고객 계좌 | 1,000,000 → **950,000** |
| 정산 계좌 (자동 생성) | `SETTLEMENT` · KB · **50,000** |
| 상대 은행 장부 | `ACCEPTED` |
| **우리 원장** | `TRANSFER_DEBIT`(고객) + `TRANSFER_CREDIT`(정산) — **두 다리** |

**"정산 계좌를 두면 원장·대사를 안 고쳐도 된다"는 판단이 실물로 확인됐습니다.**

### ⚠️ 그리고 목으로는 못 잡는 버그를 만났다

`timeoutRate=1`로 놓고 보냈더니 **"모르는 상태"가 만들어지지 않고 메시지가 DLT로 죽었습니다.**
돈은 나갔을 수 있는데 아무도 확인하지 않는, **이 Phase가 없애려던 바로 그 상태**입니다.

원인은 타임아웃을 **예외 타입 하나로 판별**한 것이었습니다.

```
기대: ResourceAccessException
실제: RestClientException: Error while extracting response ... [application/octet-stream]
        Caused by: java.net.SocketTimeoutException: Read timed out
```

**응답 헤더는 받았는데 본문을 읽다가 끊긴** 경우라 다른 자리에서 감싸집니다.

> **단위 테스트도 통합 테스트도 통과했었습니다** — 거기서는 클라이언트가 **목**이었기 때문입니다.
> 제가 `ExternalCreditUnknownException`을 던지도록 스텁해놓고 "잘 잡힌다"를 확인한 셈입니다.
> **진짜 소켓이 끊겨봐야 나오는 종류**입니다.

고친 방식은 타입이 아니라 **원인 사슬에 `IOException`이 있는지**를 보는 것입니다
(`KafkaErrorHandlingConfig`의 경합 판별과 같은 방식). `static`으로 두어
**실제로 관측된 예외 모양을 그대로 넣는 테스트**를 붙였습니다.

### 고치고 나니 이렇게 됐다

```
10:05:15 WARN  상대 은행이 답하지 않아 결과를 모른다 - 조회로 확인한다
10:05:16 INFO  조회로 확인했다 - 상대가 받았다
```

이벤트 흐름도 그대로 남았습니다.

```
transfer.debited → transfer.credit-unknown → transfer.credited → COMPLETED
                       ↑ 답이 없었다            ↑ 조회로 확인됐다
```

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| 같은 조건(`timeoutRate=1`) | **DLT로 죽음 · `DEBIT_COMPLETED`** | **`COMPLETED`** |

### 대사가 제 역할을 했다

마지막 대사에서 **어긋남 1건**이 나왔습니다 — 바로 그 **버그로 죽은 송금**입니다.

```
UNSETTLED_TRANSFER  673b4cdc  DEBIT_COMPLETED 상태로 09:58:41부터 멈춰 있다
```

**만들어둔 안전망이 실제 사고를 잡은 첫 사례입니다.** 대사가 없었으면 그 송금이
죽은 줄도 몰랐을 것이고, 저는 "고쳤다"고 적고 넘어갔을 겁니다.

### 남는 것

- 미해소 건수 지표 `remittance.external.credit.unknown` = **0**
- 정산 계좌 KB = **80,000** (성공 5만 + 조회로 해소된 3만)
- 죽은 송금 `673b4cdc` 한 건은 **일부러 남겨둡니다** —
  대사가 잡은 실물이고, 나중에 "갇힌 건을 어떻게 되살리나"의 재료가 됩니다

---

## 느린 상대가 우리 내부 송금을 19배 느리게 만든다 ★★★

**시나리오**: `mixed-bank.js` (`c688c47`) · **환경**: `home2`, 30 TPS · 외부 비율 20%

### 같은 부하, 상대만 바꿨다

상대 은행의 지연은 **k6가 아니라 그쪽 설정**으로 줬습니다. 부하도 코드도 그대로이고
**상대만 다릅니다** — 그래야 무엇 때문에 숫자가 바뀌었는지 말할 수 있습니다.

| | 상대 정상 | **상대 2초 느림** |
|---|---:|---:|
| **내부 송금 종결 p99** | **3,071ms** | **58,790ms** |
| 외부 송금 종결 p99 | 3,916ms | 52,684ms |
| 종결 성공률 | 1.00 | **0.53** |
| 시간초과 | 0 | **67건** |
| **접수 p99** | 103ms | **95ms** |

**내부 송금이 19배 느려졌습니다.** 내부 송금은 상대 은행과 <b>아무 상관이 없습니다</b> —
같은 은행 안에서 계좌 사이를 옮기는 것뿐입니다. 그런데 남의 사정으로 절반이 60초 안에
끝나지 못했습니다.

> **그리고 접수는 여전히 95ms입니다.** 접수만 보면 아무 문제가 없어 보입니다.
> 이 저장소가 "202는 성공이 아니다"를 계속 붙잡는 이유가 여기서 또 나옵니다.

### 왜 그런지는 계산이 맞아떨어진다

| 리스너 | 평균 처리 시간 | 6스레드 상한 |
|---|---:|---:|
| `transfer.requested` (출금) | 33.3ms | 180건/s |
| **`transfer.debited` (입금)** | **168.1ms** | **35.7건/s** |

입금 리스너 하나가 **내부 입금과 외부 호출을 같이** 처리합니다.

```
외부 6건/s × 2초 = 12 스레드-초/초   >   스레드 6개
```

**외부 호출만으로 이미 스레드를 다 씁니다.** 내부 입금은 그 뒤에 줄을 섭니다.
부하 중 `transfer.debited`의 lag가 파티션마다 250~350까지 올라갔고,
`transfer.requested`는 0이었습니다 — **막힌 곳이 정확히 거기**라는 뜻입니다.

### 이게 격벽(bulkhead)의 근거다

ROADMAP의 Circuit Breaker·Bulkhead 항목은 지금까지 **"있으면 좋다"** 수준이었습니다.
이제 근거가 숫자로 있습니다. 고쳐야 할 것은 하나입니다.

> **남의 사정으로 우리 일이 멈추면 안 된다.**

수단은 몇 가지가 있고, 다음 Step에서 재보고 고릅니다.
- **격벽** — 외부 호출용 스레드를 따로 준다. 내부 입금은 그 영향을 안 받는다
- **회로 차단기** — 상대가 계속 느리면 아예 부르지 않고 곧바로 "모름"으로 보낸다
- **리스너 분리** — 외부 송금을 별도 토픽·별도 컨슈머로 뺀다

### ⚠️ 이 측정에서 정직하게 적을 것

`미발사 50건`이 나왔습니다. k6가 VU를 제때 못 잡아 **부하가 온전히 안 걸렸다**는 뜻이고,
엄밀히는 30 TPS가 다 들어가지 않았습니다. 다만 결론(내부가 19배 나빠진다)은
그 정도 오차로 흔들리지 않아 그대로 씁니다. **다음 측정에서는 `preAllocatedVUs`를 올려야
합니다** — 느린 상대를 재는 시나리오는 VU를 훨씬 많이 잡아먹습니다.

---

## 격벽을 넣었다 — 그리고 스케줄러 스레드 하나가 전부를 막았다 ★★★

**커밋**: `d98fd47`(격벽) · `16052a3`(스케줄러) · **환경**: `home2`, 30 TPS · 외부 20% · 상대 2초 지연

### ⚠️ 먼저: 격벽을 넣자 시스템이 통째로 멈췄다

첫 재측정에서 **종결 성공률 0.00**이 나왔습니다. 내부 송금까지 전부입니다.
격벽이 문제가 아니라 **아무도 안 보던 공유 자원**이 문제였습니다.

```
account outbox 미발행 = 7,368건
스케줄러 스레드       = [scheduling-1]   ← 하나뿐
```

`@Scheduled`의 **기본 풀 크기가 1**입니다. 상대 은행 조회 루프가 그 하나를 오래 붙들었고,
**같은 스레드로 도는 Outbox 릴레이가 굶었습니다.** 발행이 멈추면 아무것도 종결되지 않습니다.

> **격벽으로 컨슈머 스레드는 지켰는데 정작 스케줄러가 새 단일 자원이 됐습니다.**
> 느린 외부 호출을 스케줄러에서 하는 한, 풀이 1이면 그 하나가 전부를 막습니다.

풀을 4로 키우고 조회 배치를 50 → 10으로 줄였습니다. **풀만 키우면 부족합니다** —
한 틱을 짧게 유지하지 않으면 같은 일이 규모만 바뀌어 반복됩니다.

### 고치고 나서: 격벽의 효과

같은 조건(30 TPS · 외부 20% · 상대 2초)입니다.

| | 격벽 없음 | **격벽 + 스케줄러 수정** |
|---|---:|---:|
| **내부 송금 종결 p99** | **58,790ms** | **11,579ms** |
| 종결 성공률 | 0.53 | **0.82** |
| 시간초과 | 67건 | 42건 |
| 미발사 | 50 | **0** |

**내부 송금 p99가 5배 좋아졌습니다.** 그리고 `미발사 0` — 이번엔 30 TPS가 온전히 걸렸습니다.

### 그런데 아직 SLO를 못 지킨다 (p99 11.6초 > 5초)

**예상한 대로입니다.** 격벽 코드 주석에 미리 적어둔 그대로입니다.

> 격벽은 처리량을 만들어주지 않는다. 스레드 6개가 12 스레드-초의 일을 할 수는 없다.
> 하는 일은 **피해를 가두는 것**이다.

숫자가 그걸 보여줍니다.

| | 값 |
|---|---:|
| 격벽 통과 / 거절 | 1,273 / **1,662** |
| 남은 대기 | 미전송 **920** · 모름 369 |
| 입금 리스너 평균 | 168.1 → **104.8ms** |

**거절이 통과보다 많습니다.** 외부는 6건/s로 들어오는데 격벽 정원 2 ÷ 2초 = **1건/s**밖에
못 내보냅니다. 나머지는 계속 쌓입니다 — 미전송이 920건입니다.

그 쌓인 것을 조회 루프가 처리하려 들면서 **DB 쓰기와 스케줄러 일이 늘고**,
그게 내부 송금에도 비용으로 돌아옵니다. 완전히 갈라놓지는 못한 것입니다.

### 다음 표적

**상대가 감당 못 할 만큼 느리면 부르는 것 자체를 멈춰야 합니다.**

- **회로 차단기** — 계속 느리면 아예 안 부르고 곧바로 "미전송"으로. 쌓이는 건 같지만
  **왕복 비용과 스레드 점유가 사라진다**
- **리스너 분리** — 외부 송금을 별도 토픽·컨슈머로. 내부와 자원을 아예 안 나눠 쓴다

지금 숫자로는 **리스너 분리가 더 근본적**입니다. 격벽은 같은 풀을 나눠 쓰는 것이라
"조금 덜 뺏기는" 데까지이고, 분리는 애초에 안 뺏깁니다.

### 배운 것

**병목을 하나 막으면 다음 단일 자원이 드러납니다.** 컨슈머 스레드를 지켰더니
스케줄러가 나왔습니다. 스케줄러를 고쳤더니 이번엔 격벽 정원 자체가 상한이 됐습니다.
**각 단계에서 "지금 무엇이 하나뿐인가"를 물어야** 합니다.

---

## 리스너를 분리했다 — 남의 사정으로 우리 일이 멈추지 않는다 ★★★

**커밋**: `1019e41` · **환경**: `home1`(집으로 돌아옴), 30 TPS · 외부 20% · 상대 2초 지연

### 세 번 재서 세 숫자

같은 부하, 같은 상대. **우리 쪽 구조만** 바꿨습니다.

| | 내부 종결 p99 | 내부 성공률 | 판정 |
|---|---:|---:|---|
| 한 리스너 | **58,790ms** | — | ❌ |
| 한 리스너 + 격벽 | 11,579ms | — | ❌ |
| **리스너 분리** | **2,587ms** | **1.00** | **✅** |

**내부 송금이 완전히 회복됐습니다.** 2,587ms는 상대가 **정상일 때의 3,071ms보다도 낮습니다** —
내부 리스너가 이제 외부 일을 아예 안 하기 때문입니다.

외부는 같은 실행에서 **성공률 0.00**입니다. 상대가 2초씩 걸리는데 6건/s가 들어오니
외부는 밀릴 수밖에 없습니다. **그게 맞는 결과입니다** —
목표는 외부를 빠르게 만드는 것이 아니라 **그 피해를 외부에만 가두는 것**이었습니다.

### 격벽으로는 왜 부족했나

격벽은 **같은 풀을 나눠 쓰면서 덜 뺏기는** 방법입니다. 그래서 거기까지였습니다.

```
거절 1,662  >  통과 1,273     ← 정원 2 ÷ 2초 = 1건/s인데 6건/s가 온다
미전송 920건 쌓임              ← 그걸 처리하는 일이 다시 내부에 비용이 된다
```

**나눠 쓰지 않으면 애초에 뺏기지 않습니다.**

### 어떻게 나눴나

**새 토픽을 만들지 않았습니다.** 같은 `transfer.debited`를 **두 컨슈머 그룹**이 각자 읽고
자기 몫만 처리합니다. 새 토픽을 만들면 발행하는 쪽과 `transfer-service`까지 손봐야 합니다.

메시지를 두 번 읽는 것이 대가인데, 남의 몫은 **JSON 한 번 읽고 버리는 것**뿐이라 쌉니다.

> ⚠️ **새 그룹은 처음 뜰 때 토픽을 처음부터 읽습니다.** 밀린 3만 건을 **35초**에 따라잡았습니다.
> `latest`로 두면 배포와 기동 사이에 발행된 외부 송금이 **통째로 사라지므로** 그쪽이 훨씬 나쁩니다.
> 처리 흔적이 있어 두 번 처리되지는 않습니다.

### 격벽은 지웠나 — 아니다, 역할이 바뀌었다

리스너 스레드 수가 이미 상한이므로, 격벽은 이제 **리스너와 조회 루프를 합친
이 서비스 전체의 외부 호출 동시성**에 상한을 둡니다. 리스너(6)를 조이지 않도록 2 → 8로 올렸습니다.

### 이 세 단계가 남긴 것

| 단계 | 무엇을 배웠나 |
|---|---|
| 재현 | 느린 상대가 **우리 내부 송금**을 19배 느리게 만든다. 접수는 멀쩡해 보인다 |
| 격벽 | 피해를 **가둔다.** 다만 같은 풀을 나눠 쓰는 한 거기까지다 |
| **스케줄러** | 병목을 막으면 **다음 단일 자원**이 드러난다. 풀이 1이라 릴레이가 굶었다 |
| 분리 | **나눠 쓰지 않으면 뺏기지 않는다** |

**"지금 무엇이 하나뿐인가"를 매 단계 물어야 합니다.** 컨슈머 스레드 → 스케줄러 →
격벽 정원 순으로 드러났습니다.

### 남는 것

외부 송금 자체는 여전히 밀립니다. 상대가 감당 못 할 만큼 느리면 **부르는 것을 멈추는**
판단이 필요합니다 — 회로 차단기입니다. 지금은 계속 두드리며 왕복 비용을 치르고 있습니다.

---

## 회로 차단기가 없다는 것을 실패 테스트로 고정했다

**커밋**: `c8ca911` · **Phase 6.5 회로 차단기 재현**

같은 상대 은행이 연속으로 답하지 않는데도 호출을 계속하는 현재 동작을 테스트로 남겼습니다.
10건을 연달아 보내면 기대 계약은 **처음 5건까지만 호출하고 회로를 여는 것**인데,
현재 코드는 10건을 모두 호출해 `TooManyActualInvocations`로 실패합니다.

차단된 뒤의 5건은 `CREDIT_UNKNOWN`이 아닙니다. 회로가 열려 **호출 자체를 하지 않았으므로**
돈이 나갔을 가능성이 없고, `미전송`으로 보관해야 합니다. 그래서 테스트는 다음 둘을 함께 봅니다.

- 외부 HTTP 호출은 5회에서 멈춘다
- 대기 기록은 10건 모두 남지만 `transfer.credit-unknown`은 실제로 호출한 5건만 발행한다

`reproductionTest`에서 의도대로 red이고, 기본 `test`는 이 태그를 제외한 기존
`UnknownCreditTest` 경로가 모두 green입니다. 다음 Step에서 CLOSED → OPEN → HALF_OPEN 전이와
은행별 격리, 차단 지표를 구현해 이 테스트를 green으로 바꿉니다.

---

## 회로 차단기를 직접 구현했다 — 새 입금은 멈추되 모르는 돈의 조회는 멈추지 않는다

**커밋**: `0b1f0a9` · **Phase 6.5 회로 차단기**

같은 은행의 새 입금 요청이 5번 연속 실패하면 회로를 엽니다. 30초 동안 실제 HTTP 호출 없이
미전송으로 보관하고, 시간이 지나면 단 한 건만 HALF_OPEN 시험 호출로 허용합니다. 성공하면
CLOSED, 실패하면 다시 OPEN입니다. 회로는 은행별이라 한 상대의 장애가 다른 은행을 막지 않습니다.

### 차단 범위는 POST뿐이다

처음에는 입금 POST와 거래 조회 GET을 같은 회로로 감쌌습니다. 하지만 GET은 이미 보낸 돈이
어디 있는지 확인하는 유일한 수단입니다. 새 요청을 보호하려고 GET까지 막으면
`CREDIT_UNKNOWN` 해소가 늦어집니다. 그래서 역할을 나눴습니다.

- 입금 POST: 격벽 + 회로 차단기
- 결과 조회 GET: 격벽 + 지수 백오프, **회로 차단 대상 아님**

주소를 모르는 은행도 실패 횟수에서 제외했습니다. 실제 HTTP 호출 전에 난 우리 설정 오류를
상대 장애로 세면 원인이 다른 실패를 한 지표와 한 상태로 섞게 됩니다.

### 미전송과 모르는 상태의 경계

회로가 OPEN이면 호출하지 않았으므로 `sent=false`입니다. 실제 호출을 허가받은 뒤 HTTP 직전에만
`sent=true`를 영속화합니다. 순서가 반대면 차단된 건을 보낸 것으로 오인해 불필요한 조회를 하고,
호출 뒤에 표시하면 그 사이 프로세스가 죽을 때 이중 전송할 수 있습니다.

### 검증

- 상태 머신 단위 테스트 8건: 임계값, 성공 시 초기화, 은행별 격리, HALF_OPEN 단일 시험,
  재실패, 차단 시 사전 작업 미실행, 설정 오류 제외
- 재현 테스트 green: 10건 중 HTTP 5회, `sent=true` 5건, 미전송 5건,
  `transfer.credit-unknown` 5건
- 구현을 잠시 제거하자 같은 테스트가 `TooManyActualInvocations`로 다시 red
- `./gradlew test`: **530건 전체 통과**, 2분 15초

다음 Step은 직접 만든 `Semaphore` 격벽과 이 상태 머신을 Resilience4j로 교체하는 것입니다.
동작 계약은 지금 만든 테스트가 그대로 지킵니다.

---

## 직접 만든 장애 격리를 Resilience4j로 교체했다

**커밋**: `ccb3175` · **결정**: `DECISIONS.md` D-002

직접 만든 `Semaphore` 격벽과 은행별 CLOSED → OPEN → HALF_OPEN 상태 머신을
Resilience4j 2.4.0의 `Bulkhead`와 `CircuitBreaker`로 교체했습니다. Spring Boot 4용 자동 설정에
기대지 않고 필요한 코어 모듈과 Micrometer 연동만 직접 조립했습니다.

### 동작 계약은 바꾸지 않았다

- 격벽은 정원 8을 넘으면 기다리지 않고 즉시 거절한다 (`maxWaitDuration=0`)
- 회로는 은행별이며 최근 5건이 전부 실패했을 때 열린다. count window 5 + 실패율 100%라
  중간 성공 한 건이 연속 실패를 끊는다
- OPEN 30초 뒤 HALF_OPEN 시험은 한 건만 허용한다
- 조회 GET은 회로를 우회하고 격벽·백오프만 적용한다
- 차단된 호출은 `sent=false`, 허가된 호출만 HTTP 직전에 `sent=true`로 저장한다
- HTTP 전 DB 실패와 알 수 없는 은행 주소는 상대 은행 실패율에 넣지 않는다

마지막 두 계약 때문에 애너테이션만 붙이지 않았습니다. 회로 허가를 얻은 뒤 `sent=true`를
영속화하고, 그 로컬 작업이 실패하면 허가를 반환해야 해서 Resilience4j의 저수준 permission API를
작은 어댑터 안에서 사용합니다.

### 검증

- 회로 차단기 9건 + 격벽 5건: 상태 전이, 은행 격리, 단일 HALF_OPEN 시험, 즉시 거절,
  허가 반환, 표준 Micrometer 지표
- 통합 테스트: 10건 연속 타임아웃에서 HTTP 5회, `sent=true` 5건, 미전송 5건 유지
- 최초 전송 경로에서 회로를 잠시 우회하자 같은 테스트가 `TooManyActualInvocations`로 red,
  원복 후 green
- `./gradlew test`: **532건 전체 통과**, 2분 58초

### 겪은 것

- 테스트용 시계 생성자를 추가하자 Spring이 기본 생성자를 찾다가 컨텍스트가 실패했습니다.
  운영 생성자를 `@Autowired`로 명시해 해결했습니다.
- Resilience4j의 OPEN 종료 판단은 `Clock`을 봅니다. 시스템 시각 보정으로 30초가 흔들리지 않도록
  기동 시각에 `System.nanoTime()` 경과량을 더하는 단조 증가 `Clock`을 넣었습니다.
- 표준 지표 이름은 자체 `remittance.external.*`에서 `resilience4j.circuitbreaker.*`와
  `resilience4j.bulkhead.*`로 바뀌었습니다. Phase 9 대시보드는 이 이름을 기준으로 만듭니다.

다음 Step은 홈서버에서 직접 구현 때와 같은 느린 상대·연속 실패 시나리오를 재측정하는 것입니다.
라이브러리 교체는 기능 회귀만 확인했고, 성능이 같다는 주장은 아직 하지 않습니다.

---

## 재측정하러 가기 전에 — 잴 수 없게 된 것과, 한 건이 줄을 막는 것 ★

**Phase 6.5 재측정 준비**

다음 Step이 "홈서버에서 **같은 장애 시나리오로 재측정**"인데, 그러기 전에 두 가지가 걸렸습니다.
둘 다 기능 테스트로는 안 잡히는 종류입니다 — 하나는 **잴 수 없게 된 것**이고,
하나는 **평소에는 안 보이는 것**입니다.

### ① 격벽을 라이브러리로 옮기면서 판정 근거를 잃었다

Resilience4j의 `TaggedBulkheadMetrics`가 내는 것은 **게이지 둘뿐**입니다.

```
resilience4j.bulkhead.available.concurrent.calls    남은 자리 (순간값)
resilience4j.bulkhead.max.allowed.concurrent.calls  정원     (순간값)
```

그런데 격벽을 판정할 때 우리가 봐온 것은 순간값이 아니라 **거절 수**였습니다.
정원 2를 8로 올린 근거가 바로 그 숫자입니다 — **"거절 1,662 > 통과 1,273"**.
직접 만든 격벽에는 있던 `remittance.external.bulkhead.rejected` · `.admitted`가
교체와 함께 사라졌고, 그대로 재측정하러 갔으면 **전후 비교의 한쪽이 비어 있었을 겁니다.**

Resilience4j의 이벤트로 같은 카운터를 다시 만들었습니다. **이름은 자체 구현 때와 같게** 뒀습니다 —
바꾸면 지나간 기록의 숫자와 이어지지 않기 때문입니다.

> 회로 차단기 쪽은 채울 것이 없습니다. `resilience4j.circuitbreaker.not.permitted.calls`가
> 차단된 호출을 이미 세줍니다. **없는 것만 채웠습니다.**

**교체할 때 잃은 것은 기능이 아니라 관측이었습니다.** 테스트는 계약을 지켰다고 말해주지만
"무엇을 못 보게 됐는지"는 말해주지 않습니다.

### ② 5xx 한 건이 확인 루프의 줄 전체를 막는다

확인 루프가 잡던 예외는 **둘뿐**이었습니다 — "답이 없다"와 "우리 격벽이 막았다".
상대의 **5xx는 그 둘 중 어느 것도 아닙니다.** `isNoAnswer`가 일부러 false로 거르는 값이라
(그래야 재시도로 풀릴 건이 확인 루프에 쌓이지 않습니다) 예외가 그대로 루프 밖으로 나갑니다.

그러면 두 가지가 **같이** 나빠집니다.

| | 무슨 일이 생기나 |
|---|---|
| 그 tick | 뒤에 선 건들이 **통째로 건너뛰어진다** |
| 다음 tick | 실패한 건은 `nextInquiryAt`이 그대로다 → 조회는 그 시각 **오름차순**이므로 **다시 맨 앞에 선다** |

같은 실패가 반복되면 뒤의 것들은 **영영 조회되지 않습니다.** 상대 은행에 `errorRate` 스위치가
있으니 재측정에서 실제로 만들 수 있는 상황이고, 하필 **모르는 돈을 확인하려고 만든 루프가
한 건 때문에 아무것도 확인하지 못하는 루프**가 됩니다.

한 건씩 격리하고, 실패한 건은 **결론을 내지 않은 채 뒤로 밉니다.** 결론을 못 냈다고 해서
줄 맨 앞을 계속 차지할 이유는 없습니다. 건너뛴 건수는 따로 셉니다
(`remittance.external.credit.inquiry.error`) — **결론 카운터와 섞으면 안 되는 값**이라
따로 두었고, 이 값이 오르는데 결론이 안 늘면 루프가 헛돌고 있다는 뜻입니다.

### 확인

| 무엇 | 어떻게 | 결과 |
|---|---|---|
| 거절/통과 카운터 | 정원 1을 채운 채 한 건 더 호출 | 통과 1 · 거절 1, 호출 전에는 둘 다 **0으로 보임** |
| 한 건이 터져도 다음 건 | 앞 건 5xx, 뒤 건 ACCEPTED | 뒤 건이 조회되고 종결됨 |
| 줄 맨 앞을 비우나 | 5xx 뒤 `nextInquiryAt` | 뒤로 밀림, `inquiries` 1 증가 |
| **테스트 유효성** | 두 수정을 각각 **되돌려보고** | 카운터 테스트 red, 루프 테스트는 `HttpServerErrorException`으로 red |
| 회귀 | `./gradlew unitTest` · `:account-service:test` | 전부 green |

**아직 홈서버에서는 안 재봤습니다.** 이건 재측정을 하기 위한 준비이지 재측정이 아닙니다.

---

## 재측정했다 — 회로는 열리는데, **느린 상대에는 안 열린다** ★★★

**환경**: `home2`, 서버 안에서 부하(`CPUSET=0-9` / k6 `10-11`) · 커밋 `0bee664`
**시나리오**: `mixed-bank.js` — ① 30 TPS · 외부 20% · 상대 2초 지연 ② SMOKE · 외부 100% · 5xx 100%

### ① 느린 상대 — 교체 전과 같은 조건

| | 리스너 분리(자체 구현) | **Resilience4j** |
|---|---:|---:|
| 내부 종결 p99 | 2,587ms | **2,580ms** |
| 내부 성공률 | 1.00 | **1.00** |
| 접수 p99 | — | 84ms · 실패 0 · 미발사 0 |

**바뀐 게 없습니다. 그게 이 실행이 원하던 결과입니다** — 교체는 계약을 지켰고,
이제 "성능도 같다"고 말할 수 있습니다. (외부 성공률은 0.44인데 직전 기록의 0.00과
비교하면 안 됩니다. 그 값은 실행 시작 시점의 적체에 좌우되고, 이번엔 재기동 직후였습니다.)

### ② 격벽은 이 시나리오에서 **한 번도 거절하지 않았다**

```
통과 592   거절 0        ← 정원 2였을 때는 거절 1,662 > 통과 1,273
```

**0이 정보입니다.** 격벽 정원 8은 외부 리스너 스레드 6보다 크므로, 리스너가 상한인 한
격벽은 구조적으로 거절할 일이 없습니다. 지금 외부 동시성을 정하는 것은 **격벽이 아니라
리스너 스레드 수**입니다. 되살린 카운터가 없었으면 이 문장을 숫자로 못 했습니다 —
게이지(남은 자리)만 보면 "여유 있다"까지밖에 안 보입니다.

### ③ ★ 2초 느린 상대로는 회로가 **열리지 않는다**

```
successful 586   failed 0   not_permitted 0   state=CLOSED (내내)
평균 2.03초 · 최대 2.15초 — 전부 성공이다
```

**read-timeout이 3초라 2초 지연은 실패가 아닙니다.** 회로가 세는 것은 예외뿐이므로,
느리지만 답은 하는 상대는 영원히 회로를 열지 못합니다.

그런데 회로를 하려던 동기는 리스너 분리 때 이렇게 적혀 있습니다 —
*"상대가 감당 못 할 만큼 느리면 부르는 것을 멈추는 판단이 필요합니다."*
**그 동기는 아직 충족되지 않았습니다.** 지금 구현이 막는 것은 *느린* 상대가 아니라
*틀린* 상대입니다.

Resilience4j에는 이걸 위한 설정이 따로 있습니다(`slowCallDurationThreshold` ·
`slowCallRateThreshold`) — 켜지 않았습니다. 켜는 것이 자명하지도 않습니다:
**느리지만 성공하는 상대를 우리 판단으로 끊는 일**이고, 그러면 나가야 할 송금이 안 나갑니다.
**결정으로 남길 문제이지 빠뜨린 구현이 아닙니다.**

> **뒤에 다시 보니 진단이 틀렸습니다** — `DECISIONS.md` **D-003**에 정리했습니다.
> `read-timeout: 3s`가 이미 *"이만큼 느리면 포기한다"*를 정해둔 값이라, 2초는 우리가 정한 선
> **안**입니다. 진짜로 감당 못 하면 3초를 넘고 그건 타임아웃 → 실패 → **회로가 엽니다.**
> **동기는 이미 충족돼 있었고**, 기준이 `slowCallRateThreshold`가 아니라 타임아웃이었을 뿐입니다.

### ④ 연속 실패(5xx)에서는 설계대로 움직였다

| 무엇 | 값 |
|---|---|
| 실패 8건 → `OPEN` | 회로가 열렸다 |
| **차단 801건** | 부르지 않았다 — 왕복 비용도, 스레드 점유도 없다 |
| 미전송 319건 · 모르는 건 **1건** | 차단된 건은 `sent=false`다. **돈이 나갔을 가능성이 없다** |
| 상대 정상화 45초 뒤 | `CLOSED` — HALF_OPEN 한 건이 성공했다 |
| 그 뒤 40초 | 320건 **전부 `accepted`로 해소**, pending **0** |
| 송금 상태 | `COMPLETED` 외 **0건** (FAILED·CREDIT_UNKNOWN 없음) |
| 정식 대사 | 계좌 4,642개, **findings 0** |

**차단은 사고가 아니라 미룬 것**이라는 설계가 끝까지 성립했습니다.

또 이번에 고친 자리가 실제로 걸렸습니다 — `inquiry.error` **2건**.
5xx가 조회 경로에서 실제로 났고, **루프는 멈추지 않고 320건을 전부 해소했습니다.**

### 그래서 남은 것 두 가지

**첫째, 지표 하나가 거짓말을 합니다.** `remittance.external.credit.unknown`은
`pending_external_credits`의 **행 수**를 셉니다. 그런데 그 표에는 두 가지가 함께 삽니다.

```
sent=false  319건   안 보냈다 — 돈은 안 나갔다
sent=true     1건   보냈는데 모른다 — 이게 진짜 "모르는 돈"이다
              ↓
게이지는 320 이라고 답한다     ← 320배 부풀려 보고한다
```

**"섞으면 안 된다"**는 것이 `PendingExternalCredit`의 javadoc에 적혀 있는 문장인데,
정작 지표가 섞고 있었습니다. 회로 차단기 전에는 미전송이 드물어서 안 드러났고,
**차단이 미전송을 대량으로 만들면서 처음 보였습니다.** → 아래에서 갈랐습니다.

**둘째, 회로 지표는 첫 호출 전에는 아예 없습니다.** 회로를 은행별로 늦게 만들기 때문입니다.
대시보드·알람에서 **"없음"과 "닫힘"은 다른 뜻**이라 그대로 두면 조용한 빈 패널이 됩니다
(Phase 5에서 히스토그램 버킷으로 같은 일을 겪었습니다).

---

## 사고의 크기를 부풀려 보고하던 지표를 갈랐다 ★

**Phase 6.5** · 위 재측정에서 드러난 첫 번째 문제

한 표에 두 가지가 함께 사는데 게이지가 **행 수**를 셌습니다. 그래서 회로가 열릴수록
"고객 돈이 어디 있는지 모르는 건"이 폭증하는 것처럼 보였습니다 — **사실이 아닙니다.**

```
remittance.external.credit.unknown   보냈는데 모른다   돈이 나갔을 수 있다 → 사람이 본다
remittance.external.credit.unsent    보내지도 못했다   돈은 안 나갔다     → 상대가 살아나면 빠진다
```

**이름은 그대로 두고 뜻을 바로잡았습니다.** `unknown`이라는 이름이 처음부터 뜻하던 것이
`sent=true`였고, 미전송까지 세던 쪽이 틀린 것이었습니다. 지나간 기록의 `unknown = 0`은
그때 미전송도 0이었으므로 지금 정의로도 여전히 참입니다.

> **알람을 걸 자리가 여기서 갈립니다.** `unknown`이 0에서 뜨면 **사고**이고,
> `unsent`가 뜨는 것은 **보호 장치가 일하고 있다는 뜻**입니다. 하나로 두면
> 회로가 잘 동작할 때마다 사고 알람이 울립니다.

### 확인

| 무엇 | 어떻게 | 결과 |
|---|---|---|
| 갈라 세는가 | 회로를 연 채(5xx 100%) 지표와 DB 대조 | `unknown 1` · `unsent 188` = DB의 `sent` 분포와 **정확히 일치** |
| 고치기 전이었다면 | 같은 상태 | **189를 "모르는 돈"으로 보고**했을 것 |
| 회복 | 상대 정상화 75초 뒤 | 둘 다 **0**, 회로 `CLOSED`, pending 0, 대사 **findings 0** |
| 테스트 유효성 | 게이지를 `count()`로 되돌려봄 | 두 테스트 모두 red |
| 회귀 | `unitTest` · `:account-service:test` | green |

---

## 대사가 "우리도 상대도 모르는 돈"을 사람에게 넘긴다 ★★

**Phase 6.5 마지막 항목** — 로드맵의 *"정합성 대사에 새 항목"*

기계가 더 나아갈 수 없는 상태가 하나 있습니다. **보냈는데 결과를 모르는 건**입니다 —
재시도하면 이중 입금이고, 실패로 처리하면 돈이 사라집니다. 확인 루프는 계속 물어보지만
상대가 답을 못 주면 거기서 끝입니다. **그때부터는 사람이 상대 은행에 연락해야 합니다.**

### 이미 로그도 지표도 있는데 왜 또 보나

| 있던 것 | 무엇을 말해주나 | 무엇을 못 말해주나 |
|---|---|---|
| 확인 루프의 `ERROR` 로그 | 오래된 건이 있다 | 그 프로세스가 살아 있는 동안만 흐른다 |
| `credit.unknown` 게이지 | **지금 몇 건**인가 | **어느 건**인지 — 숫자 하나다 |
| **대사 결과** | 송금 ID·은행·금액·언제부터·조회 몇 회 | — |

연락을 하려면 송금 ID와 금액이 필요합니다. 회차별로 남는 대사 결과가 그걸 합니다.

```
UNKNOWN_EXTERNAL_CREDIT  fa787c75-…  KB에 100.00 KRW를 보냈는데
                                     2026-08-28T06:04:22Z부터 결과를 모른다 (조회 8회)
```

**조회 횟수를 함께 적습니다.** 여덟 번 물었는데도 아직 여기 있다는 것이
"기다리면 풀린다"가 아니라 **"사람이 나서야 한다"**의 근거입니다.

### 못 보낸 건은 여기 들어오지 않는다

`sent=true`만 봅니다. 회로·격벽에 막힌 건은 **돈이 나가지 않았으므로 사고가 아닙니다.**
넣었으면 회로가 잘 동작할 때마다 사람을 부르게 됩니다 — 바로 앞에서 게이지를 가른 것과 같은 이유입니다.

임계값은 **5분**입니다. 확인 루프의 백오프가 최대 1분까지 늘어나므로 그보다 넉넉히 잡아야
**아직 스스로 풀릴 건**까지 부르지 않습니다 (`account-service`의 `inquiry.stuck-after`와 같은 값이고,
그쪽은 로그로 이쪽은 대사 결과로 남깁니다).

### 같은 송금이 두 번 잡히는 것은 중복이 아니다

임계값이 다릅니다(2분 vs 5분). 먼저 `UNSETTLED_TRANSFER`로 **"흐름이 끊겼다"**가 잡히고,
계속 안 풀리면 `UNKNOWN_EXTERNAL_CREDIT`으로 **"그 이유가 남의 시스템에 있고 돈이 나갔을 수 있다"**가
더해집니다. 앞은 우리 안의 사실이고 뒤는 경계 밖의 사실이라, **대응하는 사람이 다릅니다.**

### Flyway의 대가가 처음 청구됐다

`type`이 MySQL `ENUM`이라 **자바 enum에 상수를 더하는 것만으로는 저장되지 않습니다.**
테스트가 `Data truncated` … 이 아니라 `UnexpectedRollbackException`으로 터졌는데,
insert 실패를 회차 자체가 삼키고 커밋에서 드러났기 때문입니다.

**D-001 ⑤에 "엔티티만 고치면 되던 게 두 곳이 됐습니다"라고 적어둔 그 대가**입니다.
예고해둔 비용이 실제로 청구된 첫 사례라 `V2__unknown_external_credit_finding.sql`로 남깁니다.

> 새 종류 하나 때문에 **기존 발견까지 함께 롤백**됩니다. 대사는 한 회차가 한 트랜잭션이라
> 한 줄이 안 들어가면 그 회차가 통째로 없던 일이 됩니다.

### 확인 — 홈서버에서 진짜 "모르는 돈"을 만들었다

상대를 **30초 지연**으로 두면 입금 POST도 조회 GET도 3초 read-timeout을 넘겨,
**스스로는 영영 못 푸는 건**이 만들어집니다.

| 시점 | 대사 결과 |
|---|---|
| 만든 지 132초 | `UNSETTLED_TRANSFER` 181건, **`UNKNOWN_EXTERNAL_CREDIT` 0건** — 아직 부르지 않는다 |
| 5분 경과 | `UNSETTLED_TRANSFER` 363건 + **`UNKNOWN_EXTERNAL_CREDIT` 1건** (조회 8회) |
| 상대 정상화 뒤 | pending 0 · unknown 0 · unsent 0 · **findings 0** |

마이그레이션도 데이터가 든 홈서버 DB에서 확인했습니다 —
`flyway_schema_history`에 V2가 `success=1`로 남고 컬럼에 새 값이 들어갔습니다.

**테스트 유효성**: 대사에서 이 검사를 잠시 빼자 같은 테스트가 red.
`./gradlew test` 전체 green (3분 8초).

---

## Phase 6.5 종결 — 정상 상대로 끝까지 확인했다 ✅

**환경**: `home2`, 30 TPS · 외부 비율 20% · **상대 정상**(스위치 전부 0)

여기까지의 확인은 전부 **상대를 일부러 아프게 해서** 한 것이었습니다.
마지막으로 **아무것도 망가뜨리지 않은 상태**에서 끝까지 돌렸습니다.

| | 값 |
|---|---:|
| 접수 실패율 · 미발사 | **0** · **0** |
| 내부 종결 p99 · 성공률 | **2,580ms** · **1.00** |
| 외부 종결 p99 · 성공률 | **2,979ms** · **1.00** |
| 시간초과 · FAILED | **0** · **0** |
| drain 뒤 outbox 적체 · pending | **0** · **0** (unknown 0 · unsent 0) |
| 송금 상태 | `COMPLETED` 외 **0건** (누적 536,811건) |
| 정식 대사 | 계좌 **4,845개**, **findings 0** |

**외부 송금이 3초 안에 끝납니다.** 상대가 정상이면 외부도 내부와 같은 수준이고,
그 말은 앞의 모든 나쁜 숫자가 **상대의 상태 때문이었지 우리 구조 때문이 아니었다**는 뜻입니다.

### Phase 6.5가 남긴 것

| 단계 | 무엇을 배웠나 |
|---|---|
| 상대 은행 (Kotlin) | 남의 시스템은 **답을 안 줄 수 있다.** 목으로는 그 상태가 안 만들어진다 |
| 모르는 상태 | 답이 없으면 **재시도가 아니라 조회**다. 재시도는 "안 갔다"를 전제로 하는데 그걸 모른다 |
| 격벽 → 리스너 분리 | **나눠 쓰지 않으면 뺏기지 않는다.** 내부 p99 58,790 → 2,580ms |
| 회로 차단기 | **틀린 상대는 끊는다.** 느린 상대는 안 끊는다 — 그건 타임아웃의 일이다 (D-003) |
| Resilience4j | 교체하며 **잃은 것은 기능이 아니라 관측**이었다 (거절 카운터) |
| 지표 분리 | "못 보냈다"를 "모른다"로 세면 **회로가 잘 돌 때마다 사고로 보인다** |
| 대사 항목 | 기계가 더 못 가는 건을 **사람에게 넘기는 것**까지가 시스템이다 |

---

## 세 Phase가 출시되지 않은 채 쌓였다 — 같은 실수를 두 번째로 한다 ★

**2026-08-28** · `release/phase-5-6-6.5`

```
main      2636dff  2026-08-22  Phase 2·3까지
develop   8d603d5              ← 102커밋 앞
태그      phase-1 / phase-2 / phase-3-complete 뿐
```

**Phase 5(측정)·6(고동시성)·6.5(상대 은행)가 전부 출시도 태그도 없습니다.** Phase 6은
2026-08-26에 끝났는데 그대로 넘어갔고, 6.5를 하는 동안 더 벌어졌습니다.

### 이건 이미 한 번 대가를 치른 실수다

아래 "브랜치 히스토리"에 그때 기록이 있습니다 — `main` 보호 규칙이 요구하는 `unit` 잡이
Phase 2·3 머지 지점보다 **뒤에** 생겨서, 그 지점에서 낸 릴리스 브랜치에는 그 잡이 없고
GitHub은 보고되지 않는 검사를 영원히 기다렸습니다. 두 릴리스를 하나로 합쳐야 했고,
그때 이렇게 적었습니다.

> **출시를 미루면 이 어긋남이 쌓입니다.** 다음부터는 Phase가 끝나면 미루지 않는 편이 낫습니다.

**그 문장을 쓰고 나서 세 Phase를 더 미뤘습니다.**

### 그리고 뒤 Phase가 이걸 필요로 한다

Phase 7~9의 롤백·카나리 실습은 `phase-N-complete` 태그를 **기준점으로 쓴다**고 로드맵에
적혀 있습니다. 태그가 없으면 그 실습을 할 수 없습니다 —
**컨테이너화로 먼저 갔으면 필요한 순간에 기준점이 없었을 겁니다.**

### 이번에는 왜 안 막히나

같은 함정을 먼저 확인했습니다. 릴리스 브랜치를 **`develop`에서** 냈고,
그 시점의 `.github/workflows/build.yml`에 보호 규칙이 요구하는 세 잡이 **전부 있습니다**
(`lint-workflows` · `unit` · `build`). 지난번은 릴리스 대상이 *그때*의 코드라 잡이 없었던 것이고,
이번은 릴리스 대상이 **지금**의 코드입니다.

### 태그는 어디에 다나

릴리스는 하나인데 Phase는 셋입니다. Phase 2·3 때와 같은 방법을 씁니다 —
**태그는 브랜치가 아니라 커밋을 가리키므로**, 한 번 머지한 뒤 각 Phase 끝점에 그대로 답니다.

| 태그 | 커밋 | 무엇의 끝인가 |
|---|---|---|
| `phase-5-complete` | `6c10aec` | 홈서버에서 baseline을 확정한 지점 (종결 19건/s) |
| `phase-6-complete` | `6b278c2` | Phase 6 머지 — 용량 40 → 100 TPS, 핫 계좌 25 → 70 TPS |
| `phase-6-5-complete` | `5c79d54` | Phase 6.5 머지 — 상대 은행 |

> `6c10aec` 뒤에 문서·CI 커밋 셋이 더 있지만 **앱 코드는 한 줄도 다르지 않습니다**(확인함).
> 그래서 "Phase 5의 코드 지점"으로 이 커밋이 맞습니다.

**세 태그가 서로 다른 코드 지점을 가리킵니다** — 롤백 실습이 필요로 하는 건 릴리스 횟수가
아니라 그것입니다.

### 결과 (2026-08-28)

PR **#2**가 CI 세 잡(`lint-workflows` 13s · `unit` 1m33s · `build` 5m30s)을 통과해 머지됐고,
`main`은 `0cbb74f`가 됐습니다. 태그 셋도 밀었고, 실제로 다른 코드 지점인지 확인했습니다.

| 태그 | 커밋 | 잔액 샤딩 | `external-bank-service` |
|---|---|---|---|
| `phase-5-complete` | `6c10aec` | 없음 | 없음 |
| `phase-6-complete` | `6b278c2` | **있음** | 없음 |
| `phase-6-5-complete` | `5c79d54` | 있음 | **파일 15개** |

> **PR 크기가 스스로를 증명했습니다.** CodeRabbit이
> *"Review skipped: 197 files exceed the limit of 100"*으로 리뷰를 건너뛰었습니다.
> 세 Phase를 묶은 대가이고, **Phase 4부터는 끝나는 즉시 냅니다.**

---

## 문서를 손으로 쓰지 않는다 — 그리고 내부 문을 공개 문서에서 걷어냈다 ★

**Phase 4 (1/6)** · `feature/phase-4-springdoc`

399줄짜리 `docs/openapi.yaml`을 지우고, 여섯 서비스가 각자 `/v3/api-docs`로 자기 계약을
내게 했습니다. **손으로 쓴 문서는 이미 코드와 어긋나 있었습니다** — 틀린 문서는 없느니만
못합니다. 읽는 사람이 그걸 믿고 호출하기 때문입니다.

### 이 작업의 진짜 내용은 "가르는 것"이었다

문서를 생성하는 것 자체는 의존성 한 줄입니다. **위험한 건 그다음입니다.**

```
/accounts                                    고객이 부른다
/internal/reconciliation/balances            대사가 부른다
/internal/accounts/{id}/balance (PATCH)      잔액을 고친다 ★
```

한 문서에 섞어두면 Gateway가 그대로 노출할 때 **남의 잔액을 고치는 문이 공개 API로** 나갑니다.
그래서 그룹을 둘로 나눴습니다.

| 경로 | 무엇 | 누가 보나 |
|---|---|---|
| `/v3/api-docs/public` | `/internal/*`을 뺀 전부 | **Gateway가 모아서 낸다** |
| `/v3/api-docs/internal` | `/internal/*`만 | 우리만 |
| `/v3/api-docs` | 전부 | 서비스에 직접 물었을 때 |

**거르는 규칙을 경로 규약에 걸었습니다.** 새 `/internal` 경로를 만들면 공개 그룹에서 빠지는 것이
자동입니다 — 사람이 매번 기억해야 하는 규칙은 언젠가 잊힙니다.

### 서비스마다 그룹을 나눈 건 아니다

`reconciliation-service`는 **일부러 안 나눴습니다.** `/reconciliations`는 경로에 `internal`이
없지만 전부 운영용이라 고객에게 열 것이 하나도 없습니다. **노출을 막는 것은 문서 그룹이 아니라
라우팅이 할 일**이고, 여기서 공개 그룹을 만들면 *"공개할 것이 있다"*는 잘못된 신호가 됩니다.

`external-bank-service`도 안 나눴습니다. 이건 **남의 시스템을 흉내 낸 것**이라 문서의 독자가
고객이 아니라 우리 자신입니다.

### 겪은 것

- **springdoc은 2.x와 3.x가 같은 날 함께 릴리스됩니다.** 2.x가 Boot 3용이고 Boot 4에는
  **3.x**를 써야 하는데, 최신 숫자만 보면 2.9.0을 고르게 됩니다.
  루트 `build.gradle`에 `springdocVersion = '3.1.0'`으로 고정하고 그 이유를 주석에 적었습니다.
  > Maven Central 검색 API는 **2.8.6까지만** 보여줬습니다. GitHub 릴리스를 봐야 3.x가 나옵니다.
- **Jackson이 둘 공존합니다.** 우리 앱은 Jackson 3(`tools.jackson`)인데 springdoc이 끌고 오는
  swagger-core는 Jackson 2(`com.fasterxml.jackson`)입니다. 패키지가 달라 충돌하지는 않지만,
  **의존성 트리에 Jackson 2가 다시 들어온다**는 건 알고 있어야 합니다.
- **Boot 4에서 `TestRestTemplate`이 옮겨갔습니다.** `spring-boot-test`에 없고
  `spring-boot-resttestclient`(패키지도 `org.springframework.boot.resttestclient`)에 있으며,
  `starter-test`가 끌어오지 않습니다. 의존성을 늘리지 않으려고 **MockMvc**로 갔습니다 —
  이 저장소의 다른 웹 테스트와도 같은 방식입니다.

### 확인

| 무엇 | 결과 |
|---|---|
| 뜬 서비스가 자기 계약을 말하는가 | `/v3/api-docs`에 `/accounts` · `/accounts/{accountId}` |
| **낡지 않았는가** | 오늘 추가한 `/internal/reconciliation/unknown-external-credits`가 들어 있다 |
| **공개 문서에 내부 문이 없는가** ★ | `/v3/api-docs/public`에 `/internal/`이 **하나도 없다** |
| 내부 문서는 내부만 | `/v3/api-docs/internal`에 `/accounts`가 없다 |
| 회귀 | `./gradlew test` 전체 green (2분 22초) |

두 번째 확인이 중요합니다. **"생성은 되는데 낡은 것"과 구분하려면** 최근 추가한 경로가
따라오는지를 봐야 합니다 — 그게 손으로 쓴 문서가 실패한 지점이기 때문입니다.

---

## Config Server를 만들어보고 접었다 — 옮길 설정이 없었다 ★

**2026-08-28** · `feature/phase-4-config-server` (PR #4, 머지하지 않고 닫음)

Git 백엔드로 실제로 뜨고 설정을 내는 것까지 만들었습니다. 테스트도 green이었습니다.
**그런데 여섯 서비스에 붙이기 직전에 멈췄습니다.**

### 무엇을 얻고 무엇을 치르나

옮길 수 있는 공유 설정이 이것뿐이었습니다.

```yaml
management:
  endpoints: { web: { exposure: { include: health,info,metrics,prometheus } } }
  endpoint: { health: { show-details: always, probes: { enabled: true } } }
```

**액추에이터 7줄.** 그걸 한 곳으로 모으자고 아래를 치러야 했습니다.

| 대가 | 왜 |
|---|---|
| 서비스 하나 띄우려면 8888이 먼저 필요 | fail-closed면 설정 없이는 안 뜬다 |
| 테스트 6곳에 `config.enabled: false` | 테스트가 외부 서버에 의존하면 "어제는 통과했는데"가 생긴다 |
| 홈서버 스크립트 재작성 + 기동 순서 | config-server를 먼저 띄우고 health를 기다려야 한다 |

**모든 서비스에 기동 의존성을 하나 늘리는 값이 7줄이었습니다.**

### 값이 나오는 자리는 넷인데, 하나만 켜져 있었다

| | 지금 |
|---|---|
| 같은 설정이 여러 곳에 복사됨 | ✅ 해당 — 그런데 7줄이다 |
| **환경마다 값이 다름** | ❌ 환경이 하나다(로컬 ≈ 홈서버) |
| 재배포 없이 값 바꾸기 | ❌ `@RefreshScope`가 없다. 격벽처럼 호출 중에 다시 만들면 위험한 것도 있다 |
| 설정 변경 이력 | ❌ 설정을 같은 저장소에 뒀으니 어차피 남는다 |

**두 번째 칸이 켜지는 순간이 Phase 8**(K8s에서 dev/prod가 갈릴 때)이라 그리로 옮겼습니다.

### 그래도 배운 것은 남는다

접기 전에 알아낸 것 셋은 Phase 8에서 그대로 쓰입니다.

- **`file:` URI는 커밋된 것이 아니라 작업 트리를 낸다.** 문서 주석에 반대로 써놨다가
  테스트에 깨졌습니다. label(브랜치)로 시점을 고르려면 **원격 주소**여야 합니다
- **Spring Cloud 2025.1.3은 Boot 4.0.8 기준**입니다(우리는 4.1.0). Framework 줄기가 같아
  붙지만 다음 Boot 업그레이드에서 여기가 먼저 깨질 자리입니다
- macOS 임시 디렉터리에서 **JGit이 심볼릭 링크를 거부**합니다(`/var` → `/private/var`).
  리눅스 CI에서는 안 나는 문제라 로컬에서만 red가 됩니다

브랜치는 지웠거나 되돌리지 않았습니다 — **다시 열면 그대로 있습니다.**

> **한도 서비스와 같은 판단입니다.** 나쁜 기술이라서가 아니라 **지금 이 시스템에 그 문제가
> 없어서** 미룹니다. 다만 이번엔 만들어본 뒤에 접었다는 게 다릅니다 —
> 덕분에 위 셋을 알고 갑니다.

---

## 게이트웨이가 섰다 — 그리고 내 테스트가 아무것도 검증하지 않고 있었다 ★★

**Phase 4 (2/5)** · `feature/phase-4-gateway`

여덟 번째 프로세스가 아니라 **문 하나**가 생겼습니다. 밖에서 오는 요청은 `:8080`만 거칩니다.

```
:8080 ─┬─ /accounts/*/transactions ──▶ ledger      (8083)   ← 더 구체적인 쪽이 먼저
       ├─ /transactions/**          ──▶ ledger
       ├─ /accounts/**              ──▶ account     (8081)
       ├─ /transfers/**             ──▶ transfer    (8082)
       ├─ /notifications/**         ──▶ notification(8085)
       └─ /internal/**              ──▶ ❌ 404
```

### 경로가 겹쳤다

`account`와 `ledger`가 둘 다 `/accounts`를 씁니다.

```
account : /accounts/{id}, /accounts/{id}/balance
ledger  : /accounts/{id}/transactions      ← 더 구체적
```

**순서가 곧 규칙입니다.** 원장 라우트를 뒤에 두면 거래내역 조회가 account로 가서 404가 됩니다.
뒤집어서 실제로 red가 되는 것을 확인했습니다.

### ★ 테스트가 아무것도 검증하지 않고 있었다

`/internal/**`을 막는 필터(`InternalPathGuard`)를 만들고, 통합 테스트로
*"내부 경로는 404다"*를 확인했습니다. **그리고 필터를 꺼봤더니 그대로 통과했습니다.**

```
가드 켬  → /internal/... 404
가드 끔  → /internal/... 404      ← 똑같다
```

당연했습니다. **`/internal/**`에 맞는 라우트가 애초에 없어서** 가드가 없어도 404입니다.
그 테스트가 검증한 것은 *"막았다"*가 아니라 **"아직 안 열었다"**였습니다.

**이게 가드가 필요한 이유와 정확히 같은 이야기입니다.** 지금 안전한 건 *없는 것*에 기대고
있어서이고, 누가 catch-all 라우트를 하나 추가하면 그 순간 **잔액을 고치는 문이 조용히 열립니다.**

그래서 필터를 직접 부르는 단위 테스트를 따로 만들었습니다 — **체인이 이어지는지**를 봅니다.
그게 "뒤로 흘려보낸다"이기 때문입니다. 이번엔 가드를 끄니 red가 됐습니다.

> **통과하는 테스트가 있다고 검증된 게 아닙니다.** 깨보기 전까지는
> *"무엇 때문에 통과하는지"*를 모릅니다. 오늘 이 저장소에서 두 번째로 확인한 셈입니다.

### 이게 진짜 경계는 아니다 — 정직하게

서비스 포트가 여전히 열려 있어서 게이트웨이를 건너뛰면 그만입니다. 홈서버에서 확인했습니다.

```
:8080/internal/reconciliation/balances  →  404   ← 막힌다
:8081/internal/reconciliation/balances  →  200   ← 열려 있다
```

**진짜 차단은 네트워크에서 해야 합니다** — Phase 8의 NetworkPolicy로 서비스 포트를 클러스터
안으로만 열면 그때 완성됩니다. 지금 만든 것은 *"게이트웨이는 이 문을 열어주지 않는다"*까지입니다.

### 확인 — 홈서버에서 게이트웨이만 거쳐 송금 한 건

| 무엇 | 결과 |
|---|---|
| `:8080/accounts/{id}` | 200, account의 응답 |
| `:8080/accounts/{id}/transactions` | 200, **ledger의 응답**(겹침이 풀렸다) |
| `:8080/transfers` 접수 → 조회 | `COMPLETED` |
| 원장 확인 | 받는 계좌에 1건 |
| `:8080/internal/...` | **404** |
| 테스트 유효성 | 라우트 순서를 뒤집으면 red, 가드를 끄면 단위 테스트가 red |

### 겪은 것

- **아티팩트 이름이 바뀌었습니다.** `spring-cloud-starter-gateway`는 4.3.5에서 멈췄고,
  5.x부터는 `spring-cloud-starter-gateway-server-webflux`입니다 — 서버 구현(WebFlux/WebMVC)을
  이름에 박습니다. 옛 이름으로 찾으면 **"Boot 4용이 없다"고 오해하게 됩니다**
- **홈서버 스크립트의 이름 가정이 깨졌습니다.** `{짧은이름}-service`로 컨테이너와 jar 경로를
  만들고 있었는데 `gateway`에는 `-service`가 없습니다. **모듈 이름을 그대로 적도록** 바꿨고,
  기존 여섯의 컨테이너 이름은 그대로입니다

---

## 이 시스템에 처음으로 "누가 보냈는지"가 생겼다 ★★

**Phase 4 (3/5)** · `feature/phase-4-auth`

**여기까지 인증이 아예 없었습니다.** 아무나 `POST /transfers`로 남의 계좌에서 돈을 뺄 수
있었습니다. 게이트웨이가 생겼으니 그 문에서 한 번만 확인합니다.

### 검증만 한다, 발급은 안 한다

토큰을 만들어주는 서비스는 안 만들었습니다. 이 Phase의 주제가 *"게이트웨이가 횡단 관심사를
한 곳에서 처리한다"*이지 *"인증 서비스를 만든다"*가 아니고, 발급을 빼도
**검증·전달·거절**이라는 핵심은 그대로 연습됩니다. **여덟 번째 서비스를 안 늘렸습니다.**

### 암호는 직접 만들지 않았다

이 저장소는 패턴을 손으로 만들어보고 표준으로 갈아탑니다(`DECISIONS.md`). **서명 검증은
그 규칙에서 뺐습니다** — 틀리면 그게 곧 보안 구멍이고, **테스트가 green이어도 뚫립니다.**

```
정책(어느 경로에 필요한가 · 헤더를 어떻게 넘기나)   →  직접 쓴다
서명·만료 검사                                  →  nimbus-jose-jwt에 맡긴다
```

Spring Security의 리소스 서버도 속으로 같은 라이브러리를 씁니다. 비대칭 키와 JWKS가
필요해지는 시점에 그쪽으로 갑니다.

### ★ 들어온 `X-User-Id`는 **덮어쓰지 않고 지운다**

뒤 서비스는 이 헤더를 믿을 수밖에 없습니다. 그러면 밖에서 헤더만 붙여 보내는 것으로
**남이 될 수 있습니다.**

```
Authorization: Bearer <진짜-주인의 토큰>
X-User-Id: 훔친-남의-아이디            ← 이게 살아남으면 끝이다
```

그래서 **공개 경로에서도** 지웁니다. 헬스체크로 들어와 뒤에서 쓰이면 그것도 위조입니다.
테스트에서 헤더가 **정확히 하나**이고 그 값이 토큰의 주인인지를 봅니다 —
"덮어썼다"고 생각했는데 값이 둘 남는 실수가 실제로 가능한 자리입니다.

### 순서가 뜻을 갖는다

```
InternalPathGuard  (HIGHEST_PRECEDENCE)      /internal/** → 404
JwtAuthFilter      (HIGHEST_PRECEDENCE + 1)  토큰 검사
라우팅
```

**내부 경로는 토큰이 유효해도 나가면 안 됩니다.** 인증을 먼저 세우면
*"토큰만 있으면 내부 경로도 되는"* 것처럼 읽힙니다. 홈서버에서 확인했습니다 —
유효한 토큰으로 `/internal/...`을 불러도 404입니다.

### 라우팅 테스트가 깨진 것이 증거였다

인증을 붙이자 기존 라우팅 테스트 둘이 **401로 깨졌습니다.** 고칠 것은 테스트였고,
**깨졌다는 사실 자체가 인증이 실제로 걸렸다는 증거**입니다. 토큰을 붙여 되살렸고,
*"토큰이 없으면 라우팅까지 가지도 않는다"*를 새로 추가했습니다.

### 확인 — 홈서버

| 무엇 | 결과 |
|---|---|
| 토큰 없이 `/accounts/{id}` | **401** |
| 유효한 토큰으로 | **200** |
| 서명이 틀린 토큰으로 | **401** |
| `/actuator/health` 토큰 없이 | **200** (아니면 K8s가 파드를 못 살린다) |
| **유효한 토큰으로 `/internal/...`** | **404** |

단위 테스트로는 만료·서명 불일치·헤더 위조·공개 경로를 각각 못 박았습니다.

### 아직 진짜 경계가 아닌 것은 그대로다

서비스 포트가 열려 있는 한 게이트웨이를 건너뛰고 `X-User-Id`를 위조하면 그만입니다.
`/internal` 문제와 **같은 뿌리이고 답도 같습니다** — Phase 8의 NetworkPolicy로
서비스가 게이트웨이 말고는 대답하지 않게 될 때 완성됩니다.
지금 만든 것은 **"게이트웨이를 통과한 요청의 신원은 진짜다"**까지입니다.

> 그리고 대칭키(HS256)라 **비밀을 아는 사람은 아무 토큰이나 만들 수 있습니다.**
> 발급자가 없어서 이렇게 갔고, 생기면 비대칭 + JWKS로 갑니다 — 그때 게이트웨이는
> **공개키만** 압니다.

---

## 게이트웨이가 지연에 얼마를 더하나 — 60 TPS에선 +24ms, 100 TPS에선 안 보인다 ★★

**Phase 4 (4/5)** · `feature/phase-4-gateway-measure` · 환경: 홈서버, 서버 안에서 부하

**순서를 바꿨습니다.** 원래 4/5가 Rate Limiting이었는데, **제한값을 정할 근거가 없었습니다.**
"사용자당 10 TPS"라고 적으면 그 10이 어디서 나왔냐에 답을 못 합니다.
그래서 재측정을 먼저 하고, 그 숫자에서 제한값이 나오게 합니다.

### 같은 부하, 다른 경로

`spread.js` 고정 60 TPS. **우리 쪽 구조만** 다릅니다 — 하나는 서비스를 직접, 하나는 게이트웨이 경유.

| | 서비스 직접 | **게이트웨이 경유** | 차이 |
|---|---:|---:|---:|
| **접수 p95** | 91.22ms | **107.38ms** | +16.2ms |
| **접수 p99** | 112.25ms | **136.52ms** | **+24.3ms** |
| 종결 p95 | 2,092.00ms | 2,096.05ms | +4.1ms |
| 종결 p99 | 2,103.00ms | **2,104.00ms** | **+1.0ms** |
| 접수 실패율 · 미발사 | 0 · 0 | 0 · 0 | — |
| 종결 성공률 | 1.00 | 1.00 | — |

### 이 표가 구조를 그대로 보여준다

**접수는 +24ms, 종결은 +1ms.** 우연이 아니라 게이트웨이가 어디 있는지가 그대로 나온 것입니다.

```
POST /transfers ──▶ [게이트웨이] ──▶ transfer ──▶ Outbox ──▶ Kafka ──▶ account ──▶ ledger
                     ↑ 여기만 는다        └────────── 여기부터는 게이트웨이가 없다 ──────────┘
```

**접수 지연에만 홉 하나가 더해지고, 돈이 실제로 움직이는 시간은 그대로입니다.**
Gateway가 죽어도 이미 접수된 송금이 끝까지 가는 것과 같은 이야기입니다 —
이 시스템에서 게이트웨이는 **문**이지 **길**이 아닙니다.

### ★ 그런데 100 TPS에서는 그 차이가 사라진다

용량(100 TPS)에서 같은 A/B를 한 번 더 했습니다.

| | 서비스 직접 | 게이트웨이 경유 | 차이 |
|---|---:|---:|---:|
| 접수 p95 | 124.07ms | 130.43ms | +6.4ms |
| **접수 p99** | **185.58ms** | **182.92ms** | **−2.7ms** |
| 종결 p99 | 5,044.40ms | 5,061.40ms | +17ms |
| 종결 성공률 | 1.00 | 1.00 | — |

**게이트웨이 쪽이 더 낮게 나왔습니다.** 게이트웨이가 요청을 빠르게 만들 리는 없으므로,
이건 **차이가 측정 노이즈에 묻혔다**는 뜻입니다. 부하가 높아지면 큐잉으로 생기는 흔들림이
24ms보다 커집니다.

**그래서 "게이트웨이는 접수 p99에 +24ms"는 60 TPS에서만 할 수 있는 말입니다.**
100 TPS에서는 **"있는지 없는지 구분되지 않는다"**가 정확한 서술입니다.
숫자 하나만 들고 왔으면 틀린 문장을 쓸 뻔했습니다.

### SLO는 어떤가 — 그리고 여기서 다른 게 걸렸다

접수 지연 목표가 **p99 < 500ms**입니다. 100 TPS·게이트웨이 경유에서 182.92ms이므로
**여유가 63%** 남습니다. 계층을 하나 얹어도 접수 목표는 위태롭지 않습니다.

**그런데 종결 지연이 걸립니다.**

```
종결 p99 목표          5,000ms
2026-08-26 (Phase 6)   4,673ms  ✅
2026-08-29 직접        5,044ms  ❌
2026-08-29 게이트웨이   5,061ms  ❌
```

**게이트웨이 탓이 아닙니다** — 직접 경로도 똑같이 넘었고, 둘의 차이는 17ms입니다.
사흘 전 같은 도착률에서 4,673ms였던 것이 지금 5,044ms입니다.

그 사이에 바뀐 것은 **데이터입니다.** 오늘 측정을 반복하면서 송금이 **536,811건**까지
쌓였습니다. 원장·계좌 테이블이 커지면 같은 부하도 느려집니다.

> **용량은 한 번 재고 끝나는 값이 아닙니다.** "100 TPS"는 그때의 데이터 크기에서 잰 값이고,
> 데이터가 늘면 같은 부하가 SLO를 넘습니다. 이건 게이트웨이와 무관한 별개의 발견이라
> 따로 확인해야 합니다 — 지금 단정할 수 있는 건 **"사흘 만에 문턱을 넘었다"**까지입니다.

### 부하 스크립트가 토큰을 만든다

게이트웨이를 통과하려면 토큰이 필요한데 **발급 서비스가 없으므로** k6가 직접 서명합니다
(HS256, `load-test/lib/auth.js`). 대칭키라 비밀을 알면 만들 수 있고, 여기서는 그 성질을 씁니다.

> ⚠️ **부하 생성기가 토큰을 서명할 수 있다는 것 자체가 운영에서는 사고입니다.**
> 측정용이라 이렇게 둡니다.

토큰은 **모듈이 한 번만 만듭니다.** 매 요청 서명하면 그게 부하 생성기의 일이 되어,
재려는 것(게이트웨이가 더하는 지연)에 우리 CPU 시간이 섞입니다.

**시드는 게이트웨이를 통과하지 않습니다.** 계좌 생성·충전은 `/internal/*`을 쓰는 운영 경로라
게이트웨이가 막고, 측정 대상도 아닙니다.

### 요약에 조건을 남긴다

`서비스 직접` / `**게이트웨이 경유**`가 요약 첫 줄에 찍힙니다.
**홉이 하나 다른 값을 나란히 두면 그냥 틀린 비교**라, 조건을 숫자 옆에 박아둡니다 —
`SHARDS`(핫 계좌 조각 수)를 적어두는 것과 같은 이유입니다.

---

## 발행이 끝난 Outbox 행을 아무도 안 지우고 있었다 ★★★

**2026-08-29** · `feature/phase-4-retention` · Phase 4를 잠시 세우고 함

앞 항목에서 *"용량이 사흘 만에 문턱을 넘었다"*를 별건으로 남겼습니다. 그걸 파보니
**설계 구멍**이었습니다.

### 무엇이 쌓여 있었나

```
account_db.outbox_events    2,406,230건   1,105 MB   ← 미발행 0건
transfer_db.outbox_events   1,200,546건     551 MB   ← 미발행 0건
account_db.processed_events 1,248,969건     155 MB
transfer_db.idempotency_keys  633,750건     136 MB

InnoDB 버퍼 풀  1,024 MB   vs   전체 데이터  2,405 MB
```

**전부 발행이 끝난 행입니다.** 릴레이는 미발행 건만 읽고 나머지는 `publishedAt`만 찍습니다.
지우는 주체가 **없습니다.** 버퍼 풀에 안 들어가는 데이터의 **69%가 이미 쓸모없는 행**이었습니다.

### 원인 확정 — 지우고 다시 쟀다

| | 종결 p99 | 비고 |
|---|---:|---|
| 2026-08-26 (데이터 적을 때) | 4,673ms | ✅ |
| 2026-08-29 (360만 건 쌓인 뒤) | 5,044ms | ❌ SLO 초과 |
| **지운 직후** | **11,768ms** | ❌❌ **더 나빠졌다** |
| 지우고 InnoDB가 정리된 뒤 | 4,699ms · **4,639ms** | ✅ 돌아왔다 |

**코드는 한 줄도 안 바뀌었습니다.** 쌓인 것 말고 달라진 게 없습니다.

### ★ 지운 직후가 더 나빴던 것 — 내가 규칙을 어겼다

정리하자마자 재고 *"정리가 나쁜가?"* 할 뻔했습니다. 원인은 **InnoDB가 뒤에서 360만 건의
삭제 흔적을 치우는 중**이었기 때문입니다(`History list length`). 다 치워진 뒤(0) 다시 재니
4,639ms였습니다.

이 저장소의 측정 규칙 첫 줄이 **"재기동 직후 첫 실행은 버린다"**입니다.
**대량 삭제 직후도 같은 상태인데 그걸 안 지켰습니다.** 규칙을 적어두고도 새 상황에서
못 알아본 셈이라, 규칙에 한 줄 더합니다 — **"대량 DML 직후도 버린다."**

### 그래서 만든 것

```
outbox.retention:
  enabled: true          측정 중에는 끌 수 있어야 한다
  keep-for: 3d           ← 근거는 아래
  chunk-size: 1000       한 번에 다 지우면 위에서 겪은 일이 난다
  max-chunks-per-tick: 5 스케줄러 스레드를 무한히 붙들지 않는다
  interval-ms: 60000
```

**보관 기간 3일의 근거**는 *"사고가 가장 늦게 발견되는 시점"*입니다.
발행된 행이 쓸모 있는 경우는 하나뿐입니다 — 조사할 때 *"그 이벤트가 실제로 발행됐나"*.

| 무엇이 사고를 발견하나 | 언제 |
|---|---|
| DLT 적재 | 즉시 |
| 미종결 송금 | 2분 (대사) |
| 모르는 돈 | 5분 (대사) |
| **전체 잔액 대사** | EOD 배치 예정 — **하루** |

가장 늦은 것이 하루이므로 조사 시간을 더해 3일입니다. **늘리려면 그 근거가 이 표에 있어야 합니다.**

### 이 지표가 없어서 못 봤다

`remittance.outbox.backlog`(**미발행** 적체)는 Phase 5부터 보고 있었습니다.
그런데 **발행이 끝난 뒤 쌓이는 것은 아무도 세지 않았습니다.**

```
remittance.outbox.retained          ← 새로 만든 것. 발행됐지만 아직 남아 있는 행
remittance.outbox.retention.deleted ← 지운 건수
```

**"보고 있던 것"과 "봤어야 하는 것"이 달랐습니다.** 240만 건이 될 때까지 몰랐던 이유가 그겁니다.

### CDC로 가면 이 문제가 구조적으로 사라진다

로드맵에 **"폴링 Outbox 릴레이 → Debezium(CDC)"**가 이미 있는데, 조건이 *"폴링이 병목일 때"*라
이 문제로는 안 걸립니다. 그런데 CDC로 가면:

| | 폴링 (지금) | CDC |
|---|---|---|
| 이벤트를 어떻게 읽나 | 릴레이가 **테이블을 조회** | **binlog**를 읽는다 |
| 행이 언제까지 필요한가 | 릴레이가 읽을 때까지 | **INSERT가 binlog에 남는 순간** 끝 |
| 정리 | 별도 배치 (이번에 만든 것) | **INSERT 직후 바로 지워도 된다** |

**"테이블이 무한히 자란다"는 폴링 방식의 대가**입니다. CDC의 장점으로 보통 지연과 폴링 제거만
이야기하는데 **"쓰레기가 안 쌓인다"가 하나 더** 있습니다. 로드맵에 이 근거를 추가했습니다.

### 남은 것 — 같은 구멍이 셋 더 있다

`processed_events`(155MB) · `idempotency_keys`(136MB) · `notifications`(262MB).
`idempotency_keys`는 **`expiresAt`이 이미 있는데 아무도 안 쓴다**고 `DECISIONS.md`에
적혀 있었습니다. 이번엔 **가장 큰 둘(1.6GB)만** 고치고 나머지는 로드맵에 남깁니다.

> **디스크는 아직 안 돌려받았습니다.** `DELETE`는 페이지를 재사용 가능하게 할 뿐 파일을
> 줄이지 않습니다 — `information_schema`는 0MB라고 하는데 `.ibd` 파일은 1.2GB 그대로입니다.
> 되찾으려면 `OPTIMIZE TABLE`로 테이블을 다시 만들어야 하고, 그건 긴 잠금이라 따로 다룰 일입니다.
> **버퍼 풀 입장에서는 이미 비었으므로 성능은 돌아왔습니다.**

---

## 정리 배치가 배포하자마자 죽고 있었다 — 테스트가 감싸주고 있었기 때문이다 ★★★

**2026-08-29** · 바로 앞 항목(보관 기간)의 **후속**

정리 배치를 만들고 테스트도 green이었는데, 홈서버에 올려 로그를 보니 **매 주기 예외로
죽고 있었습니다.**

```
jakarta.persistence.TransactionRequiredException: No active transaction for update or delete query
    at com.remittance.account.outbox.OutboxRetention.sweep(OutboxRetention.java:83)
```

`@Modifying` 삭제 쿼리는 트랜잭션이 있어야 하는데 `sweep()`에는 없었습니다.

### ★ 테스트는 왜 통과했나 — 테스트가 트랜잭션을 대신 만들어줬다

처음 테스트가 이랬습니다.

```java
@Test
@Transactional          // ← 이것 때문에 green이었다
void 오래된_발행_행만_지운다() {
    repository.deletePublishedBefore(...);   // 리포지토리를 직접 호출
}
```

**운영 코드에 없는 트랜잭션을 테스트가 만들어주고 있었습니다.** SQL이 맞는지는 확인했지만
**그 SQL이 운영에서 실행될 수 있는지는 확인하지 않았습니다.**

> 이번 세션에서 세 번째입니다 — 게이트웨이 가드도 "라우트가 없어서" 통과했고,
> 여기서는 "테스트가 감싸줘서" 통과했습니다. **통과하는 테스트가 있다고 검증된 게 아니고,
> 무엇 때문에 통과하는지를 물어야 합니다.**

### 고친 것

`OutboxChunkDeleter`를 따로 뒀습니다 — **이 저장소가 이미 같은 함정을 문서로 남겨둔 자리**입니다.

> `OutboxRelay`의 주석: *"실제 발행은 `OutboxBatchPublisher`가 한다 — 배치 하나가 트랜잭션
> 하나여야 하는데, 같은 빈 안에서 자기 메서드를 부르면 `@Transactional` 프록시를 타지 않기
> 때문이다."*

**릴레이에는 적어뒀는데 정리 배치에서 똑같이 걸렸습니다.** `sweep()`에 트랜잭션을 걸면
안 되는 이유도 같습니다 — 한 주기 전체가 한 트랜잭션이 되어 **끊어 지우는 의미가 사라집니다.**

### 테스트도 고쳤다 — 이제 이 버그를 잡는다

- 테스트 메서드의 `@Transactional`을 **뺐습니다.** 운영에서 스케줄러가 부르는 것과 같이
  `OutboxChunkDeleter`를 통해 부릅니다 — **그 빈이 자기 트랜잭션을 여는지가 여기서 갈립니다**
- 준비(과거 시각으로 되돌리기)는 `JdbcTemplate`으로 합니다. 테스트 클래스 메서드에
  `@Transactional`을 붙여도 **자기 클래스 내부 호출이라 프록시를 안 탑니다** —
  방금 운영 코드에서 겪은 것과 **똑같은 함정**이라 두 번 걸렸습니다
- `@Transactional`을 빼고 돌려보니 **red**가 됐습니다. 이제 잡습니다

### 확인 — 홈서버에서 실제로 지운다

```
500건을 5일 전 발행으로 되돌림
  → INFO  보관 기간이 지난 Outbox 행을 지웠다 (500건, 보관 PT72H)
  → remittance_outbox_retention_deleted_total  500
  → 3일 지난 행                                0
```

로그·카운터·DB가 전부 일치합니다. **로그를 안 봤으면 "만들었다"고 적고 넘어갔을 일**입니다.

---

## 한 사람이 전체 용량을 다 먹지 못하게 한다 ★★

**Phase 4 (5/5)** · `feature/phase-4-rate-limit`

Phase 4의 마지막 항목입니다. 그리고 **미뤄뒀던 fail-open/closed 질문의 답**이 여기서 나옵니다.

### 제한값의 근거

```
용량            100 TPS   (2026-08-29 실측, 종결 p99 4,639ms)
사용자당 제한    10 TPS    ← 전체의 10%
burst           20건      ← 몰아치는 것을 봐준다
```

용량을 넘겨 받으면 대기행렬이라 지연이 자릿수로 늘고 **모든 사용자가 같이 느려집니다**
(120 TPS에서 종결 p99가 2.7초 → 18.9초였습니다). 그래서 **한 사람이 전체의 10분의 1까지**로
막습니다. 비율을 바꾸려면 그 근거가 있어야 합니다.

**burst가 없으면 정상 사용자도 튕깁니다.** 사람이 쓰는 앱은 조용하다가 갑자기 몇 건씩 옵니다.

### 무엇을 기준으로 세나 — 로드맵의 근거를 고쳤다

로드맵에는 이 항목의 근거가 *"핫 계좌 보호"*로 적혀 있었습니다. **그건 지금 틀렸습니다.**

- 받는 계좌로 제한하려면 게이트웨이가 **요청 본문을 열어야** 합니다 → 도메인을 알게 되고
  본문 버퍼링으로 논블로킹 이점도 깎입니다
- 무엇보다 **핫 계좌는 Phase 6에서 잔액 샤딩으로 이미 풀었습니다**(25 → 70 TPS)

그래서 **게이트웨이가 이미 아는 것**으로만 판단합니다 — 토큰에서 꺼내 넣은 `X-User-Id`.
본문을 안 열고, **3/5에서 만든 인증이 여기서 값을 합니다.**

### ★ Redis가 죽으면 통과시킨다 (fail-open) — 그리고 그게 맞다

Spring Cloud Gateway의 기본 동작이고, **그대로 두기로 했습니다.**
이 시스템의 다른 두 결정과 나란히 놓으면 기준이 보입니다.

| 없으면 무슨 일이 나나 | 판단 |
|---|---|
| **인증** — 누군지 모르는 채 통과 | 틀린 동작. **fail-closed** (401) |
| **설정** — 다른 값으로 뜬 다른 프로세스 | 틀린 동작. **fail-closed** (Phase 8) |
| **제한** — 제한 없이 통과 | **보호가 약해질 뿐** 동작은 맞다. **fail-open** |

기준은 **"그것이 없으면 틀린 동작이 되는가, 보호가 약해지는가"**입니다.
인증 없이 통과시키면 남의 돈이 움직이고, 설정 없이 뜨면 다른 프로세스입니다.
그런데 제한이 없어도 **용량 안에서는 정상으로 돕니다** — 보호 장치 때문에 서비스가 멈추면
본말전도입니다.

> **대가는 조용하다는 것입니다.** 지금은 `RedisRateLimiter`가 ERROR 로그만 남깁니다.
> 로그는 사람이 찾아봐야 보이므로 Redis 상태는 액추에이터 health로 보고,
> Phase 10의 알림이 그걸 봐야 합니다. **"보호가 꺼진 것"도 사고입니다.**

그 동작을 테스트로 못 박아뒀습니다 — 나중에 라이브러리가 fail-closed로 바뀌면 여기서 먼저
드러납니다. 모르고 넘어가면 **Redis 장애가 곧 전면 중단**이 됩니다.

### 부하 스크립트가 자기 제한에 걸렸다

`load-test`라는 사용자 하나로 전부 보내고 있었습니다. 그러면 **100 TPS를 걸어도 한 사람 몫인
10 TPS만 들어갑니다.** 계좌마다 토큰을 내도록 고쳤고 — 계좌 주인이 각자 자기 토큰으로 보내는
것이 실제 모습이기도 합니다.

**보호 장치를 넣으면 그걸 재는 도구도 같이 바뀌어야 합니다.** 안 고쳤으면 다음 측정에서
"용량이 10분의 1로 줄었다"는 숫자를 보고 한참 헤맸을 겁니다.

### 확인 — 홈서버

```
heavy-user 30번 연속:  200 ×20  →  429 ×10        ← burst 20까지 통과, 그 뒤 차단
같은 순간 quiet-user:  200                        ← 남의 몫을 갉아먹지 않는다
```

100 TPS 부하도 다시 걸었습니다 — **접수 실패율 0.00, 미발사 0.** 계좌별 토큰이 실제로
제한을 피해 갑니다(사용자당 1.7 TPS 남짓).

| | 제한 넣기 전 | 제한 넣은 뒤 |
|---|---:|---:|
| 접수 p99 | 182.92ms | 220.42ms |
| 종결 p99 | 4,639ms | 5,141ms |

> ⚠️ **이 차이를 "Rate Limiting의 비용"이라고 단정하지 않습니다.** 이 시스템의 100 TPS
> 종결 p99는 오늘 하루에만 4,639 · 4,699 · 5,044ms로 흔들렸습니다. +500ms는 **그 변동 범위
> 안**이라 한 쌍으로는 못 가릅니다.
>
> 다만 **의심할 자리는 있습니다** — 게이트웨이의 제한 카운터와 `account-service`의 분산 락이
> **같은 Redis**를 씁니다. 100 TPS면 초당 100번의 Lua 호출이 락과 자리를 다툽니다.
> 확인하려면 제한을 끈 채/켠 채 같은 조건으로 여러 번 재야 하고, 그건 별도 작업입니다.

> **1차 실행은 버렸습니다** — 재기동 직후라 종결 p99가 22,631ms였습니다.
> 오늘만 세 번째로 같은 규칙에 걸렸습니다(`SLO.md`의 측정 규칙 첫 두 줄).

테스트로도 못 박았습니다 — 429가 실제로 뜨는지, 사용자별로 따로 세는지,
Redis가 죽으면 통과하는지, 그때도 **인증은 그대로 막는지**.
`default-filters`를 빼보니 red가 됐습니다 — **설정 한 줄이 틀려도 티가 안 나는 자리**라
반드시 눌러봐야 압니다.

---

## 로드맵의 미체크가 뜻하는 게 네 가지였다 ★

**2026-08-29**

Phase 6이 ✅ 완료인데 **미체크가 17개** 남아 있었습니다. 그 안을 열어보니 뜻이 제각각이었습니다.

| 실제로는 | 몇 개 | 예 |
|---|---|---|
| **이미 했는데 체크만 안 됨** | 7 | Kafka 파티션 3 → 6(했다) · baseline 재측정 비교(`SLO.md`에 표가 있다) |
| **조건이 안 돼서 안 한 것** | 6 | Redisson(락이 병목이어야) · Redis 캐싱(읽기가 아파야) |
| **할 일이 아니라 기록** | 3 | 취소선이 그어져 있거나 *"지금은 급하지 않다"*로 끝나는 것 |
| **진짜 남은 일** | 4 | EOD 대사 전환 · `read-heavy` 재조정 등 |

**그래서 로드맵을 볼 때마다 "이건 왜 안 했지"를 매번 다시 따져야 했습니다.**

### 표기를 갈랐다

```
- [x]     했다 — 근거(커밋·숫자)가 같이 있다
- [ ]     진짜 남은 일 — 지금 손댈 수 있는 것
- ⏭️      조건이 안 됐다 — **조건이 반드시 함께 적힌다**
> 인용문   기록 — 할 일이 아니다. 체크박스를 쓰지 않는다
```

`⏭️`가 핵심입니다. 이 저장소는 **"재고 나서 고친다"**가 원칙이라, 아직 아프지 않은 것을
안 고치는 것이 **빠뜨린 게 아니라 지킨 것**입니다. 그런데 `[ ]`로 두면 그 구분이 사라집니다.
**조건 없이 `⏭️`를 쓰는 것은 금지**로 문서 맨 앞에 적었습니다.

### 완료된 Phase 안에는 미체크를 두지 않는다

정리하고 나니 Phase 6에 진짜 남은 것 넷이 여전히 `[ ]`였습니다. **완료된 Phase 안에 미체크가
있으면 "안 끝난 Phase"로 읽힙니다.** 그렇다고 지우면 잊힙니다.

그래서 **`## 상시 — Phase 6에서 넘어온 숙제`** 절로 옮겼습니다. 넷 다 *"재는 일"*이라는
공통점이 있어서 — 코드를 고치는 게 아니라 **무엇이 아픈지 다시 보는 일**이라 특정 Phase에
묶이지 않습니다.

### 결과

```
[x] 완료      104
[ ] 남은 일    43   ← 대부분 Phase 7~13 (착수 전이라 [ ]가 맞다)
⏭️  조건 대기   15
완료된 Phase 안의 미체크    0
```

---

## 브랜치 히스토리

```
main ──●───────────────────────────────────────────────●  2636dff (PR #1 머지)
        \                                             ↗ \
develop ─●──●────────●──────────●────●────●──────────●─┘   ●  ← 지금 여기 (역머지)
             \      ↗ \         ↑     ↑    ↑
   feature/phase-2-data-consistency  CI위생  Phase5 Step1
                      feature/phase-3-event-driven
             phase-2-complete ┘      └ phase-3-complete   ← 태그는 각 Phase 끝점에
```

각 Phase는 `feature/*` → `develop` → `release/phase-N` → `main` + 태그 순으로 나갑니다
(규칙은 `CONTRIBUTING.md`).

### 두 번의 릴리스를 한 번으로 합쳤다 — 브랜치 보호가 그렇게 만들었다

원래 계획은 `release/phase-2` → `main`, 이어서 `release/phase-3` → `main`이었습니다.
**그런데 그렇게 낼 수가 없습니다.**

`main` 보호 규칙이 요구하는 상태 검사는 `unit` · `build` · `lint-workflows` 세 개인데,
**`unit` 잡은 CI 위생 커밋(`812a06f`)에서 생겼습니다.** 그 커밋은 Phase 2·3 머지 지점보다
**뒤에** 있습니다. 그래서 `edb9673`(Phase 2 머지)에서 낸 릴리스 브랜치에는 `unit` 잡이 없고,
GitHub은 보고되지 않는 검사를 영원히 기다립니다 — PR이 열린 채 머지되지 않습니다.

| 방법 | 대가 |
|---|---|
| 릴리스 브랜치에 CI 잡을 손으로 얹는다 | `develop`에 없는 커밋을 릴리스 브랜치에서 만들어야 한다 |
| 보호 규칙을 잠시 푼다 | 보호를 켠 이유를 스스로 무너뜨린다 |
| **한 번의 릴리스로 내고 태그는 둘로 나눈다** | 릴리스 하나에 Phase 둘이 들어간다 |

**세 번째를 택했습니다.** 태그는 브랜치가 아니라 **커밋**을 가리키고, `edb9673`과 `e924c9b`는
둘 다 `develop`의 조상입니다. `develop`을 `main`에 머지하면 그 두 커밋이 `main`에서
도달 가능해지므로, **각 Phase 끝점에 태그를 그대로 달 수 있습니다.**

머지한 뒤 실제로 확인했습니다 — `phase-2-complete`에는 `notification-service` 디렉터리가
없고 `phase-3-complete`에는 있습니다. **두 태그가 서로 다른 코드 지점을 가리킵니다.**

```
release/phase-2-3 → main
  phase-2-complete → edb9673 (Phase 2 머지 지점)
  phase-3-complete → e924c9b (Phase 3 머지 지점)
```

롤백 실습(Phase 7~8)이 필요로 하는 건 **서로 다른 두 코드 지점**이지 릴리스 횟수가 아닙니다.
그건 이 방법으로도 그대로 남습니다.

> **왜 이런 일이 생겼나** — 보호 규칙은 *지금*의 CI를 요구하는데 릴리스 대상은 *그때*의 코드입니다.
> 출시를 미루면 이 어긋남이 쌓입니다. **e2e를 미룬 대가가 태그 계획으로 돌아온 셈**이라,
> 다음부터는 Phase가 끝나면 미루지 않는 편이 낫습니다.

> Phase 3 작업을 처음에 `feature/phase-2-data-consistency` 위에 그대로 얹었습니다.
> 규칙대로면 `develop`에서 새 `feature/*`를 냈어야 합니다. Phase 2 끝점에서 브랜치를 갈라
> 되돌렸지만, **Phase가 넘어갈 때 브랜치도 함께 넘겨야 한다**는 걸 기록해둡니다 —
> 한 브랜치에 두 Phase가 섞이면 Phase별 태그 기준점을 만들 수 없습니다
> (Phase 7~8의 롤백 실습이 그 기준점을 씁니다).
