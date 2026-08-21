# AGENTS.md

이 저장소에서 작업하는 AI 에이전트를 위한 안내 문서입니다. **작업 시작 전에 이 문서부터 읽으세요.**

## 이 프로젝트가 무엇인가

송금/결제 시스템을 MSA로 구현하는 **학습용 프로젝트**입니다.

목표는 완성도 높은 결제 제품을 만드는 게 아니라, **대규모 분산 시스템에서 실제로 터지는 문제들
(데이터 정합성 깨짐, 과부하, 장애 전파, 확장)을 재현하고 해결하는 것**입니다.
송금 도메인을 고른 이유도 "부분 실패가 곧 사고로 이어지는" 특성 때문에
멱등성·분산락·Saga·Outbox 같은 패턴을 안 쓸 수 없기 때문입니다.

따라서 작업 제안 시 **도메인 기능을 풍부하게 만드는 것보다, 현재 Phase가 목표로 하는
분산 시스템 패턴을 제대로 연습하는 쪽을 우선**하세요.

## 문서 지도

| 문서 | 역할 | 언제 읽나 |
|---|---|---|
| `AGENTS.md` (이 문서) | 저장소 진입점, 작업 규칙 | 작업 시작 전 항상 |
| `docs/ROADMAP.md` | **앞으로 할 일** — Phase 0~11 계획과 체크박스 | 다음에 뭘 할지 정할 때 |
| `docs/PROGRESS.md` | **실제로 한 일과 그 이유** — 시간순 기록 | 현재 상태를 파악할 때 (문서 상단에 현재 위치·브랜치·빌드 상태) |
| `docs/CONTRIBUTING.md` | **규칙** — 커밋 컨벤션, git flow 브랜치 전략 | 커밋/브랜치를 만들기 전 |
| `docs/openapi.yaml` | API 계약 (Gateway가 외부에 노출하는 스펙) | 엔드포인트를 추가·변경할 때 |
| `docs/HELP.md` | Spring Initializr 자동 생성물 (`.gitignore` 대상) | 볼 일 없음 |

문서는 루트의 이 파일 하나만 남기고 나머지는 `docs/`에 모아두었습니다.
(`AGENTS.md`는 에이전트 도구가 저장소 루트에서 찾는 규약이라 옮기지 않습니다.)

세 문서의 관심사가 다릅니다 — **계획(ROADMAP) / 기록(PROGRESS) / 규칙(CONTRIBUTING)**.
내용을 중복해서 적지 말고 각자 자리에 남기세요.

## 작업 규칙

1. **Phase 단위로 진행한다.** `docs/ROADMAP.md`의 Phase를 순서대로 진행하고, 완료 시 체크박스를 갱신합니다.
2. **큰 Phase는 Step으로 쪼개고, Step마다 멈춘다.** 구현 → 테스트 → 설명 → 확인 후 다음 Step으로.
   Step마다 독립 커밋을 남깁니다.
3. **`docs/PROGRESS.md`를 갱신하면서 작업한다.** Step을 끝낼 때마다 무엇을 왜 했는지, 어떤 문제를 만났는지,
   커밋 해시가 무엇인지 기록합니다. 문서 상단의 "현재 위치"도 최신으로 유지합니다.
4. **`main`에 직접 커밋하지 않는다.** 브랜치 전략은 `docs/CONTRIBUTING.md` 참고.
   실패하는 테스트(문제 재현용 등)는 `feature/*` 안에서만 존재할 수 있습니다.
5. **문제를 먼저 재현하고 고친다.** 패턴을 적용하기 전에 현재 코드가 깨지는 걸 실패하는 테스트로
   드러내는 방식을 씁니다 (Phase 2 Step 0 참고).
6. **검증은 테스트 코드로 한다.** 아래 "검증 방법" 참고.

## 검증 방법

**동작 확인은 반드시 테스트 코드로 남깁니다.** curl이나 수동 실행으로 "잘 되네" 하고 넘어가지 마세요.

수동 검증은 그 순간에만 존재합니다. 다음 사람도, 다음 달의 나도, CI도 그걸 다시 해주지 않습니다.
테스트로 남겨야 회귀를 잡습니다.

```
구현 → 테스트 작성 → 통과 확인 → (필요하면) 수동 e2e로 보강
```

### 수동 실행은 보조 수단

