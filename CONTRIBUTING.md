# CONTRIBUTING

이 저장소에서 작업하는 모든 기여자/에이전트가 따라야 할 규칙입니다.

## 커밋 컨벤션

[Conventional Commits](https://www.conventionalcommits.org/) 형식을 따릅니다.

```
<type>: <설명>

<본문 (선택, 변경 이유·상세 내용)>
```

### type 목록

| type | 용도 |
|---|---|
| `feat` | 새로운 기능/모듈/도메인 모델 추가 |
| `fix` | 버그 수정 |
| `chore` | 빌드 설정, 의존성, `.gitignore` 등 코드 동작에 영향 없는 잡무 |
| `docs` | 문서(README, ROADMAP, API 설계 문서 등)만 변경 |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `test` | 테스트 코드 추가/수정 |
| `style` | 포맷팅 등 코드 의미에 영향 없는 변경 |
| `perf` | 성능 개선 |
| `build` | Gradle/Docker 등 빌드 시스템 변경 |
| `ci` | CI 파이프라인 설정 변경 |

### 규칙

- 제목은 명령형, 한글 설명 뒤 마침표 생략
- 하나의 커밋은 하나의 관심사를 담는다 — 여러 type이 섞이면 커밋을 분리
- 본문에는 "무엇을"보다 "왜"를 우선 기록
- 예시:
  ```
  feat: Phase 0 프로젝트 기반 설정 (멀티모듈 구조 및 도메인 모델)

  - Gradle 멀티모듈 구조 전환 (account-service, transfer-service,
    ledger-service, gateway, config-server)
  - 도메인 모델 설계: Account, Transfer/IdempotencyKey, Transaction(원장)
  ```

## 진행 방식

`ROADMAP.md`의 Phase 단위로 진행하며, Phase 완료 시 체크박스를 갱신한다.
