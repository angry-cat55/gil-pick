# 길픽 협업 및 AI 개발 지침

## 1. 적용 범위와 공통 기준

- 길픽은 4인 팀이 `GitHub Flow`와 `GitHub Spec Kit` 기반 SDD(Spec-Driven Development)로 개발한다.
- 이 파일은 Codex와 Claude를 포함한 모든 AI 개발 도구와 팀원이 따르는 저장소 공통 지침이다.
- constitution이 팀 합의로 확정되면 프로젝트 최상위 원칙으로 적용한다. 기능별 요구사항과 구현 범위는 해당 feature의 `spec.md`, `plan.md`, `tasks.md`를 기준으로 판단한다.
- 작업을 시작하기 전에 이 파일, 관련 `docs/` 문서, 해당 feature의 `spec.md`, `plan.md`, `tasks.md`를 먼저 읽는다. 산출물이 없거나 서로 충돌하면 임의로 보완하지 말고 누락·충돌을 먼저 알린다.
- 불확실한 내용은 확인된 사실, 근거가 있는 추론, 검증되지 않은 추측으로 구분한다. 확인할 수 없는 내용은 모른다고 명시한다.
- `.agents/skills/`, `.claude/skills/`, `.specify/templates/`, `.specify/scripts/`, `.specify/workflows/` 등 Spec Kit 생성·관리 파일은 도구 설정 변경이나 업그레이드가 작업 범위인 경우에만 수정한다. `.specify/memory/constitution.md`는 팀이 원칙을 합의한 뒤 constitution 작업으로 수정한다.

### 팀원 식별과 담당 영역

| GitHub 계정 | 이름 | 이니셜 | 기본 담당 |
|---|---|---|---|
| `angry-cat55` | 유지환 | `jh` | Backend |
| `xotlr467-cpu` | 유태식 | `ts` | Backend |
| `NKIA-SJY` | 신재영 | `jy` | Frontend Android |
| `aihonte` | 전현서 | `hs` | Frontend Android |

- 작업 담당자는 GitHub Issue assignee를 위 표와 대조해 식별한다.
- 작업 브랜치가 이미 있으면 브랜치 이니셜과 Issue assignee가 일치하는지 확인한다.
- 대화에서 사용자가 이니셜을 명시했다면 해당 정보를 함께 사용한다.
- assignee가 없거나 여러 명이라 담당자를 특정할 수 없거나, assignee와 브랜치 이니셜이 다르면 branch 생성 또는 작업 시작 전에 사용자에게 확인한다.
- Git commit author나 과거 작업 기록만으로 담당자 이니셜을 추측하지 않는다.

## 2. 문서 작성 언어

- 프로젝트 문서의 기본 작성 언어는 한글이다.
- `AGENTS.md`, `CLAUDE.md`, constitution, feature별 `spec.md`, `plan.md`, `tasks.md`와 기타 운영·설계 문서의 설명 문장은 가능한 한 한글로 작성한다.
- 코드, 명령어, 파일명, 경로, API 이름, enum, 클래스명, 함수명, GitHub keyword, conventional commit type처럼 영어 표기가 자연스러운 기술 항목은 영어를 유지한다.
- `.specify/` 내부의 Spec Kit 기본 템플릿, skill, command 설명처럼 도구 사용설명서 성격의 파일은 원문 언어를 유지해도 된다.
- 사용자와 팀원을 위한 설명을 불필요하게 영어로 작성하지 않는다.

## 3. GitHub Flow와 브랜치