실제로 서비스를 띄워 curl로 확인하는 건 **테스트가 놓친 걸 찾는 용도**로는 유용합니다.
Phase 2에서 이렇게 찾은 버그가 여럿 있습니다 (멱등성 키가 삭제되어 이중 출금이 가능했던 문제,
Outbox payload가 TINYTEXT로 생성된 문제).

다만 **수동으로 무언가를 발견했다면, 고치는 것으로 끝내지 말고 반드시 테스트로 고정하세요.**
그러지 않으면 같은 버그가 다시 들어와도 아무도 모릅니다.

### 테스트가 진짜로 잡는지 확인하기

새로 쓴 테스트는 **일부러 코드를 되돌려 실패하는지** 봐야 합니다.
통과하는 것만 보면, 실은 아무것도 검증하지 않는 테스트를 통과로 착각할 수 있습니다.

Testcontainers MySQL로 전환할 때 이 방법으로 확인했습니다 — `@Lob` 길이 지정을 되돌리자
이전에는 통과하던 테스트 8건이 실패했습니다. 반대로 시각 정밀도 버그는 되돌려도
로컬에서 통과해서, macOS에서는 재현되지 않는다는 걸 알게 됐습니다 (아래 "로컬에서 잡히지 않는 것").

## 저장소 구조

Gradle 멀티모듈. 서비스 간에 공유 라이브러리를 두지 않습니다 —
DTO가 중복되더라도 서비스 경계를 유지하는 쪽을 택했습니다.

| 모듈 | 포트 | 스택 | 저장소 |
|---|---|---|---|
| `account-service` | 8081 | Spring MVC + JPA | MySQL `account_db` |
| `transfer-service` | 8082 | Spring MVC + JPA | MySQL `transfer_db` |
| `ledger-service` | 8083 | Spring WebFlux | MongoDB `ledger_db` |
| `reconciliation-service` | 8084 | Spring MVC + JPA | MySQL `reconciliation_db` |
| `gateway` | 8080 | (Phase 4에서 구현) | — |
| `config-server` | 8888 | (Phase 4에서 구현) | — |

`gateway`와 `config-server`는 아직 Phase 0 스켈레톤입니다.
특히 `config-server`는 지금 실행해도 즉시 종료됩니다 — 버그가 아니라 미구현 상태입니다.

### 송금 흐름 (Phase 2 Step 4 이후)

서비스 간 동기 호출은 없습니다. **각 서비스가 이벤트를 보고 스스로 다음을 발행**합니다(Choreography Saga).

```
POST /transfers ─▶ Transfer  transfer.requested       (202 즉시 반환, 상태 PENDING)
                   Account   출금 ─▶ transfer.debited
                   Account   입금 ─▶ transfer.credited
                   Ledger    원장 기록 ─▶ transfer.ledger-recorded
                   Transfer  상태 갱신 ─▶ COMPLETED (+ transfer.completed)
```

실패하면 흐름이 꺾입니다. **어디까지 갔느냐에 따라 되돌릴 게 있고 없고가 갈립니다.**

```
출금 실패 ─▶ Account  transfer.debit-failed
                      Transfer  FAILED (+ transfer.failed)      돈이 안 움직였으므로 종결만

입금 실패 ─▶ Account  transfer.credit-failed
                      Transfer  COMPENSATING                     아직 종결 아님
                      Account   환불 ─▶ transfer.debit-reversed  ← 보상
                      Transfer  FAILED (+ transfer.failed)
```

`transfer.credit-failed`는 **Account가 발행하고 Account가 다시 받습니다.** 한 서비스 안에서
끝낼 수 있는 일을 굳이 브로커에 한 바퀴 돌리는 이유는 재시도입니다 — 요청 스레드에서 곧바로
환불하면 그 환불이 실패했을 때 아무도 다시 해주지 않습니다.

그래서 **전진 단계와 보상 단계는 실패했을 때의 처신이 다릅니다.** 전진 단계는 실패 이벤트를 남기고
물러나지만, 보상 단계는 물러날 곳이 없어 예외를 그대로 던집니다(재배달 → 끝내 안 되면 DLT).

**흐름 전체를 한눈에 볼 수 있는 코드가 없다**는 게 이 방식의 대가입니다.
이벤트 계약은 각 서비스의 `messaging/TransferEvents`에 같은 내용으로 중복 정의되어 있으니
(공유 모듈을 두지 않기로 했으므로) **필드를 바꿀 때는 세 곳을 함께 확인**하세요.

