# 프로젝트 로드맵 — 송금/결제 시스템

## 확정된 방향
- **도메인**: 송금/결제 시스템 (계좌, 이체, 잔액 정합성, 거래 내역)
- **언어**: Java (Spring Boot)
- **인프라**: 로컬 K8s(minikube/kind) + Istio 실제 구축, 서비스 메시 트래픽 정책 적용
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

- [ ] **버전을 커밋 SHA 기반으로** — 지금 `version = '0.0.1-SNAPSHOT'` 고정이라
      **무엇이 배포됐는지 구분할 수 없습니다.** Phase 7에서 이미지 태그로 반드시 필요
- [ ] **브랜치 보호** — `main`·`develop`에 PR 필수 + CI 통과 필수.
      Phase 2·3을 e2e 없이 `main`에 올렸다가 되돌린 적이 있는데, **규칙으로 막을 수 있는 일**입니다
- [ ] **테스트 분리** — 지금 통합 테스트(Testcontainers)가 PR마다 전부 돕니다.
      Phase 5에서 부하 테스트까지 붙으면 더 느려집니다. 단위/통합을 태그로 갈라
      PR에선 빠른 것만 (`reproductionTest` 태스크를 만든 것과 같은 방식)
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

### Step 1 — 부하를 걸 수단
- [ ] k6 시나리오 작성
      - 정상 송금 (계좌를 골고루)
      - **핫 계좌 집중** — 여러 명이 같은 계좌로 입금 (Phase 6의 주 소재)
      - 거래내역 조회 폭주 (ledger)
- [ ] 시나리오를 저장소에 넣어 재현 가능하게 (`load-test/`)

### Step 2 — 결과를 볼 수단
- [ ] Prometheus + Grafana 기동, 각 서비스 `/actuator/prometheus` 수집
      (Actuator·Micrometer는 Phase 0부터 이미 붙어 있음)
- [ ] 대시보드: TPS, p95/p99 지연, 에러율
- [ ] **직접 심어야 하는 메트릭** — 이게 없으면 병목이 어디인지 못 봅니다
      - Outbox **미발행 적체 건수** (릴레이가 못 따라가는지)
      - 분산 락 **대기 시간**과 **획득 실패 횟수**
      - **낙관적 락 충돌 횟수**
      - Kafka **consumer lag**
      - HikariCP **커넥션 사용률·대기 시간**
- [ ] 정합성 대사 결과를 메트릭으로 노출 (발견 건수·유형별, 마지막 회차 실패 여부와 경과 시간)
      — Phase 2에서 대사는 만들었지만 **알리는 경로가 없다.** 지금은 API를 열어봐야 안다

### Step 3 — baseline 측정 ★
- [ ] **지금 시스템의 천장을 측정하고 기록한다.** 검증할 가설:
      | 가설 | 근거 |
      |---|---|
      | 송금 TPS가 **50 근처**에서 막힌다 | Outbox 릴레이가 500ms×100건 = 초당 200 이벤트인데, 송금 1건이 account에서 4개를 만든다 |
      | 같은 계좌는 **초당 수십~수백 건**이 상한 | 계좌 락으로 완전 직렬화 |
      | 락 대기 3초를 넘기면 **대량 실패** | `LockAcquisitionException` |
      | 커넥션 10개가 **금방 고갈** | 락을 쥔 채 커넥션을 들고 있음 |
- [ ] 측정 결과를 `PROGRESS.md`에 숫자로 남긴다 (이후 모든 비교의 기준)

## Phase 6. 고동시성 — 병목을 하나씩 뚫는다

> **측정 → 병목 지목 → 대응 → 재측정**을 한 바퀴씩 돕니다.
> 개선마다 **전후 숫자**를 `DECISIONS.md`에 남깁니다 — 숫자 없는 개선은 안 한 것과 같습니다.

### Step 1 — 핫 계좌 (hot account) ★ 이 Phase의 본론