- `main`은 유일한 장기 브랜치이며 항상 배포·통합 가능한 상태를 유지한다. `main`에 직접 commit하거나 push하지 않는다.
- 새 작업을 시작할 때는 먼저 `main`으로 이동해 `git pull --ff-only origin main`으로 최신 상태를 받은 다음 작업 브랜치를 생성한다.
- 모든 작업은 GitHub Issue 단위로 관리하고, 각 Issue마다 최신 `main`에서 별도의 작업 브랜치를 생성한다.
- 브랜치명은 `<브랜치종류>/<이름이니셜>-<작업내용>` 형식을 사용한다.
  - 이름 이니셜은 `jh`, `ts`, `jy`, `hs` 중 담당자의 값을 사용한다.
  - 브랜치 종류는 작업 성격에 맞춰 `feat`, `fix`, `docs`, `refactor`, `test`, `chore` 등을 사용한다.
  - 작업내용은 짧고 구체적인 kebab-case 영어를 권장한다.
  - 예: `feat/jh-kakao-login`, `fix/ts-token-refresh`, `docs/jy-api-contract`
- 원칙적으로 한 브랜치와 한 PR은 하나의 Issue만 해결한다. 서로 다른 목적의 변경이나 무관한 정리는 섞지 않는다.
- 구현 완료 후 관련 테스트를 실행하고 `commit → push → PR 생성 → review → squash merge` 순서로 진행한다.
- PR을 만들기 전 최신 `origin/main`을 반영하고, 충돌 해결 후 관련 검증을 다시 실행한다.
- PR은 팀원 review를 거쳐 기본적으로 squash merge한다. merge가 끝난 작업 브랜치는 삭제를 권장한다.
- AI는 담당 Issue의 구현·검증·commit·push와 PR 생성까지 수행하고, 생성한 PR의 URL과 검증 결과를 개발자에게 전달한다.
- PR의 review 완료 여부와 병합 가능 여부는 개발자가 판단하며, 최종 merge도 개발자가 직접 수행한다.
- AI는 검증과 CI가 모두 통과했더라도 PR을 직접 merge하거나 merge queue 또는 auto-merge에 등록하지 않는다.
- Git 브랜치명(예: `feat/jh-kakao-login`)과 Spec Kit feature 경로(예: `specs/001-kakao-login`)는 별도 식별자다. 같은 기능임을 Issue와 PR에서 연결하되 두 이름을 동일 형식으로 만들려고 하지 않는다.

## 4. GitHub Issue 작성과 작업 단위

- Issue에는 목적, 작업 범위, 완료 조건, 테스트 방법, 관련 문서 또는 의존 Issue를 명시한다.
- 한 Issue는 한 사람이 독립적으로 구현하고 테스트할 수 있으며, 다른 팀원이 변경 범위와 완료 여부를 독립적으로 review할 수 있는 크기로 나눈다.
- 프론트엔드와 백엔드 작업은 가능한 한 별도 Issue로 분리한다. 양쪽을 함께 다뤄야 하는 계약 확정이나 통합 검증은 별도의 통합 Issue로 둔다.
- 선행 작업이 있으면 `blocked by #번호` 또는 동등하게 명확한 방식으로 의존성을 기록한다.
- 작업 중 Issue 범위를 실질적으로 벗어나는 요구가 발견되면 몰래 포함하지 말고 기존 Issue 조정 또는 새 Issue가 필요한지 먼저 식별한다.

## 5. Commit과 Pull Request

- commit은 review와 되돌리기가 가능한 논리 단위로 작성하며, 관련 없는 변경을 포함하지 않는다.
- PR 제목은 conventional commit 스타일인 `<type>: <한글 요약>` 형식을 사용한다.
  - 예: `feat: 카카오 로그인 구현`, `docs: API 계약 갱신`
- PR 제목과 본문은 conventional commit type, GitHub keyword, API·코드 식별자 등 영어가 자연스러운 항목을 제외하고 한글로 작성한다.
- PR 본문에는 다음 항목을 포함한다.
  - 작업 요약
  - 주요 변경사항
  - 테스트 결과와 실제 실행한 명령어
  - 관련 Issue
