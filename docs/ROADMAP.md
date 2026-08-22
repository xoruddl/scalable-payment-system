# 프로젝트 로드맵 — 송금/결제 시스템

## 확정된 방향
- **도메인**: 송금/결제 시스템 (계좌, 이체, 잔액 정합성, 거래 내역)
- **언어**: Java (Spring Boot)
- **인프라**: 로컬 K8s(minikube/kind) 실제 구축
- **서비스 메시(Istio)는 범위에서 뺐습니다** — 이 시스템은 서비스 간 통신이 거의 Kafka라
  메시가 줄 수 있는 게 mTLS 정도입니다. 비용은 다 치르고 배우는 건 적어, **별도로 공부**합니다.
  판단 근거는 `DECISIONS.md`에 있습니다
- **최종 형태**: **현업에서 실제로 쓰는 구조.** 패턴을 먼저 손으로 만들어 이해한 뒤,
  검증된 기술로 갈아탑니다. 교체 대상과 시점은 `DECISIONS.md`에 있습니다.

## 커버해야 할 요구사항
- 대규모 트래픽 환경에서 발생할 수 있는 문제점
- 분산 환경에서의 데이터 정합성 보장 방법
- 시스템 과부하 상황에 대한 대응 방안
- 확장성을 고려한 아키텍처 설계

## 순서의 원칙 — **재고 나서 고친다**

Phase 5(측정 기반)가 Phase 6(고동시성)보다 **앞에 있는 건 의도된 것**입니다.

```
❌ 캐시를 넣는다 → 나중에 부하 테스트한다      "느릴 것 같아서 넣었습니다"
✅ 부하를 걸어 천장을 잰다 → 병목을 지목한다   "X TPS에서 막혔고 원인이 Y였습니다"
   → 고친다 → 같은 시나리오로 다시 잰다        "Z로 올랐고 대신 W를 잃었습니다"
```

**숫자 없는 개선은 안 한 것과 같습니다.** 그리고 면접에서도 앞의 답변은 통하지 않습니다.
개선마다 전후 숫자를 `DECISIONS.md`에 남깁니다.

---

## 상시 — CI 위생 (Phase가 아님)

CI는 `.github/workflows/build.yml` 하나가 이미 돌고 있습니다(actionlint + JDK 21 + `./gradlew build`).
아래 셋은 **뒤 Phase의 전제**라 늦어지면 곧바로 발목을 잡습니다.

- [x] **빌드가 자기 커밋을 알게 하기** — `bootBuildInfo`로 커밋·브랜치·빌드 시각을 새겨
      `GET /actuator/info`가 답하게 했습니다.
      > 원래 계획은 "Gradle 버전을 커밋 SHA로"였는데 **바꿨습니다.** 아티팩트 버전은 *제품* 버전이고,
      > "지금 떠 있는 게 어느 커밋이냐"는 **실행 중인 프로세스에 물어봐야** 답이 됩니다.
      > 이미지 태그는 Phase 7에서 `github.sha`를 그대로 씁니다. 근거는 `DECISIONS.md`
- [x] **브랜치 보호** — `main`에만 걸었습니다. PR 필수 + CI(`unit`/`build`/`lint-workflows`)
      통과 필수 + force push·삭제 금지. **승인자는 0명**(혼자라 1명 이상이면 자기 PR을 자기가
      승인 못 해 잠깁니다), **관리자 우회는 허용**(실수는 막고 의도적 우회는 열어둠).
      `develop`은 통합 브랜치라 자유롭게 뒀습니다.
      > Phase 2·3을 e2e 없이 `main`에 올렸다가 되돌린 적이 있습니다. 이제 실수로는 안 됩니다
- [x] **테스트 분리** — `./gradlew unitTest`(Docker 불필요, **9초 / 331건**)와
      `./gradlew test`(전체, 1분 43초 / 431건)로 갈랐습니다.
      CI는 `unit` → `build` 순으로 돌아, 오타 하나 때문에 컨테이너를 띄우지 않습니다