컨슈머는 이벤트를 두 번 받을 수 있습니다(at-least-once). 서비스마다 대응 방식이 다릅니다.
- **Account**: 잔액 변경은 되돌릴 수 없으므로 `processed_events`에 처리 흔적을 남깁니다 (잔액 변경과 같은 트랜잭션).
  실패도 "처리했다"로 기록합니다 — 그러지 않으면 재전송 때마다 실패 이벤트가 새로 나갑니다.
- **Transfer**: 상태에 **진행도**를 매겨 앞으로만 가게 합니다(`TransferStatus`). 지나간 단계는
  진행도가 뒤라 무시되고, 종결 상태(COMPLETED/FAILED)는 무엇이 와도 바뀌지 않습니다.
  단계마다 토픽이 달라 **도착 순서가 보장되지 않으므로**, 뒤 단계가 먼저 오면 건너뛰어서라도 적용합니다 —
  버리면 그 이벤트는 다시 오지 않아 송금이 영원히 멈춥니다 (Step 4d에서 실제로 겪음).
- **Ledger**: 문서 _id를 **발행하는 쪽이 고정해 보낸 분개 항목 ID**(`entryId`)로 삼아 재기록이
  덮어쓰기가 되게 합니다. Step 5a 전에는 자연키(송금+계좌+방향)를 썼는데, 송금과 무관한 변경이
  들어오고 같은 송금에서 같은 계좌가 두 번 움직일 수 있게 되면서(출금 → 환불) 더는 성립하지 않습니다.

### 컨슈머가 실패하면 (Step 4c)

세 서비스 모두 `config/KafkaErrorHandlingConfig`에 같은 정책을 각자 정의합니다
(이벤트 계약과 마찬가지로 **바꿀 때는 세 곳을 함께**).

- **1초 → 2초 → 4초** 백오프로 최대 3회 재시도한 뒤, `<원래 토픽>.DLT`로 보냅니다.
- 결과가 달라질 리 없는 실패(JSON 파싱 실패, 보상 단계의 업무 예외 등)는 **재시도 없이 바로 DLT**입니다.

> ⚠️ 이 설정을 지우면 spring-kafka 기본값(지연 없이 10회 시도 후 **로그만 남기고 오프셋 커밋**)이
> 적용됩니다. 메시지가 조용히 사라지므로, 리스너를 새로 만들 때 이 설정이 적용되는지 확인하세요.

### 토픽은 쓰는 서비스가 모두 선언한다 (Step 4d)

각 서비스의 `config/KafkaTopicsConfig`는 **발행하는 토픽뿐 아니라 소비하는 토픽과 DLT까지** 선언합니다.

컨슈머가 토픽이 만들어지기 전에 구독하면 **브로커가 1파티션으로 자동 생성**해버리고, 뒤늦게 3으로
늘려도 이미 붙은 컨슈머는 모릅니다(기본 `metadata.max.age.ms`가 5분). 세 서비스를 함께 띄운 e2e에서
Saga 전체가 5분 멈추는 걸 겪었습니다.

`KafkaAdmin`은 이미 있는 토픽을 다시 만들지 않고 파티션이 모자랄 때만 늘리므로 중복 선언은 안전합니다.
다만 **선언이 어긋나면 큰 쪽이 이깁니다** — 파티션 수를 바꿀 때는 그 토픽을 쓰는 서비스를 함께 확인하세요.

### 상태 전이는 낙관적 락으로 보호한다 (Step 4d)

리스너 스레드가 토픽마다 다르므로 같은 송금 행을 동시에 건드릴 수 있습니다. `Transfer`의 `@Version`이
그걸 막고, 충돌하면 `TransferService`가 **다시 읽어 전이 조건을 처음부터 판단**합니다.
재시도는 트랜잭션 밖에서 해야 하므로 전이 자체는 `TransferStateUpdater`에 따로 있습니다
(Account의 `guarded` + `SagaStepExecutor`와 같은 구조).

### 정합성 대사 (Phase 2 Step 5)

각 서비스가 옳게 동작해도 **합쳐놓고 보면 어긋날 수 있습니다.** Saga가 끊기거나 이벤트가 DLT로 빠지면
계좌 잔액과 원장이 벌어지고, 송금이 종결되지 못한 채 남습니다. `reconciliation-service`가 주기적으로
세 서비스에 물어보고 그런 것들을 찾아냅니다.

전제는 **모든 잔액 변경이 원장에 남는다**는 것입니다(Step 5a). 잔액을 바꾸는 경로는 반드시
`BalanceJournal`을 지나야 합니다 — 한 경로라도 빠지면 "원장 합 = 잔액"이 깨지고 대사가 무의미해집니다.

