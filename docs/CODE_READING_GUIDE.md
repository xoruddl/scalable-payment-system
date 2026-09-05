# 코드 읽기와 면접 준비 가이드

이 문서는 **어떤 순서로 코드를 읽고, 무엇을 설명할 수 있어야 하는지** 안내한다.
첫 목표는 “A가 B에게 1만 원을 송금한다”는 요청을 접수부터 종결까지 따라가는 것이다.
처음부터 모든 클래스와 설정을 외우려 하지 않는다.

## 이미 있는 문서와 역할

| 문서 | 여기서 얻을 것 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 서비스 경계와 이벤트 흐름. 특히 §11은 코드 탐색 안내 |
| [DECISIONS.md](DECISIONS.md) | 기술 선택의 대안·이유·대가와 면접 답변 사례 |
| [PROGRESS.md](PROGRESS.md) | 실제 문제 재현, 수정 과정, 검증 기록 |
| [SLO.md](SLO.md) | 성능 목표와 측정 조건·결과 |
| [ROADMAP.md](ROADMAP.md) | 완료한 것과 아직 계획인 것의 구분 |
| 이 문서 | 코드 → 테스트 → 설명을 연결하는 학습 순서 |

구조·결정·측정 기록은 원래 문서에 둔다. 이 문서의 질문은 학습용이며 구현 완료 체크박스가 아니다.
주석이나 문서에는 이전 구현 설명이 남아 있을 수 있으므로 현재 코드와 테스트를 대조한다.

## 읽는 방법

각 단계에서 다음 네 가지를 짧게 적는다.

1. 입력: HTTP 요청인가, 어떤 이벤트인가?
2. 변경: 어느 서비스의 어떤 데이터를 바꾸는가?
3. 트랜잭션: 함께 커밋되는 것은 무엇인가?
4. 실패: 중복 수신·동시 실행·프로세스 종료가 생기면 어떻게 되는가?

처음에는 내부 송금 정상 경로만 읽고, 다음에 중복·실패·외부 은행으로 넓힌다.

## 1. 접수 — TransferService부터 시작한다

**읽을 코드**

- [TransferController](../transfer-service/src/main/java/com/remittance/transfer/web/TransferController.java): `requestTransfer`, `getTransfer`
- [CreateTransferRequest](../transfer-service/src/main/java/com/remittance/transfer/web/dto/CreateTransferRequest.java): 입력 검증
- [TransferService](../transfer-service/src/main/java/com/remittance/transfer/service/TransferService.java): `requestTransfer`, `settleExisting`, `recoverInProgress`
- [TransferAcceptExecutor](../transfer-service/src/main/java/com/remittance/transfer/service/TransferAcceptExecutor.java): `accept`
- [IdempotencyService](../transfer-service/src/main/java/com/remittance/transfer/service/IdempotencyService.java): 키 선점·내용 비교·복구 조건

**따라갈 흐름**: 요청 검증 → 멱등성 키 선점 → 송금·Outbox·접수 결과 저장 → 202 응답.
현재 키 선점은 별도 트랜잭션이고, 뒤의 송금·Outbox·접수 결과는 한 트랜잭션이다.

**설명할 질문**

- 왜 응답이 202인가? 응답 시점에 잔액은 이미 변경됐는가?
- 같은 키로 같은 요청이 오면? 같은 키로 금액이 달라지면?
- 키 선점 후 죽으면 무엇을 근거로 다시 접수해도 된다고 판단하는가?
- 멱등성 키의 `COMPLETED`와 송금의 `COMPLETED`는 무엇이 다른가?

**같이 볼 테스트**: [TransferAcceptExecutorTest](../transfer-service/src/test/java/com/remittance/transfer/service/TransferAcceptExecutorTest.java), [IdempotencyRecoveryTest](../transfer-service/src/test/java/com/remittance/transfer/service/IdempotencyRecoveryTest.java).

## 2. 돈의 이동 — 업무 규칙을 찾는다

**읽을 코드**

- [TransferSagaService](../account-service/src/main/java/com/remittance/account/saga/TransferSagaService.java): `onRequested` → `onDebited` → `creditInternal`
- [AccountBalance](../account-service/src/main/java/com/remittance/account/domain/AccountBalance.java): `debit`, `credit`, `total`
- [Account](../account-service/src/main/java/com/remittance/account/domain/Account.java): `assertUsable`

**설명할 질문**

- 잔액 부족·정지 계좌·통화 불일치는 어디에서 거절하는가?
- 출금은 잔액 조각 전체를 보고, 입금은 조각 하나만 변경하는 이유는?
- 출금 후 입금을 직접 호출하지 않고 이벤트를 거치는 이유는?
- 송금 상태를 관리하는 서비스와 잔액을 바꾸는 서비스는 왜 다른가?

**같이 볼 테스트**: [AccountBalanceTest](../account-service/src/test/java/com/remittance/account/domain/AccountBalanceTest.java), [TransferSagaServiceTest](../account-service/src/test/java/com/remittance/account/saga/TransferSagaServiceTest.java).