- [ ] (선택) **변경된 모듈만 빌드** — 모노레포인데 `notification-service`만 고쳐도 5개를 다 돌립니다

---

## Phase 0. 프로젝트 기반 설정
- [x] git 저장소 초기화, `.gitignore` 정리
- [x] Gradle 멀티모듈 구조로 전환 (`account-service`, `transfer-service`, `ledger-service`, `gateway`, `config-server` 등)
- [x] 도메인 모델 설계: 계좌(Account), 거래(Transaction), 원장(Ledger), 멱등성 키(IdempotencyKey)
- [x] API 설계 문서 작성 (계좌 생성/조회, 송금 요청/조회, 거래 내역 조회)

## Phase 1. 핵심 도메인 서비스 구현
- [x] **Account Service**: 계좌 생성/조회, 잔액 관리 (Spring MVC + JPA/Hibernate + MySQL)
- [x] **Transfer Service**: 송금 요청 처리, 상태 관리 (요청 → 처리중 → 완료/실패)
- [x] **Ledger Service**: 거래 내역 저장/조회 (Spring WebFlux + MongoDB, 조회 트래픽 대응 목적)
- [x] 낙관적 락(`@Version`)으로 동시 잔액 갱신 충돌 처리

## Phase 2. 분산 환경 데이터 정합성
- [x] 멱등성 처리: 송금 요청에 idempotency key 적용 (중복 요청 방지)
- [x] 분산 락: Redis로 계좌별 동시 이체 직렬화 (Redisson 대신 `SET NX PX` + Lua 직접 구현)
- [x] Saga 패턴(Choreography): 출금 → 입금 → 원장 기록 단계별 보상 트랜잭션 설계
      — 정상 흐름 Step 4a, 보상 흐름 Step 4b, 컨슈머 재시도·DLT Step 4c
- [x] Outbox 패턴: DB 트랜잭션과 이벤트 발행의 원자성 보장 (Kafka 연계)
- [x] 정합성 검증 배치/스케줄러: 계좌 잔액 합 vs 원장 합 대사(reconciliation) 로직
      — 별도 `reconciliation-service`로 분리. 전제로 모든 잔액 변경을 원장에 남기도록 바꿈(Step 5a)
- [x] 개시 잔액 이월: 원장 도입 이전 잔액을 분개 한 줄로 심어 묵은 불일치 정리 (Step 6a)
- [x] 멱등성 키↔송금 연결: 접수 도중 죽은 흔적을 구분해 안전하게 걷어내기 (Step 6b)

## Phase 3. 이벤트 기반 아키텍처 ✅

> Kafka를 Phase 2로 당겨왔기 때문에 아래 세 항목은 Phase 2에서 이미 끝났습니다.
> Phase 3는 **토픽 확장과 새 컨슈머**가 중심이 됐습니다.

- [x] Kafka 토픽 설계 — 실제로는 Saga 단계별로 더 늘어남
      (`transfer.requested` / `debited` / `credited` / `ledger-recorded` / `completed` / `failed`)
- [x] Transfer Service → Kafka Producer (Outbox 기반)
- [x] Ledger Service → Kafka Consumer (거래 내역 적재)
- [x] Notification 관련 이벤트 컨슈머 (알림 발송 시뮬레이션)
      — 별도 `notification-service`로 분리. `transfer.completed`와 `transfer.failed`를 구독한다
      (성공만 알리면 정작 사용자가 알아야 할 실패를 못 알린다)

## Phase 4. API Gateway & 설정 관리
- [ ] **🔁 손으로 쓴 `openapi.yaml` → springdoc-openapi** (Gateway가 쓸 계약이므로 먼저 정리)
- [ ] Spring Cloud Gateway로 단일 진입점 구성 (라우팅, 인증 필터)
- [ ] Spring Cloud Config Server로 중앙 설정 관리 (Git 기반 config repo)
- [ ] Netty 기반 리액티브 게이트웨이 특성 활용 (비동기 논블로킹)

## Phase 5. 측정 기반 마련 — **먼저 재고 나서 고친다**