```
계좌 잔액 합  vs  원장 합        → BALANCE_MISMATCH
종결 안 된 오래된 송금            → UNSETTLED_TRANSFER
IN_PROGRESS로 남은 멱등성 키      → STRANDED_IDEMPOTENCY_KEY
```

> ⚠️ **대사는 찾아서 알리기만 하고 고치지 않습니다.** 고치는 건 데이터 주인의 몫입니다 —
> 남의 서비스 데이터를 대사가 바꾸기 시작하면 서비스 경계가 무너지고, 원인을 모르는 채 증상만 지우게 됩니다.
> 그래서 각 서비스의 `/internal/reconciliation/*`는 **읽기 전용**입니다.

발견 0건과 "대사가 못 돌았다"는 다릅니다. 회차(`reconciliation_runs`)에 `failureReason`이 있으면
그 회차 결과를 믿으면 안 됩니다.

### 개시 잔액 이월 (Phase 2 Step 6a)

Step 5a 이전에 만들어진 계좌는 그때의 잔액 변경이 원장에 없어, 잔액이 멀쩡해도 계속
`BALANCE_MISMATCH`로 잡혔습니다. 과거를 `OPENING_BALANCE` 분개 **한 줄**로 요약해 심어 맞춥니다.

```
POST /internal/accounts/{id}/opening-balance   { observedBalance, ledgerBalance }
```

- **금액이 아니라 관측한 두 값을 받습니다.** 금액을 그대로 받으면 남이 내 원장에 아무 숫자나
  적을 수 있는 문이 됩니다. 차이는 계좌 서비스가 직접 빼서 정합니다.
- **대사 서비스가 자동으로 부르지 않습니다.** 부르는 순간 대사가 남의 데이터를 고치는 셈입니다.
  보고를 보고 판단해 부르는 건 운영자입니다.
- **잔액을 바꾸지 않습니다.** 없던 돈을 넣는 게 아니라 이미 있던 잔액을 원장에도 적는 일입니다.
- 계좌당 한 번뿐입니다(`accounts.opening_balance_carried_at`). 두 번 심으면 원장이 잔액보다 커집니다.

> ⚠️ 스냅샷 경합을 두 겹으로 막습니다 — **잔액 CAS**(본 잔액 ≠ 지금 잔액이면 거절)와
> **미발행 분개 검사**(Outbox에 안 나간 게 있으면 거절). 뒤엣것이 없으면 "잔액은 그대로인데
> 원장만 뒤처진" 경우를 통과시켜 같은 변경을 두 번 세게 됩니다.
> 그래도 *발행됐지만 아직 소비되지 않은* 이벤트는 못 막으니, **한산할 때 돌리고 다음 대사 회차로
> 확인**하세요.

`OPENING_BALANCE`는 **송금의 다리가 아닙니다.** `isTransferLeg()`에 넣으면 출금 줄 하나와
이월 줄 하나로 "두 줄 모였다"고 판단해 입금 없이 원장 기록 완료를 알리게 됩니다.

### 접수 도중 죽은 멱등성 키 (Phase 2 Step 6b)

접수는 세 번의 커밋입니다 — **키 선점 → 송금 저장 → 키에 결과 기록.** 중간에 죽으면 키가
`IN_PROGRESS`로 남는데, **어디서 죽었느냐에 따라 처신이 정반대**입니다.

```
선점 후 · 송금 저장 전에 죽음   ─▶ 송금이 없다.  키를 놓아줘야 한다
송금 저장 후 · 기록 전에 죽음   ─▶ 송금이 있다.  놓아주면 두 번째 송금이 생긴다
```

`transfers.idempotency_key`가 이 둘을 가르는 근거입니다. **송금 저장과 같은 트랜잭션**에 들어가므로
송금이 있으면 키도 반드시 적혀 있습니다. `TransferService.settleExisting()`이 재요청 때 이걸 보고
스스로 결론짓습니다.

| 상황 | 처신 |
|---|---|
| 그 키로 커밋된 송금이 있다 | 키를 마저 닫고 그 송금을 돌려준다 (전진 복구) |
| 송금이 없고 키가 오래됐다 | 키를 놓아주고 다시 선점해 새로 접수한다 |
| 송금이 없고 키가 방금 것이다 | **409.** 지금 다른 스레드가 접수 중일 수 있다 |