## 3. 정합성 — 보호 장치를 하나씩 빼면 무엇이 깨지는가

```text
TransferSagaService.runStep()
  → AccountService.guarded()       락과 낙관적 충돌 재시도
    → SagaStepExecutor.execute()   한 단계의 트랜잭션
      → AccountBalance.debit()     출금 규칙
```

**읽을 코드**: [AccountService](../account-service/src/main/java/com/remittance/account/service/AccountService.java), [SagaStepExecutor](../account-service/src/main/java/com/remittance/account/saga/SagaStepExecutor.java), [BalanceJournal](../account-service/src/main/java/com/remittance/account/outbox/BalanceJournal.java).

| 장치 | 설명할 문제 |
|---|---|
| 분산 락 | 같은 잔액을 여러 요청이 동시에 변경하는 경합 |
| 낙관적 락 | 분산 락의 TTL 만료 등으로 보호가 깨졌을 때의 동시 갱신 |
| 처리 흔적 | 같은 이벤트가 다시 왔을 때의 중복 출금·입금 |
| Outbox | DB 변경과 Kafka 발행 사이의 부분 실패 |
| BalanceJournal | 잔액은 변했는데 원장에 남지 않는 경로 |

**설명할 질문**

- 처리 흔적·잔액·후속 이벤트·분개 이벤트 중 하나만 별도 커밋하면 무엇이 깨지는가?
- 중복 여부를 먼저 조회하는 대신 처리 흔적을 INSERT하는 이유는?
- 분산 락이 있는데 낙관적 락도 필요한 이유는?
- 재시도와 트랜잭션 실행을 다른 빈으로 나눈 이유는?
- Kafka 발행 후 Outbox 마킹 전에 죽으면? 왜 소비자의 멱등성이 계속 필요한가?

**같이 볼 테스트**: [TransferEventConsumerConcurrencyTest](../account-service/src/test/java/com/remittance/account/messaging/TransferEventConsumerConcurrencyTest.java), [DistributedLockTest](../account-service/src/test/java/com/remittance/account/lock/DistributedLockTest.java), [BalanceJournalTest](../account-service/src/test/java/com/remittance/account/outbox/BalanceJournalTest.java).

## 4. 완료와 실패 — 성공을 언제 확정하는가

**읽을 코드**

- [BalanceChangedConsumer](../ledger-service/src/main/java/com/remittance/ledger/messaging/BalanceChangedConsumer.java)
- [TransactionService](../ledger-service/src/main/java/com/remittance/ledger/service/TransactionService.java): `record`, `isTransferFullyRecorded`
- [TransferStateUpdater](../transfer-service/src/main/java/com/remittance/transfer/service/TransferStateUpdater.java): `advanceTo`, `markCompensating`, `markFailed`
- `TransferSagaService.onCreditFailed`: 환불

**설명할 질문**

- 왜 입금이 끝나도 원장 기록을 기다리는가?
- 원장 완료 조건은 단순히 문서 두 개인가? 환불·개시 잔액도 포함하는가?
- 입금 완료 이벤트가 출금 완료보다 먼저 도착하면 어떻게 처리하는가?
- 출금 실패와 입금 실패의 후속 처리가 다른 이유는?
- 보상은 DB 롤백과 무엇이 다른가? 보상마저 실패하면 어떻게 되는가?

**같이 볼 테스트**: [BalanceChangedConsumerTest](../ledger-service/src/test/java/com/remittance/ledger/messaging/BalanceChangedConsumerTest.java), [TransferStateUpdaterTest](../transfer-service/src/test/java/com/remittance/transfer/service/TransferStateUpdaterTest.java), [TransferCompensationTest](../account-service/src/test/java/com/remittance/account/saga/TransferCompensationTest.java).

## 5. 외부 은행 — 결과를 모르는 돈을 다룬다

**읽을 코드**

- `TransferSagaService.creditExternal`
- [PendingExternalCredits](../account-service/src/main/java/com/remittance/account/external/PendingExternalCredits.java)
- [ExternalCreditProber](../account-service/src/main/java/com/remittance/account/external/ExternalCreditProber.java)
- [ExternalCreditResolver](../account-service/src/main/java/com/remittance/account/saga/ExternalCreditResolver.java)

**설명할 질문**

- 입금 POST가 타임아웃 났을 때 바로 환불하거나 재전송하면 어떤 문제가 생기는가?
- “보냈지만 결과를 모른다”와 “보내지 못했다”를 왜 나누는가?
- 외부 승인 후 우리 정산 계좌에 기록하는 이유는?
- 리스너 분리·격벽·회로 차단기는 각각 어떤 자원을 보호하는가?
- 조회 GET을 신규 입금 POST와 같은 회로로 막으면 어떤 문제가 생기는가?
- 상대가 느리지만 타임아웃 안에 성공하면 회로가 열리는가?