> **이 Phase가 없으면 뒤가 전부 "느낄 것 같아서 고쳤다"가 됩니다.**
> 부하를 걸 수단과 결과를 볼 수단을 먼저 만들고, **지금 시스템의 천장을 숫자로 박아둡니다.**
> 그 baseline이 이후 모든 개선의 기준선이자 면접 답변의 출발점입니다.

### Step 1 — 부하를 걸 수단 ✅
- [x] k6 시나리오 작성 (`load-test/scenarios/`)
      - `spread.js` — 계좌를 골고루. 락 경합이 낮아 **파이프라인 병목**이 드러난다
      - `hot-account.js` — **핫 계좌 집중.** Phase 6의 주 소재
      - `read-heavy.js` — 거래내역 조회 폭주 (ledger)
- [x] 저장소에 넣어 재현 가능하게 (`load-test/`, 사용법은 그 안 `README.md`)
- [x] **부하와 측정을 분리** — 모든 요청을 폴링하면 폴링이 곧 부하가 된다.
      `load`(접수만) + `prober`(초당 1건, 끝까지 추적) 두 시나리오를 동시에 돌린다
- [x] **smoke 모드** — `SMOKE=1`로 20초 만에 스크립트가 맞는지 먼저 확인

### Step 2 — 결과를 볼 수단 ✅
- [x] Prometheus + Grafana 기동, 각 서비스 `/actuator/prometheus` 수집
      (`docker-compose.dev.yml` + `docker/prometheus`, `docker/grafana`. 대시보드도 코드로 둔다)
- [x] 대시보드: TPS, p95/p99 지연, 에러율 (`송금 시스템 — 개요`)
- [x] **히스토그램 버킷을 켠다** — 안 켜면 `_count`/`_sum`만 나와 p95·p99가
      **조용히 빈 패널**이 된다. `MetricsDistributionConfig`(코드로 둔 이유는 PROGRESS 참고)
- [x] **직접 심어야 하는 메트릭** — 이게 없으면 병목이 어디인지 못 봅니다
      - [x] Outbox **미발행 적체 건수** — `remittance.outbox.backlog`
      - [x] 분산 락 **대기 시간**과 **획득 실패 횟수** — `remittance.lock.wait{outcome}`
            (실패는 별도 카운터가 아니라 같은 타이머의 `outcome=timeout`이다)
      - [x] **낙관적 락 충돌 횟수** — `remittance.optimistic.lock.conflict{entity,outcome}`
      - [x] Kafka **consumer lag** — `kafka_consumer_fetch_manager_records_lag_max`로 이미 나온다
      - [x] **DLT 적재 건수** (토픽별) — `remittance.kafka.dlt.published{topic}`.
            e2e에서 확인한 대로 로그도 한 줄 없었으므로 **복구 시점 WARN**도 함께 붙였다
      - [x] HikariCP **커넥션 사용률·대기 시간** — `hikaricp_connections_*`로 이미 나온다
- [x] 정합성 대사 결과를 메트릭으로 노출 — `remittance.reconciliation.*`.
      가장 중요한 건 발견 건수가 아니라 **마지막 회차로부터 흐른 시간**이다.
      건수만 보면 "어긋난 게 없었다"와 "대사가 안 돌았다"가 똑같이 0이다
- [ ] **남은 것**: 알림(Alertmanager)이 없어 지금은 화면을 열어야 보인다.
      `spring_kafka_listener_seconds`의 라벨이 컨테이너 빈 이름이라 어느 토픽인지 안 읽힌다

### Step 3 — baseline 측정 ★ ✅
- [x] **지금 시스템의 천장을 측정하고 기록했다** (2026-08-22, 커밋 `28b7a15`).
      가설 넷 중 **하나만 맞았다**:
      | 가설 | 결과 |
      |---|---|
      | 송금 TPS가 **50 근처**에서 막힌다 | 🔴 틀렸다 — 실제 **12~14건/s** |
      | 같은 계좌는 **초당 수십~수백 건**이 상한 | ⬜ 관측 안 됨 — 락 대기 최대 **22ms** |
      | 락 대기 3초를 넘기면 **대량 실패** | ⬜ 관측 안 됨 — 실패 **0건** |
      | 커넥션 10개가 **금방 고갈** | ✅ 맞았다 — transfer **pending 193**, 획득 p99 **4.3초** |