- Issue의 모든 완료 조건을 충족한 경우에만 `Closes #번호`를 사용한다.
- Issue의 일부만 해결했거나 참고 관계만 있으면 `Refs #번호`를 사용한다.
- 실행하지 못한 테스트는 통과했다고 표현하지 말고, 미실행 항목과 이유를 명시한다.
- secret, credential, 개인용 AI 설정, 빌드 산출물, 머신 종속 경로를 commit하지 않는다.

## 6. Spec Kit 기반 SDD 운영 흐름

### 전체 흐름

1. MVP Feature 목록 생성
2. constitution 후보 검토 및 팀 합의 후 확정
3. 구현할 기능 선택
4. `spec` 생성
5. `clarify` 수행
6. `plan` 생성
7. 다음 Feature 담당자가 팀원과 Frontend·Backend 담당자 합의
8. 합의 결과를 반영해 `tasks` 생성
9. task를 GitHub Issue로 분리하고 assignee와 dependency 반영
10. 각 담당자가 Issue별 구현·테스트
11. 명세·구현·테스트 결과 검증(verify)
12. PR review 및 merge

- 새로운 기능을 임의로 추가하지 않고 합의된 MVP 범위 안에서 다음 Feature를 선정한다.
- 다음 Feature는 MVP Feature 목록, 완료·진행 중인 Feature, GitHub Issues, Feature 간 의존성을 확인한 뒤 선택한다.
- 다음 Feature 담당자는 `tasks` 생성 전에 팀원과 Frontend·Backend 담당자를 합의하고, 그 결과를 `tasks.md`와 GitHub Issue assignee에 반영한다. 합의되지 않은 담당자는 `미정`으로 기록하며 AI가 임의로 배정하지 않는다.
- 현재 Feature가 구현 단계에 들어가면 다른 담당자는 다음 Feature 하나의 `spec → clarify → plan → tasks`를 미리 준비할 수 있다.
- 구현보다 앞서 상세화하는 Feature는 최대 1개로 제한한다.
- 원칙적으로 `현재 Feature: 구현 중`, `다음 Feature: 상세 설계 준비 가능`, `그 이후 Feature: 상세 명세 미생성` 상태를 유지한다.
- 앞선 구현에서 API, ERD, 정책, 요구사항이 바뀌어 후속 명세가 낡는 일을 줄이기 위해 여러 후속 Feature를 한꺼번에 상세화하지 않는다.
- 하나의 Feature가 완전히 구현될 때까지 다음 Feature의 설계를 무조건 기다릴 필요는 없지만, 선행 Feature의 미확정 계약에 의존하는 내용은 확정된 것처럼 작성하지 않는다.
- 설치된 Spec Kit bundle workflow에는 포함되지 않은 `clarify`와 GitHub Issue 변환도 각각 설치된 `speckit-clarify`, `speckit-taskstoissues`를 사용해 위 순서대로 수행한다.
- verify는 별도 Spec Kit command명이 아니라 팀 검증 단계다. Issue 완료 조건, 관련 테스트·정적 분석, `spec.md`·`plan.md`·`tasks.md`와 구현의 일치 여부를 확인하고, 필요하면 `speckit-checklist`, `speckit-analyze`, `speckit-converge`를 사용한다.

## 7. `tasks.md` 작성 원칙

- task는 하나의 feature 안에서 구현 가능한 최소 작업 단위로 나누고, 이후 GitHub Issue로 전환할 수 있을 만큼 범위와 완료 조건을 구체화한다.
- 프론트엔드, 백엔드, 테스트, 통합 작업을 구분한다.
- 서로 다른 파일을 다루고 미해결 선행 관계가 없어 동시에 수행할 수 있는 task에만 `[P]`를 표시한다.
- 각 task에 담당자 이니셜 또는 `미정`을 기록한다.
- 선행 task나 Issue가 있으면 식별자를 명시하고, 없으면 `없음`으로 기록한다.
- 구현 task의 설명에는 정확한 수정 대상 파일 경로를 포함한다. 설정·검증처럼 단일 대상 파일이 없는 task는 구체적인 실행 대상과 검증 방법을 적는다.
- Spec Kit의 GitHub Issue 변환이 인식하는 checklist prefix와 간결한 설명은 첫 줄에 유지하고, 담당자·영역·선행 관계·검증 방법은 들여쓴 보조 메타데이터로 기록한다.

