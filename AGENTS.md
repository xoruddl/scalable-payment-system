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

`AbstractRedisIntegrationTest`(account) / `AbstractMongoIntegrationTest`(ledger) /
`AbstractKafkaIntegrationTest`(transfer)를 상속해서 씁니다.
세 베이스 모두 **싱글턴 컨테이너 패턴**(static 블록에서 한 번 start + `@DynamicPropertySource`)입니다.

> ⚠️ `@Testcontainers` + `@Container` 조합으로 바꾸지 마세요. 그 조합은 **테스트 클래스가 끝날 때마다
> 컨테이너를 멈추기** 때문에, 베이스를 상속한 클래스가 둘 이상이면 두 번째부터
> "Unable to connect"로 실패합니다. (Step 2에서 실제로 겪음)

## 환경 주의사항

**Spring Boot 4.1 / Jackson 3 / Testcontainers 2.x** 조합이라, 웹에 있는 Boot 3.x 기준 예제와
패키지·아티팩트 좌표가 다른 경우가 많습니다. 이미 겪은 사례들이 `docs/PROGRESS.md`의
"Spring Boot 4.1 이행 이슈" 표에 정리되어 있으니 **새 의존성을 추가하기 전에 먼저 확인하세요.**

새 라이브러리를 붙일 때는 기억에 의존하지 말고, Boot BOM이 해당 의존성을 관리하는지
(`./gradlew :{모듈}:dependencies --configuration testCompileClasspath`) 먼저 확인하는 편이 빠릅니다.

## 언어

문서·커밋 메시지·테스트 메서드명은 한글로 작성합니다. 코드 식별자는 영문입니다.