- [x] 측정 결과를 `PROGRESS.md`에 숫자로 남겼다 (이후 모든 비교의 기준)
- [ ] **원장 조회(시나리오 C)는 다시 재야 한다** — 호스트 CPU가 91%까지 차서
      k6와 JVM 다섯 개가 CPU를 두고 싸운 값이다. 부하 생성기 분리는 Phase 11 소재

## Phase 6. 고동시성 — 병목을 하나씩 뚫는다

> **측정 → 병목 지목 → 대응 → 재측정**을 한 바퀴씩 돕니다.
> 개선마다 **전후 숫자**를 `DECISIONS.md`에 남깁니다 — 숫자 없는 개선은 안 한 것과 같습니다.

> ⚠️ **baseline이 이 Phase의 순서를 바꿨습니다.** 아래 Step 1(핫 계좌)이 본론인 건 그대로지만,
> **Step 2(파이프라인)를 먼저 해야 합니다.** 핫 계좌를 400 req/s로 때려도 락 대기가 22ms에
> 그쳤는데, 락이 튼튼해서가 아니라 **그 앞에서 막혀 부하가 락까지 닿지 않기 때문**입니다.
> 접수는 초당 300건을 받는데 릴레이가 못 내보내 5만 5천 건이 쌓이고, 컨슈머에는 초당 12건만
> 도착합니다. 지금 핫 계좌를 고치면 **개선해도 숫자가 안 움직입니다.**
> 파이프라인을 뚫어 부하가 락까지 닿게 만든 뒤가 핫 계좌의 차례입니다.

### Step 1 — 핫 계좌 (hot account) ★ 이 Phase의 본론 (단, Step 2 다음에)

정산 계좌·가맹점 대표 계좌처럼 **입금이 한 계좌로 몰리는** 경우입니다.
**서버를 늘려도 안 풀리는 병목**을 직접 만나는 게 목적입니다.

- [ ] 락 **홀딩 시간 단축** — 지금은 락 안에서 JPA 트랜잭션 전체가 돈다
- [ ] **분산 락을 빼고 낙관적 락 + 재시도만**으로 돌려보고 비교
      → 충돌이 적은 계좌는 빨라지고 **핫 계좌는 오히려 나빠진다**는 걸 확인.
        여기서 **계좌별로 전략을 나눈다**는 발상이 나옵니다
- [ ] **잔액 샤딩** — 핫 계좌 잔액을 N개 서브 잔액으로 쪼갠다.
      입금은 랜덤 샤드, 조회는 합산. **출금이 복잡해지는 것**이 대가
- [ ] (선택) **배치 집계** — 입금을 모았다가 주기 반영. 처리량 ↔ 잔액 반영 지연

### Step 2 — 파이프라인 병목 ← **baseline이 지목한 첫 표적. 여기부터 한다**

baseline 실측: 종결 처리량 **12~14건/s**, Outbox 적체 최대 **약 55,000건**,
transfer HikariCP **pending 193** / 획득 대기 p99 **4.3초**.

- [ ] **커넥션 풀 튜닝(HikariCP)** — 접수 p99를 2.8초까지 밀어올린 직접 원인.
      가장 싸고 효과가 즉시 보이는 표적이라 먼저 한다
- [ ] **릴레이 처리량** — 지금은 500ms 폴링 × 100건이고 건마다 `send().join()`으로 직렬이다.
      배치 크기·폴링 간격·비동기 발행을 하나씩 바꿔가며 **각각 몇 건/s를 벌어주는지** 잰다
- [ ] **🔁 폴링 Outbox 릴레이 → Debezium(CDC)** — 위를 다 해도 폴링이 상한이면 갈아탄다.
      **병목임을 숫자로 보여준 뒤** 바꾼다
