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
- **Transfer**: 상태 전이에 조건을 걸어 자연스럽게 멱등합니다. 정상 흐름은 "직전 단계일 때만",
  실패 흐름은 **여러 상태에서 받아줍니다** — 실패 이벤트는 정상 이벤트와 다른 토픽이라 도착 순서가
  보장되지 않고, 한 점만 허용하면 그 이벤트를 버려 송금이 PENDING에 갇힙니다.
- **Ledger**: 문서 _id를 거래의 자연키(송금+계좌+방향)로 만들어 재기록이 덮어쓰기가 되게 합니다.

### 컨슈머가 실패하면 (Step 4c)

세 서비스 모두 `config/KafkaErrorHandlingConfig`에 같은 정책을 각자 정의합니다
(이벤트 계약과 마찬가지로 **바꿀 때는 세 곳을 함께**).

- **1초 → 2초 → 4초** 백오프로 최대 3회 재시도한 뒤, `<원래 토픽>.DLT`로 보냅니다.
- 결과가 달라질 리 없는 실패(JSON 파싱 실패, 보상 단계의 업무 예외 등)는 **재시도 없이 바로 DLT**입니다.

> ⚠️ 이 설정을 지우면 spring-kafka 기본값(지연 없이 10회 시도 후 **로그만 남기고 오프셋 커밋**)이
> 적용됩니다. 메시지가 조용히 사라지므로, 리스너를 새로 만들 때 이 설정이 적용되는지 확인하세요.

## 실행 · 테스트

```bash
# 1. 로컬 인프라 기동 (MySQL·MongoDB·Redis·Kafka. 서비스 컨테이너화는 Phase 6)
docker compose -f docker-compose.dev.yml up -d

# 2. 테스트
./gradlew :account-service:test :transfer-service:test :ledger-service:test

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
