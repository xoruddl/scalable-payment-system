# 프로젝트 로드맵 — 송금/결제 시스템

## 확정된 방향
- **도메인**: 송금/결제 시스템 (계좌, 이체, 잔액 정합성, 거래 내역)
- **언어**: Java (Spring Boot)
- **인프라**: 로컬 K8s(minikube/kind) + Istio 실제 구축, 서비스 메시 트래픽 정책 적용

## 커버해야 할 요구사항
- 대규모 트래픽 환경에서 발생할 수 있는 문제점
- 분산 환경에서의 데이터 정합성 보장 방법
- 시스템 과부하 상황에 대한 대응 방안
- 확장성을 고려한 아키텍처 설계

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
- [ ] 멱등성 처리: 송금 요청에 idempotency key 적용 (중복 요청 방지)
- [ ] 분산 락: Redis(Redisson) 또는 Zookeeper로 계좌별 동시 이체 직렬화
- [ ] Saga 패턴(Choreography): 출금 → 입금 → 원장 기록 단계별 보상 트랜잭션 설계
- [ ] Outbox 패턴: DB 트랜잭션과 이벤트 발행의 원자성 보장 (Kafka 연계)
- [ ] 정합성 검증 배치/스케줄러: 계좌 잔액 합 vs 원장 합 대사(reconciliation) 로직

## Phase 3. 이벤트 기반 아키텍처
- [ ] Kafka 토픽 설계 (`transfer.requested`, `transfer.completed`, `transfer.failed`)
- [ ] Transfer Service → Kafka Producer (Outbox 기반)
- [ ] Ledger Service → Kafka Consumer (거래 내역 적재)
- [ ] Notification 관련 이벤트 컨슈머 (알림 발송 시뮬레이션)

## Phase 4. API Gateway & 설정 관리
- [ ] Spring Cloud Gateway로 단일 진입점 구성 (라우팅, 인증 필터)
- [ ] Spring Cloud Config Server로 중앙 설정 관리 (Git 기반 config repo)
- [ ] Netty 기반 리액티브 게이트웨이 특성 활용 (비동기 논블로킹)

## Phase 5. 과부하 대응 & 캐싱
- [ ] Redis 캐싱: 계좌 조회 등 읽기 트래픽 캐시
- [ ] Rate Limiting: Gateway 레벨 (Redis 기반 토큰 버킷)
- [ ] Circuit Breaker / Bulkhead: Resilience4j 적용 (서비스 간 장애 전파 차단)
- [ ] WebFlux 백프레셔 활용 (Ledger 조회 API)
- [ ] 커넥션 풀 튜닝 (HikariCP), DB 커넥션 고갈 대응

## Phase 6. 컨테이너화
- [ ] 각 서비스 Dockerfile 작성
- [ ] `docker-compose.yml`로 로컬 통합 환경 구성 (MySQL, MongoDB, Redis, Kafka+Zookeeper)
- [ ] 로컬 통합 테스트 (docker-compose 기동 후 전체 플로우 확인)

## Phase 7. Kubernetes 배포
- [ ] minikube 또는 kind로 로컬 클러스터 구성
- [ ] 서비스별 Deployment/Service/ConfigMap/Secret manifest 작성
- [ ] HPA(HorizontalPodAutoscaler)로 확장성 검증 (부하 시 자동 스케일 아웃)
- [ ] Ingress 구성

## Phase 8. 서비스 메시 (Istio)
- [ ] Istio 설치 및 사이드카 주입
- [ ] mTLS로 서비스 간 통신 암호화
- [ ] VirtualService/DestinationRule로 카나리 배포·트래픽 분할 시연
- [ ] Istio 레벨 Circuit Breaking / Retry / Timeout 정책 적용

## Phase 9. 관측성 (Observability)
- [ ] Prometheus + Grafana로 메트릭 수집/대시보드 (요청량, 지연시간, 에러율)
- [ ] ELK(Elasticsearch/Logstash/Kibana)로 로그 중앙화
- [ ] 분산 트레이싱 고려 (Istio/Envoy 트레이싱 연계)

## Phase 10. 부하 테스트 & 검증
- [ ] k6 또는 JMeter로 대규모 트래픽 시뮬레이션
- [ ] 과부하 상황에서 Rate Limiter/Circuit Breaker 동작 확인
- [ ] HPA 오토스케일링 동작 확인
- [ ] 정합성 대사 로직으로 장애 주입 후 데이터 정합성 검증

## Phase 11. 선택 항목 (범위 초과 시 문서화로 대체 가능)
- [ ] Vault: 시크릿 관리 연동
- [ ] Consul: 서비스 디스커버리 (또는 K8s 자체 디스커버리로 대체)
- [ ] ArgoCD: GitOps 매니페스트 작성 (실제 설치는 선택)
- [ ] GoCD: CI 파이프라인 설계 문서화 (실제 설치는 무거우므로 선택)
- [ ] Harbor/Ceph: 사내 레지스트리/스토리지 성격 — 로컬 재현 실익 낮음, 문서화로 대체 권장

---

## 진행 방식
Phase 단위로 하나씩 진행하며, 각 Phase 완료 시 동작 확인 후 다음 Phase로 이동합니다.