- [ ] Kafka 파티션 수와 리스너 `concurrency` 조정 (지금 리스너당 스레드 1개)
- [ ] 재측정해서 **부하가 계좌 락까지 닿는지** 확인 — 닿아야 Step 1이 의미를 가진다

### Step 3 — 락 자체를 바꾼다
- [ ] **🔁 자체 분산 락(`SET NX PX` + Lua) → Redisson**
      — Step 1에서 **락이 병목임을 확인한 뒤**. watchdog·재진입·pub/sub 대기

### Step 4 — 읽기 부하와 방어
- [ ] Redis 캐싱: 계좌 조회 등 읽기 트래픽
- [ ] **캐시 스탬피드 대응** — 인기 계좌 캐시가 만료되는 순간 전부 DB로 몰린다.
      여기서 **이미 만든 분산 락을 재활용**하게 됩니다
- [ ] Rate Limiting: Gateway 레벨 (Redis 기반 토큰 버킷) — 핫 계좌 보호가 실제 근거
- [ ] Circuit Breaker / Bulkhead: Resilience4j (서비스 간 장애 전파 차단)
- [ ] WebFlux 백프레셔 활용 (Ledger 조회 API)

### Step 5 — 재측정
- [ ] Phase 5의 baseline과 **같은 시나리오로** 다시 측정해 비교
- [ ] 개선별 전후 숫자를 `DECISIONS.md`에 기록

## Phase 7. 컨테이너화
- [ ] **🔁 `ddl-auto: update` → Flyway** — 컨테이너 여럿이 동시에 뜨면 DDL이 경합합니다.
      컨테이너화보다 **먼저** 들어가야 합니다 (지금도 테스트 신뢰도를 갉아먹는 중)
- [ ] 각 서비스 Dockerfile 작성 (멀티스테이지, Spring Boot **layered jar**로 레이어 캐싱)
- [ ] `docker-compose.yml`로 로컬 통합 환경 구성 (MySQL, MongoDB, Redis, Kafka)
- [ ] 로컬 통합 테스트 (docker-compose 기동 후 전체 플로우 확인)
- [ ] **CI에 이미지 빌드·푸시 추가** — 레지스트리는 **GHCR**
      (토스는 사내 레지스트리 Harbor. 대체 이유는 `DECISIONS.md`)
- [ ] **태그는 커밋 SHA.** `latest`를 쓰지 않습니다 — 무엇이 떠 있는지 알 수 없게 됩니다
- [ ] **Trivy로 이미지 취약점 스캔** — Harbor가 해주는 일 중 하나를 CI에서 대신합니다

## Phase 8. Kubernetes 배포
- [ ] **🔁 맨 `@Scheduled` → ShedLock** — replica가 늘면 Outbox 릴레이와 대사가 중복 실행됩니다.
      **HPA를 켜기 전에** 들어가야 합니다. 없으면 **스케일 아웃이 곧바로 버그**입니다
- [ ] minikube 또는 kind로 로컬 클러스터 구성
- [ ] 서비스별 Deployment/Service/ConfigMap/Secret manifest 작성
- [ ] **Kustomize로 환경별 차이 관리** (Helm보다 배우기 쉽고 K8s 네이티브)
- [ ] **🆕 ArgoCD 설치 — 여기서 처음으로 CD가 생깁니다**
      - 매니페스트 저장소를 앱 코드와 분리할지 결정 (GitOps의 첫 결정)
      - **Pull 기반**의 의미를 직접 확인: Git을 고치면 클러스터가 스스로 맞추고,
        수동으로 바꾸면 **drift로 잡힙니다**. 되돌리기가 `git revert`가 됩니다
- [ ] **시크릿을 평문에서 걷어내기** — 지금 `application.yml`에 `root/root`가 그대로 있습니다.
      K8s Secret은 base64일 뿐 암호화가 아니므로 **Sealed Secrets** 또는 **External Secrets**
      (토스는 Vault. 대체 이유는 `DECISIONS.md`)