**같이 볼 테스트**: [UnknownCreditTest](../account-service/src/test/java/com/remittance/account/external/UnknownCreditTest.java), [ListenerSeparationTest](../account-service/src/test/java/com/remittance/account/messaging/ListenerSeparationTest.java), [ExternalCallCircuitBreakerTest](../account-service/src/test/java/com/remittance/account/external/ExternalCallCircuitBreakerTest.java).
기술 선택과 실측 근거는 `DECISIONS.md`의 D-002·D-003 및 `PROGRESS.md`에서 확인한다.

## 6. 후속 처리와 성능 — 관심 주제별로 확장한다

| 주제 | 코드·테스트 | 설명할 질문 |
|---|---|---|
| 알림 | [NotificationService](../notification-service/src/main/java/com/remittance/notification/service/NotificationService.java), [PartialSendFailureTest](../notification-service/src/test/java/com/remittance/notification/service/PartialSendFailureTest.java) | 발송 전 SENT로 기록하면? 발송 후 기록 전에 죽으면? |
| 대사 | [ReconciliationService](../reconciliation-service/src/main/java/com/remittance/reconciliation/service/ReconciliationService.java), [ReconciliationServiceTest](../reconciliation-service/src/test/java/com/remittance/reconciliation/service/ReconciliationServiceTest.java) | 왜 자동으로 돈을 고치지 않는가? 발견 0건과 실행 실패는 어떻게 구분하는가? |
| 잔액 샤딩 | [BalanceShards](../account-service/src/main/java/com/remittance/account/service/BalanceShards.java), [BalanceShardingTest](../account-service/src/test/java/com/remittance/account/service/BalanceShardingTest.java) | 왜 입금에는 이득이 있고 출금에는 제한적인가? 변경 후 잔액 표시에 어떤 대가가 있는가? |
| 릴레이 확장 | [OutboxBatchPublisher](../transfer-service/src/main/java/com/remittance/transfer/outbox/OutboxBatchPublisher.java), [OutboxRelayConcurrencyTest](../transfer-service/src/test/java/com/remittance/transfer/outbox/OutboxRelayConcurrencyTest.java) | SKIP LOCKED만으로 부족했던 이유는? Kafka 전송 동안 행 락을 쥐는 대가는? |
| 진입점 | [GatewayRoutingTest](../gateway/src/test/java/com/remittance/gateway/GatewayRoutingTest.java), [JwtAuthFilterTest](../gateway/src/test/java/com/remittance/gateway/JwtAuthFilterTest.java), [RateLimitWithoutRedisTest](../gateway/src/test/java/com/remittance/gateway/RateLimitWithoutRedisTest.java) | 인증과 요청 제한이 장애 시 다르게 동작하는 이유는? |

성능 답변은 `SLO.md`와 `HOMELAB.md`를 함께 읽고 준비한다. TPS만 외우지 않고 계좌 분포,
종결 p99, 오류·유실·대사 결과, 워밍업과 실행 환경을 함께 설명한다.
단일 인스턴스의 측정값을 여러 인스턴스의 검증 결과로 확대해서 말하지 않는다.

## 면접 답변을 만드는 방법

주제마다 다음 다섯 문장을 직접 채운다. 상세 기술 결정은 `DECISIONS.md`에 남긴다.

```text
문제: 어떤 상황에서 무엇이 깨지거나 느려졌나?
대안: 어떤 방법들을 비교했나?
선택 이유: 현재 문제에 이 방법이 맞는 이유는?
포기한 것: 얻지 못한 보장, 복잡성, 비용은?
검증: 어떤 테스트와 측정으로 확인했고, 아직 확인하지 못한 범위는?
```

테스트는 준비 상황 → 실행 → 검증을 읽고, 마지막 검증문이 보장하는 범위를 설명한다.
재현 실습으로 보호 코드를 잠시 제거할 때는 별도 작업 브랜치에서 수행하고 원복한다.
기존 재현 기록을 읽은 것과 직접 실패를 재현한 것은 구분해서 말한다.

AI가 작성한 코드도 입력·출력·트랜잭션·실패 처리를 직접 설명하고 변경 결과를 예측해 본다.
설계 판단, 구현 보조, 직접 수행한 검증 중 본인의 기여가 무엇인지 구체적으로 정리한다.

## 첫 학습의 완료 기준

1~3번을 먼저 읽고 아래를 코드 없이 설명한 뒤, 근거 메서드를 찾아 연다.

- 내부 송금 요청 하나의 이벤트 흐름을 그릴 수 있다.
- 서비스별로 무엇을 저장하고 무엇을 모르는지 설명할 수 있다.
- 중복 요청과 중복 이벤트를 서로 다른 곳에서 막는 이유를 설명할 수 있다.
- 처리 흔적·잔액 변경·Outbox의 트랜잭션 경계를 그릴 수 있다.
- 관련 테스트 하나의 실패 조건과 검증 범위를 설명할 수 있다.

그다음 4번의 실패·보상, 5번의 외부 은행, 6번의 측정과 확장 순서로 넓힌다.