> ⚠️ 세 번째를 두 번째와 섞으면 **살아 있는 접수의 키를 뺏어** 같은 키로 두 건이 접수됩니다.
> 기준은 `IdempotencyService.ABANDON_AFTER`이고, 대사의 `reconciliation.key-stranded-after`와
> 뜻이 같습니다 — **한쪽만 바꾸지 마세요.**

`transfers.idempotency_key`의 unique 제약은 그 위의 안전망입니다. 판정이 어떤 이유로든 뚫려도
같은 키로 두 번째 송금이 저장되는 것 자체를 DB가 막습니다.

대사는 여전히 **고치지 않습니다.** 다만 이제 두 경우를 구분해서 보고합니다
(`StrandedKeyView.committedTransferId`) — 대응이 정반대라 뭉뚱그리면 보는 사람이 접수된 송금을
못 봤다고 착각해 같은 송금을 두 번 보낼 수 있습니다.

## 실행 · 테스트

```bash
# 1. 로컬 인프라 기동 (MySQL·MongoDB·Redis·Kafka. 서비스 컨테이너화는 Phase 6)
docker compose -f docker-compose.dev.yml up -d

# 2. 테스트
./gradlew test

# 3. 서비스 실행 (각각 별도 터미널)
./gradlew :account-service:bootRun
```

- `account-service` / `transfer-service`는 **MySQL이 떠 있지 않으면 부팅 자체가 실패**합니다
  (JPA가 시작 시점에 DB에 붙어 dialect를 판별하기 때문).
- Testcontainers를 쓰는 통합 테스트는 Docker 데몬이 필요합니다.

### 통합 테스트용 컨테이너

각 서비스의 통합 테스트 베이스를 상속해서 씁니다. Java는 단일 상속이라,
**그 서비스가 필요로 하는 컨테이너를 베이스 하나에 모아**둡니다.

| 베이스 | 띄우는 컨테이너 |
|---|---|
| `AbstractIntegrationTest` (account) | MySQL + Redis + Kafka |
| `AbstractIntegrationTest` (transfer) | MySQL + Kafka |
| `AbstractIntegrationTest` (ledger) | MongoDB + Kafka |

**인메모리 DB(H2)를 쓰지 마세요.** 운영과 같은 MySQL을 컨테이너로 띄웁니다.
H2로 돌리던 시절, 테스트를 전부 통과하고도 MySQL에서만 터지는 버그가 두 번 있었습니다 —
`@Lob` 컬럼이 TINYTEXT로 만들어져 "Data too long", 그리고 예약어(`key`) 문제입니다.

> ⚠️ `@Testcontainers` + `@Container` 조합으로 바꾸지 마세요. 그 조합은 **테스트 클래스가 끝날 때마다
> 컨테이너를 멈추기** 때문에, 베이스를 상속한 클래스가 둘 이상이면 두 번째부터
> "Unable to connect"로 실패합니다. (Step 2에서 실제로 겪음)
> 모든 베이스는 **싱글턴 컨테이너 패턴**(static 블록에서 한 번 start + `@DynamicPropertySource`)입니다.

### 로컬에서 잡히지 않는 것

**시각 정밀도 문제는 macOS에서 재현되지 않습니다.** macOS의 `Instant.now()`는 애초에
마이크로초까지만 주지만 Linux는 나노초까지 줍니다. `support/Timestamps`의 잘라내기를 없애도
로컬 테스트는 통과하고 **CI(Linux)에서만 실패**합니다. 시각을 다루는 코드를 고쳤다면
로컬 통과만 믿지 말고 CI 결과를 확인하세요.

## 환경 주의사항

**Spring Boot 4.1 / Jackson 3 / Testcontainers 2.x** 조합이라, 웹에 있는 Boot 3.x 기준 예제와
패키지·아티팩트 좌표가 다른 경우가 많습니다. 이미 겪은 사례들이 `docs/PROGRESS.md`의
"Spring Boot 4.1 이행 이슈" 표에 정리되어 있으니 **새 의존성을 추가하기 전에 먼저 확인하세요.**

새 라이브러리를 붙일 때는 기억에 의존하지 말고, Boot BOM이 해당 의존성을 관리하는지
(`./gradlew :{모듈}:dependencies --configuration testCompileClasspath`) 먼저 확인하는 편이 빠릅니다.

## 언어

문서·커밋 메시지·테스트 메서드명은 한글로 작성합니다. 코드 식별자는 영문입니다.