정산 계좌·가맹점 대표 계좌처럼 **입금이 한 계좌로 몰리는** 경우입니다.
**서버를 늘려도 안 풀리는 병목**을 직접 만나는 게 목적입니다.

- [ ] 락 **홀딩 시간 단축** — 지금은 락 안에서 JPA 트랜잭션 전체가 돈다
- [ ] **분산 락을 빼고 낙관적 락 + 재시도만**으로 돌려보고 비교
      → 충돌이 적은 계좌는 빨라지고 **핫 계좌는 오히려 나빠진다**는 걸 확인.
        여기서 **계좌별로 전략을 나눈다**는 발상이 나옵니다
- [ ] **잔액 샤딩** — 핫 계좌 잔액을 N개 서브 잔액으로 쪼갠다.
      입금은 랜덤 샤드, 조회는 합산. **출금이 복잡해지는 것**이 대가
- [ ] (선택) **배치 집계** — 입금을 모았다가 주기 반영. 처리량 ↔ 잔액 반영 지연

### Step 2 — 파이프라인 병목
- [ ] **🔁 폴링 Outbox 릴레이 → Debezium(CDC)** — Phase 5에서 잡힌 50 TPS 천장의 원인.
      **병목임을 숫자로 보여준 뒤** 갈아탑니다
- [ ] Kafka 파티션 수와 리스너 `concurrency` 조정 (지금 리스너당 스레드 1개)
- [ ] 커넥션 풀 튜닝(HikariCP), DB 커넥션 고갈 대응

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

## Phase 9. 서비스 메시 (Istio)
- [ ] Istio 설치 및 사이드카 주입
- [ ] mTLS로 서비스 간 통신 암호화
- [ ] VirtualService/DestinationRule로 카나리 배포·트래픽 분할 시연
- [ ] Istio 레벨 Circuit Breaking / Retry / Timeout 정책 적용
      — Phase 6에서 **애플리케이션 레벨(Resilience4j)**로 했던 것과 비교
- [ ] **🆕 Argo Rollouts로 카나리 자동화** — Istio가 트래픽을 나누고, Rollouts가
      메트릭(Phase 5의 Prometheus)을 보며 단계적으로 올리거나 자동 롤백합니다
      (토스는 Spinnaker. 대체 이유는 `DECISIONS.md`)

## Phase 10. 관측 심화
> 메트릭·대시보드는 Phase 5에서 이미 섰습니다. 여기서는 **로그와 추적**입니다.
- [ ] ELK(Elasticsearch/Logstash/Kibana)로 로그 중앙화
- [ ] 분산 트레이싱 (OpenTelemetry, Istio/Envoy 연계)
      — Choreography라 **흐름이 코드에 안 보이는** 구조라서, 추적이 특히 값어치가 큽니다
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
| Consul | **K8s 자체 디스커버리 + Istio** | Phase 8~9 |

- [ ] 각 대체 선택의 이유를 `DECISIONS.md`에 정리 (원래 것이 주는 가치 → 무엇으로 대신했나 → 못 얻은 것)
- [ ] (여유가 되면) Vault 하나만 실제로 붙여보기 — 시크릿은 지금 평문이라 실익이 큽니다

---

## 표기

- `🔁` — **자체 구현을 현업 기술로 갈아타는 항목.** 왜 그때인지, 무엇을 얻고 잃는지는
  `DECISIONS.md`에 있습니다.
- `🆕` — **없던 것을 새로 도입하는 항목.** 주로 CD 쪽입니다 (지금 CD가 전무합니다).

## 진행 방식
Phase 단위로 하나씩 진행하며, 각 Phase 완료 시 동작 확인 후 다음 Phase로 이동합니다.

동작 확인은 **테스트 + 크로스 서비스 e2e** 둘 다입니다. 테스트만 통과한 상태로 `main`에
올리지 않습니다 — 이 저장소는 e2e가 테스트를 통과한 코드에서 결함을 잡아낸 전례가 여럿입니다.