```text
- [ ] T001 [P] [US1] 로그인 요청 DTO 구현 in api/src/auth/schemas.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: DTO validation test 통과
- [ ] T002 [US1] 로그인 화면 연동 in android/app/src/main/java/com/gilpick/auth/LoginScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: UI test와 API 연동 확인
- [ ] T003 [US1] 로그인 통합 테스트 in api/tests/integration/test_kakao_login.py
  - 영역: 통합
  - 담당: jy
  - 선행: T001, T002
  - 검증: 인증 성공·실패 흐름 통과
```

- `[P]` task라도 같은 계약이나 파일을 동시에 수정하면 먼저 소유권과 작업 순서를 조율한다.
- task 완료는 코드 작성만을 뜻하지 않는다. 명시된 테스트, 완료 조건, 필요한 문서 동기화까지 끝나야 한다.
- 자동 테스트가 없는 문서·설정 작업은 검토 방법과 확인 결과를 테스트 대신 기록한다.
- `speckit-taskstoissues` 변환 후에는 보조 메타데이터의 담당자와 선행 관계를 실제 GitHub Issue의 assignee와 dependency에 반영했는지 확인한다.

## 8. 명세 충돌과 문서 동기화

- 구현 중 API 계약, DB 구조, 사용자 동작, 정책이 기존 명세와 충돌하면 코드만 임의로 바꾸지 않는다. 어떤 문서와 결정의 변경이 필요한지 먼저 식별하고 팀 합의를 거친다.
- 외부 계약에 영향을 주는 변경은 해당 feature의 `spec.md`·`plan.md`와 관련 `docs/`의 API·ERD·요구사항 문서를 같은 작업 범위에서 동기화한다.
- 사용자 흐름이나 제품 정책이 바뀌면 `spec.md`뿐 아니라 관련 MVP·요구사항·기능 명세·user flow의 영향도 확인한다.
- DB 스키마, API 요청·응답, enum, 인증·권한, 외부 연동 정책의 변경은 호환성, migration, 테스트 영향을 PR에 기록한다.
- 함수명, private 내부 클래스 구조, 외부 동작을 바꾸지 않는 내부 리팩터링은 상위 명세와 설계 문서를 불필요하게 수정하지 않는다.
- 구현과 문서가 충돌한 상태를 발견하면 어느 쪽이 최신이라고 추측하지 말고 Issue 또는 PR에서 차이를 명시해 결정받는다.

## 9. 구현·검증과 AI 협업

- 승인된 명세를 만족하는 가장 작은 구현을 우선하고, 범위 밖 기능을 선제적으로 추가하지 않는다.
- 동작 변경에는 관련 테스트를 추가하거나 갱신한다. 테스트와 정적 분석은 변경 위험에 비례해 실행한다.
- 독립적인 코드·자료 조사, 사실 검증, 데이터 추출, 테스트·로그 분석, PR 분야별 review가 두 개 이상이고 병렬화가 시간 또는 검증 품질에 도움이 되면 subagent를 활용할 수 있다.
- 같은 파일을 수정하는 작업은 여러 agent에 병렬로 맡기지 않는다. 결과는 주 agent가 검증하고 하나의 결론으로 통합한다.
- 두 작업이 같은 파일, API 또는 DB 계약을 건드리면 구현 전에 담당 범위와 소유권을 조율한다.
- 중요한 설계 결정과 trade-off는 관련 `plan.md` 또는 적절한 `docs/decisions/` 문서에 기록한다.
