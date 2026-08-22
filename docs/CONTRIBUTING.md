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

## 브랜치 전략

[git flow](https://nvie.com/posts/a-successful-git-branching-model/)를 따릅니다.
단, git flow의 `master` 역할은 `main` 브랜치가 맡습니다.

| 브랜치 | 역할 | 분기 원본 | 머지 대상 |
|---|---|---|---|
| `main` | 제품으로 출시될 수 있는 상태 | — | — |
| `develop` | 다음 출시 버전을 개발하는 통합 브랜치 | `main` | — |
| `feature/*` | 기능 개발 | `develop` | `develop` |
| `release/*` | 이번 출시 버전 준비 | `develop` | `main` + `develop` |
| `hotfix/*` | 출시 버전에서 발생한 버그 수정 | `main` | `main` + `develop` |

### 명명 규칙

- `feature/phase-2-data-consistency` — Phase 단위 작업은 `feature/phase-{번호}-{주제}`
- `release/phase-2` — 출시 준비
- `hotfix/{요약}` — 예: `hotfix/transfer-balance-mismatch`

### 규칙

- **`main`에 직접 커밋하지 않는다.** 항상 `release/*` 또는 `hotfix/*`를 거친다.
  이제 **저장소 설정으로 강제**됩니다 — `main`은 PR을 거쳐야 하고 CI(`unit`/`build`/
  `lint-workflows`)가 통과해야 머지됩니다. force push와 브랜치 삭제도 막혀 있습니다.
  (관리자 우회는 열어뒀지만, 그건 **의도적으로 설정을 끌 때만** 되는 것이지 실수로는 안 됩니다.
  `develop`은 통합 브랜치라 보호하지 않았습니다.)
- `main`은 언제나 빌드/테스트가 통과하는 상태를 유지한다.
  실패하는 테스트(문제 재현용 등)는 `feature/*` 안에서만 존재할 수 있다.
- 하나의 `feature/*` 브랜치 안에서는 작업 흐름 단위로 커밋을 쪼갠다.
  (예: Phase 2는 Step 0~5를 각각 별도 커밋으로)

### 출시 주기와 태그

`ROADMAP.md`의 **Phase 하나를 한 번의 출시로 본다.**

```
feature/phase-N-* → develop → release/phase-N → main (+ 태그) → develop 역머지
```

Phase 완료 시 `main`에 `phase-N-complete` 형식의 annotated 태그를 단다.
나중에 특정 Phase 시점의 코드로 되돌아가거나, 카나리 배포·롤백 실습(Phase 7~8)의
기준점으로 활용한다.

## 진행 방식

`ROADMAP.md`의 Phase 단위로 진행하며, Phase 완료 시 체크박스를 갱신한다.
