# CLEAN CODE

코드를 쓰거나 고칠 때 지키는 체크리스트다. 우아한테크코스의 클린코드 체크리스트를 따른다.

리뷰 전에 스스로 훑고, 어긴 항목이 있으면 **왜 어겼는지 한 줄**을 남긴다.
지키지 못할 이유가 있는 항목은 아래 "이 저장소에서의 예외"에 이미 적혀 있다.

## 체크리스트

### 자바 코드 컨벤션을 지켰는가

- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) 기준
- [번역·요약](https://myeonguni.tistory.com/1596)
- IntelliJ의 formatting을 돌린다

### 한 메서드에 들여쓰기를 한 단계만 썼는가

- `for` 안의 `if`처럼 두 단계가 되면 안쪽을 메서드로 뽑는다

### else 예약어를 쓰지 않았는가

- 이른 반환(early return)으로 대신한다

### 모든 원시값과 문자열을 포장했는가

- `long accountId` 대신 `AccountId`, `BigDecimal amount` 대신 `Money`
- 포장하면 그 타입에 검증과 연산이 모인다

### 콜렉션에 일급 콜렉션을 적용했는가

- 콜렉션 필드 하나만 가지는 클래스로 감싼다
- 콜렉션에 걸리는 규칙(중복 금지, 개수 제한, 합계)이 그 클래스 안에 들어간다

### 인스턴스 변수가 3개를 넘지 않는가

- 쉽지 않은 연습이다. 넘더라도 줄이려는 시도는 한다

### getter/setter 없이 구현했는가

- 핵심 로직을 담는 도메인 객체에 getter/setter를 두지 않는다
- 값을 꺼내 밖에서 판단하지 말고, 객체에게 시킨다
- **DTO는 허용한다**

### 메서드 인자 수를 제한했는가

- 4개 이상은 허용하지 않는다
- 3개도 가능하면 줄인다

### 코드 한 줄에 점(`.`)을 하나만 썼는가

- 디미터의 법칙 — 친구하고만 대화한다
- `location.current.representation.substring(0, 1)`처럼 점이 여럿이면 리팩터링할 자리다

### 메서드가 한 가지 일만 하는가

### 클래스를 작게 유지했는가

## 이 저장소에서의 예외

체크리스트는 도메인 객체를 겨냥한 것이다. Spring 인프라 코드에 그대로 대면 지킬 수 없는
항목이 생기므로, 아래 셋은 **예외로 못 박고 나머지는 예외 없이 지킨다.**

| 항목 | 예외 | 이유 |
|---|---|---|
| 인스턴스 변수 3개 | `@Component`·`@Service`의 생성자 주입 필드 | 협력자 수는 설계의 결과지 필드 욕심이 아니다. 대신 **주입이 4개 이상이면 책임을 쪼갤 신호로 본다** |
| getter/setter | JPA 엔티티, `record` DTO, 설정 프로퍼티 | 프레임워크가 접근자를 요구한다 |
| 원시값 포장 | Kafka 이벤트 페이로드, DB 컬럼 매핑 | 직렬화 경계에서는 원시값을 그대로 쓴다. 포장은 그 경계 안쪽에서 한다 |

예외를 쓰더라도 **도메인 로직은 예외 밖이다.** 엔티티가 getter를 갖는 것과, 잔액 판단을
엔티티 밖에서 하는 것은 다른 문제다. 잔액을 바꾸는 규칙은 `Account` 안에 있어야 한다.

## 지금 어디에 서 있나 (2026-09-13 측정)

| 항목 | 현황 |
|---|---|
| `else` 사용 | main 코드 전체에서 **2건** |
| 인스턴스 변수 4개 이상 | **12 / 201 클래스** |
| 주입 4개 이상 (쪼갤 신호) | **9 클래스** — 아래 표 |

`else`는 이미 지켜지고 있다.

**쪼갤 신호를 "5개 초과"에서 "4개 이상"으로 낮췄다 (2026-09-13).** `TransferSagaService`를
7개에서 5개로 줄이는 안을 보니, 5개 안에도 성격이 다른 두 묶음(단계 실행 장치, 외부 입금)이
그대로 섞여 있었다. 5는 쪼갤 자리를 가려주지 못하는 기준이었다.

신호는 "본다"는 뜻이지 "쪼갠다"는 뜻이 아니다. 봤는데 둘 이유가 있으면 그 이유를 적는다.

| 클래스 | 주입 | 판단 |
|---|---|---|
| `SagaStepExecutor` | 6 | 아직 안 봤다 |
| `TransferService` (transfer) | 5 | 아직 안 봤다 |
| `TransferSagaService` | 4 | 7 → 4로 쪼갠 결과다. 남은 넷(단계 실행 · 외부 호출 · 미결 기록 · 정산 계좌)은 각자 다른 일이라, 더 묶으면 호출을 넘기기만 하는 클래스가 생긴다 |
| `ExternalCreditProber` | 4 | 게이트웨이로 6 → 4. 더는 안 봤다 |
| `AccountService` | 4 | 아직 안 봤다 |
| `BalanceGuard` | 4 | 아직 안 봤다 |
| `OpeningBalanceExecutor` | 4 | 아직 안 봤다 |
| `PendingExternalCredits` | 4 | 아직 안 봤다 |
| `ReconciliationChecks` (reconciliation) | 4 | 아직 안 봤다 |

`MeterRegistry`도 주입으로 센다 — 계측도 협력자다.

아래 스크립트는 `private final` 필드를 모두 세므로 주입이 아닌 필드도 들어간다.
`ReconciliationMetrics`(주입 3 + 상태 4), `DistributedLock`(주입 2 + 회로 1 + 계측 3),
`ExternalCallCircuitBreaker`(주입 필드 없음)는 스크립트에는 걸리지만 위 표에서 뺐다.
주입 수는 생성자를 보고 셌다.

측정은 이렇게 다시 잰다.

```bash
# else 사용
grep -rn "} else" --include="*.java" . | grep "/main/" | grep -v "/build/" | wc -l

# 인스턴스 변수 4개 이상인 클래스
for f in $(find . -name "*.java" | grep "/main/" | grep -v "/build/"); do
  n=$(grep -cE "^\s*private final .*;" "$f"); [ "$n" -ge 4 ] && echo "$n  ${f#./}"
done | sort -rn
```