- [ ] HPA(HorizontalPodAutoscaler)로 확장성 검증 (부하 시 자동 스케일 아웃)
      — Phase 6에서 **"서버를 늘려도 안 풀리는 병목"**을 겪었으므로, 여기서
        **무엇이 스케일 아웃으로 풀리고 무엇이 안 풀리는지** 대비가 됩니다
- [ ] Ingress 구성

## Phase 9. 배포 전략 — 무중단·카나리

> 원래 이 자리는 Istio였습니다. **서비스 메시를 뺐다고 카나리까지 포기할 이유는 없습니다** —
> 트래픽 분할은 Ingress로도 됩니다. 메시가 주는 건 *더 정밀한 분할*이지 *카나리 그 자체*가 아닙니다.

- [ ] 롤링 업데이트 기본기 — readiness/liveness probe, `maxSurge`/`maxUnavailable`,
      **graceful shutdown**(Kafka 컨슈머가 처리 중인 메시지를 흘리지 않게)
- [ ] **🆕 Argo Rollouts로 카나리** — NGINX Ingress로 트래픽을 나누고, Rollouts가
      메트릭(Phase 5의 Prometheus)을 보며 단계적으로 올리거나 **자동 롤백**합니다
      (토스는 Spinnaker. 대체 이유는 `DECISIONS.md`)
- [ ] **비동기 시스템의 카나리는 무엇인가** ★ — 이 프로젝트 고유의 숙제입니다.
      HTTP 트래픽을 10%만 새 버전으로 보내도, **Kafka 컨슈머는 파티션 단위로 붙어서
      트래픽 비율대로 나뉘지 않습니다.** 새 버전 컨슈머가 어떤 파티션을 잡을지 모릅니다
      - 컨슈머 그룹을 나눠 일부만 새 버전으로 돌릴지
      - 아니면 컨슈머는 카나리 대상에서 빼고 API 계층만 할지
- [ ] 배포 중 **이벤트 계약 호환성** 확인 — `DECISIONS.md`의 "배포 순서 문제"를 실제로 겪어보기

## Phase 10. 관측 심화
> 메트릭·대시보드는 Phase 5에서 이미 섰습니다. 여기서는 **로그와 추적**입니다.
- [ ] ELK(Elasticsearch/Logstash/Kibana)로 로그 중앙화
- [ ] **분산 트레이싱 (OpenTelemetry)** — Choreography라 **흐름이 코드에 안 보이는** 구조라
      추적이 특히 값어치가 큽니다. 사실상 `ARCHITECTURE.md`를 대신 그려주는 도구입니다
      - ⚠️ **메시가 없으니 애플리케이션에서 직접 붙여야 합니다.** 그리고 사이드카가 있어도
        **Kafka를 건너면 추적이 끊깁니다** — trace context를 이벤트 본문에 실어 보내는
        (W3C traceparent) 작업은 어차피 애플리케이션 몫입니다
      - 즉 **Istio를 뺐다고 잃는 게 없는 항목**입니다
- [ ] DLT 적체 알림 — 지금은 메시지가 DLT로 빠져도 아무도 모릅니다

## Phase 11. 대규모 부하 테스트 & 장애 주입
> Phase 5·6은 **단일 병목**을 봤다면, 여기서는 **클러스터 전체**를 봅니다.
- [ ] K8s 위에서 대규모 트래픽 시뮬레이션
- [ ] 과부하 상황에서 Rate Limiter / Circuit Breaker 동작 확인
- [ ] HPA 오토스케일링 동작 확인
- [ ] **장애 주입** 후 정합성 대사로 검증 (서비스 강제 종료, 네트워크 지연, DB 장애)

## Phase 12. Saga 오케스트레이션 (Choreography와 비교)

지금은 Choreography라 **흐름 전체를 볼 수 있는 코드가 없고, 타임아웃이 없습니다.**
같은 도메인을 오케스트레이션으로도 구현해 나란히 두고 비교하는 것이 목표입니다.

