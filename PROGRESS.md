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
Phase 2  🔄  분산 환경 데이터 정합성   ← 지금 여기 (Step 2/5 완료)
Phase 3~11   미착수
```

**작업 브랜치**: `feature/phase-2-data-consistency`
**빌드 상태**: 🔴 의도적 red — Step 0의 재현 테스트 4개 중 **2개 해결(멱등성·동시성), 2개 남음**
(남은 2개는 Step 3~4에서 green이 됩니다. 이 red 상태는 feature 브랜치 안에서만 존재하며 `main`으로 가지 않습니다)

### 시스템 구성

| 서비스 | 포트 | 스택 | 저장소 |
|---|---|---|---|
| account-service | 8081 | Spring MVC + JPA | MySQL `account_db` |
| transfer-service | 8082 | Spring MVC + JPA | MySQL `transfer_db` |
| ledger-service | 8083 | Spring WebFlux | MongoDB `ledger_db` |
| gateway | 8080 | (Phase 4에서 구현) | — |
| config-server | 8888 | (Phase 4에서 구현) | — |

로컬 인프라(MySQL·MongoDB·Redis)는 Docker로 띄웁니다: `docker compose -f docker-compose.dev.yml up -d`
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
| 3 | Outbox 패턴 + Kafka 인프라 | ⬜ 다음 |
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
`AGENTS.md`에도 재발 방지용으로 적어두었습니다.

#### 검증

- account-service 테스트 20건 전부 통과 — 재현 테스트 #1(동시 출금)이 **green**으로 전환
- `DistributedLockTest` 5건으로 락 자체를 검증: 임계구역 상호배제, 정상/예외 종료 시 해제,
  대기 타임아웃, **남의 락은 지우지 않음**
- 실제 HTTP e2e: 잔액 10,000에 동시 출금 20건 × 100원 → **20건 전부 200**, 최종 잔액 정확히 8,000
  (Step 0 재현 시엔 `ConcurrentUpdateException` 다발이었음)
- 실패 경로(409) 포함해 Redis에 잔여 락 키 0건 확인

**커밋**: `39d12f0`

---

## 브랜치 히스토리

```
main ──●───────────────────────────────────────────  phase-1-complete 태그
        \
develop ─●─────────────────────────────────────────  e97ef20 브랜치 전략 문서화
          \
feature/phase-2-data-consistency ─●──●──●──●────  Step 0 → 문서 → Step 1
```

Phase 2 완료 시: `feature/phase-2-*` → `develop` → `release/phase-2` → `main` + `phase-2-complete` 태그
