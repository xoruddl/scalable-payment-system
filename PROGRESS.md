# 진행 기록

문서마다 역할이 다릅니다 (전체 지도는 `AGENTS.md` 참고).

| 문서 | 역할 |
|---|---|
| `ROADMAP.md` | **앞으로 할 일** — Phase 0~11 계획 |
| `PROGRESS.md` (이 문서) | **실제로 한 일과 그 이유** — 시간순 기록 |
| `CONTRIBUTING.md` | **규칙** — 커밋 컨벤션, 브랜치 전략 |
| `AGENTS.md` | **저장소 진입점** — 프로젝트 목적, 작업 규칙, 실행 방법 |

> 각 Step을 완료할 때마다 이 문서를 갱신합니다.

---

## 현재 위치

```
Phase 0  ✅  프로젝트 기반 설정
Phase 1  ✅  핵심 도메인 서비스 (Account / Transfer / Ledger)
Phase 2  🔄  분산 환경 데이터 정합성   ← 지금 여기 (Step 0/5 완료)
Phase 3~11   미착수
```

**작업 브랜치**: `feature/phase-2-data-consistency`
**빌드 상태**: 🔴 의도적 red — Step 0에서 추가한 재현 테스트 4개가 실패 중
(이 red 상태는 feature 브랜치 안에서만 존재하며 `main`으로 가지 않습니다)

### 시스템 구성

| 서비스 | 포트 | 스택 | 저장소 |
|---|---|---|---|
| account-service | 8081 | Spring MVC + JPA | MySQL `account_db` |
| transfer-service | 8082 | Spring MVC + JPA | MySQL `transfer_db` |
| ledger-service | 8083 | Spring WebFlux | MongoDB `ledger_db` |
| gateway | 8080 | (Phase 4에서 구현) | — |
| config-server | 8888 | (Phase 4에서 구현) | — |

로컬 DB는 Docker로 띄웁니다: `docker compose -f docker-compose.dev-db.yml up -d`
서비스 자체의 컨테이너화는 Phase 6 예정 — 지금은 `./gradlew :{서비스}:bootRun`으로 직접 실행합니다.

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
- 로컬 개발용 `docker-compose.dev-db.yml` (MySQL + MongoDB만)

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
| 1 | 멱등성 처리 | ⬜ 다음 |
| 2 | Redis 분산 락 | ⬜ |
| 3 | Outbox 패턴 + Kafka 인프라 | ⬜ |
| 4 | Choreography Saga 전환 | ⬜ |
| 5 | 정합성 대사 배치 | ⬜ |

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

## 브랜치 히스토리

```
main ──●───────────────────────────────────────────  phase-1-complete 태그
        \
develop ─●─────────────────────────────────────────  e97ef20 브랜치 전략 문서화
          \
feature/phase-2-data-consistency ─●──────────────  ac3b4ac Step 0 (재현, red)
```

Phase 2 완료 시: `feature/phase-2-*` → `develop` → `release/phase-2` → `main` + `phase-2-complete` 태그