- [ ] 이벤트("일어난 일") → 명령("해야 할 일")으로 계약 전환
- [ ] **🔁 자체 Choreography Saga → Temporal 또는 Camunda 8**
- [ ] 타임아웃·재개 검증 (지금은 응답이 안 오면 대사가 나중에 발견할 뿐)
- [ ] 두 방식의 장단 비교를 `DECISIONS.md`에 기록

## Phase 13. 문서로 대체한 것들

**전부 세우면 학습이 아니라 삽질이 됩니다.** 아래는 더 가벼운 것으로 대체하고,
**무엇을 왜 대체했는지**를 `DECISIONS.md`에 남깁니다 — 면접에서 필요한 건 그 이유입니다.

| 원래 스택 | 이 프로젝트 | 어디서 |
|---|---|---|
| Harbor / Ceph | **GHCR** + Trivy | Phase 7 |
| Vault | **Sealed / External Secrets** | Phase 8 |
| Spinnaker | **Argo Rollouts** | Phase 9 |
| GoCD / Jenkins | **GitHub Actions** | 이미 있음 |
| Consul | **K8s 자체 디스커버리** | Phase 8 |
| **Istio** | **빼고 별도 학습** | 아래 참고 |

- [ ] 각 대체 선택의 이유를 `DECISIONS.md`에 정리 (원래 것이 주는 가치 → 무엇으로 대신했나 → 못 얻은 것)
- [ ] (여유가 되면) Vault 하나만 실제로 붙여보기 — 시크릿은 지금 평문이라 실익이 큽니다

### Istio는 왜 뺐나

**적용할 자리가 거의 없어서**입니다. 이 시스템은 서비스 간 동기 호출이 사실상 없습니다
(대사의 읽기 전용 조회 하나뿐). 그래서:

| Istio가 주는 것 | 여기서는 |
|---|---|
| 트래픽 분할·카나리 | 나눌 **서비스 간** 트래픽이 없음 |
| 재시도·타임아웃·회로 차단 | **Kafka 컨슈머에는 안 걸림.** 이미 `DefaultErrorHandler`로 처리 중 |
| 분산 트레이싱 | **Kafka를 건너면 끊김.** 애플리케이션에서 trace context를 이벤트에 실어야 함 |
| mTLS | 이건 됨 — 얻는 게 사실상 이것뿐 |

**설계가 잘못된 게 아니라 도구가 안 맞는 것**입니다. 비동기 이벤트 중심으로 간 건 Phase 2의
의도된 선택이고, Istio는 서비스들이 서로 HTTP/gRPC로 부르는 구조에서 빛납니다.

> ⚠️ **Istio를 쓰려고 동기 호출을 일부러 만들면 안 됩니다.** 아키텍처를 도구에 맞추는 건
> 거꾸로이고, 면접에서 "왜 이벤트 기반인데 동기 호출이 있죠?"로 되돌아옵니다.

리소스도 현실적인 벽입니다. Phase 8~9쯤이면 서비스 5개 + 인프라 + Prometheus/Grafana + ArgoCD가
동시에 떠 있어야 하는데, 여기에 사이드카와 istiod까지 붙으면 **16GB로도 빠듯**합니다.

- [ ] Istio는 **별도 프로젝트로 학습** — 동기 호출이 많은 작은 샘플에서 하는 편이 훨씬 잘 보입니다

---

## 표기

- `🔁` — **자체 구현을 현업 기술로 갈아타는 항목.** 왜 그때인지, 무엇을 얻고 잃는지는
  `DECISIONS.md`에 있습니다.
- `🆕` — **없던 것을 새로 도입하는 항목.** 주로 CD 쪽입니다 (지금 CD가 전무합니다).

## 진행 방식
Phase 단위로 하나씩 진행하며, 각 Phase 완료 시 동작 확인 후 다음 Phase로 이동합니다.

동작 확인은 **테스트 + 크로스 서비스 e2e** 둘 다입니다. 테스트만 통과한 상태로 `main`에
올리지 않습니다 — 이 저장소는 e2e가 테스트를 통과한 코드에서 결함을 잡아낸 전례가 여럿입니다.