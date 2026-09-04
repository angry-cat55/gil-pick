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
- 프로젝트가 소유한 코드에서 새로 작성하거나 수정하는 주석은 팀원이 별도 번역 없이 이해할 수 있도록 한글로 작성한다. 코드 식별자, API 이름, 라이브러리명 등 영어 표기가 자연스러운 기술 용어는 영어를 유지한다.
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
- Issue를 생성하거나 변환할 때 작업 영역과 유형에 맞는 저장소 표준 label을 적용한다.
- 한 Issue는 한 사람이 독립적으로 구현하고 테스트할 수 있으며, 다른 팀원이 변경 범위와 완료 여부를 독립적으로 review할 수 있는 크기로 나눈다.
- `tasks.md`의 task는 구현 순서와 세부 완료 여부를 관리하는 단위이며 GitHub Issue와 1:1로 대응하지 않는다. GitHub Issue는 한 담당자가 하나의 브랜치와 PR에서 완료할 수 있는 응집된 구현·검증 묶음으로 생성한다.
- 같은 담당자와 영역에 속하고 하나의 기능 흐름을 함께 완성하며 선행 관계와 수정 파일 소유권이 양립하는 setup·구현·test task는 하나의 Issue로 묶을 수 있다. 담당자나 플랫폼이 다르거나 별도 review·배포가 필요하거나 서로 독립적으로 완료할 수 있는 작업은 분리한다.
- 그룹 Issue 본문에는 포함된 모든 `T###`를 checklist로 기록하고 각 task의 대상, 선행 관계와 검증 기준을 보존한다. `speckit-implement`를 사용할 때는 현재 Issue의 checklist에 포함된 task만 구현하고 완료 처리한다.
- Issue 전체를 시작할 수 없는 선행 관계는 `blocked by #번호`로 기록한다. Issue 안의 일부 task만 다른 Issue의 중간 결과를 기다리면 전체 Issue를 차단하지 말고 해당 task의 부분 선행 조건과 필요한 계약·산출물을 본문에 명시한다.
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

1. MVP Feature 목록에서 다음 기능 선택
2. 다음 Feature 담당자가 `speckit-specify`로 `spec.md` 작성
3. `speckit-clarify`로 불명확한 요구사항 확정
4. `speckit-plan`으로 기술 설계와 구현 방향 작성
5. 팀 메시지방에서 Frontend·Backend 구현 담당자 합의
6. `speckit-tasks`로 합의된 담당자가 포함된 `tasks.md` 생성
7. `speckit-analyze`로 `spec.md`·`plan.md`·`tasks.md` 간 누락·충돌·불일치 검증
   - 발견 사항을 검토해 해당 산출물을 수정하고 필요하면 `speckit-analyze`를 다시 수행한다.
   - 검증된 명세 산출물은 현재 작업 브랜치와 별도의 문서 브랜치에서 Issue 없이 PR로 제출한 뒤 `speckit-taskstoissues`를 수행한다.
8. `speckit-taskstoissues`로 task를 GitHub Issue로 변환
9. `tasks.md`의 담당자·영역·선행 관계가 실제 GitHub Issue의 assignee·label·dependency에 반영되었는지 확인
10. 각 담당자가 Issue별 브랜치에서 해당 Issue에 연결된 task만 `speckit-implement` 또는 직접 구현·테스트
    - `speckit-implement`를 사용하더라도 현재 Issue 범위를 벗어난 task는 구현하지 않는다.
    - 구현 중 `spec.md`·`plan.md`·`tasks.md`가 변경되었다면 `speckit-analyze`를 다시 수행한다.
11. Issue별 PR 생성 및 팀원 review
12. review와 필수 검증이 완료된 PR을 개발자가 squash merge

- AI는 Feature 작업을 시작할 때 현재 단계, 완료된 선행 단계, 필요한 산출물과 다음 단계를 확인한다. 선행 단계나 담당자 합의가 누락되면 임의로 건너뛰지 않고 사용자에게 알린다.
- `speckit-plan` 완료 후 사용자에게 보내는 결과 메시지에는 구현에 필요한 Frontend·Backend 담당자가 각각 어떤 일을 맡아야 하는지 주요 작업, 소유할 계약·상태, 필수 검증을 요약한다. 이 정보는 별도 요구가 없는 한 `plan.md` 산출물에 추가하지 않는다. 특정 영역의 작업이 필요하지 않으면 역할을 억지로 만들지 말고 결과 메시지에서 제외 이유를 알린다.
- 위 완료 보고의 역할 설명은 “어떤 일을 맡을 사람이 필요한가”를 식별하는 것이며 팀원 배정이 아니다. AI는 합의 전에 이름이나 이니셜을 임의로 연결하지 않고 배정이 미정임을 함께 알린다.

### UI가 포함된 Feature 산출물

- 사용자 화면 또는 UI 요소가 포함된 Feature의 Owner는 `spec.md` 작성 전에 `docs/design/ui-guidelines.md`와 Figma Make `Design UI from Reference`(로컬 사본 `gilpick/figma-make/src/screens/*.tsx`)의 관련 화면·컴포넌트를 확인한다.
- `spec.md`에는 적용 가능한 `loading`·`empty`·`error`·`content` 상태, 상호작용, 접근성, adaptive 요구사항과 검증 가능한 완료 조건을 기록한다. 적용되지 않는 상태는 억지로 만들지 않고 그 이유를 명시한다.
- **화면이 어떻게 생겼는지는 `spec.md`에 글로 적지 않는다.** 모양의 정본은 pen이므로 해당 화면을 가리키기만 한다. `spec.md`가 화면에 대해 적는 것은 "사용자가 무엇을 할 수 있어야 하는가"이지 "어떻게 배치하는가"가 아니다.
- 새로운 시각·UX 판단이나 명세의 빈틈을 점검할 때는 `ui-ux-pro-max`를 사용한다. Compose 구현 제약, Material 3, adaptive layout, semantics 또는 UI test 가능성을 구체화할 필요가 있을 때는 `compose-expert`를 보조적으로 사용한다.
- UI가 포함된 Feature의 `plan.md`에는 승인된 `spec.md`의 UI 요구사항을 구현할 theme token·재사용 component·상태 모델과 접근성·실제 기기 또는 screenshot 검증 방법을 기록한다.
- 관련 Frontend task의 검증 기준에는 `spec.md`와 `plan.md`의 UI 완료 조건을 추적 가능하게 반영한다. 적용하지 않는 공통 상태나 검증 항목이 있으면 그 이유를 기록한다.
- **Figma Make `Design UI from Reference`(https://www.figma.com/make/H7SpIPF8iNYyxb5jPlo7xM)가 화면 모양의 정본이다(2026-09-04 팀 결정).** 화면 구성·배치·어떤 요소를 넣고 뺄지·색·글자·간격이 `spec.md`나 구현과 다르면 Figma에 맞추고, `spec.md`도 Figma 기준으로 고친다. `docs/design/gilpick-design-reference.pen`은 참고용이다.
- Figma보다 위에 있는 것은 `ui-guidelines.md` 9절 화면 상태, 10절 접근성 최저선(48dp 터치, 색 단독 의미 전달 금지), 정책상 필수 표시(Google attribution)뿐이다. 이는 모양을 바꾸지 않는 방식으로 지킨다. API에 없는 값은 지어내지 않고 `정보 없음`으로 두며 Backend 계약 추가를 요청한다. 상세 기준과 경계 판단은 `ui-guidelines.md` 12절을 따른다.
- skill의 제안은 참고 자료다. 저장소 기준으로 검증한 뒤 쓰고, 검증 결과를 PR에 기록한다.
- Figma에도 명세에도 없는 화면이나 기능을 임의로 범위에 추가하지 않는다.

- 새로운 기능을 임의로 추가하지 않고 합의된 MVP 범위 안에서 다음 Feature를 선정한다.
- 다음 Feature는 MVP Feature 목록, 완료·진행 중인 Feature, GitHub Issues, Feature 간 의존성을 확인한 뒤 선택한다.
- 다음 Feature 담당자는 `tasks` 생성 전에 팀원과 Frontend·Backend 담당자를 합의하고, plan 완료 보고에서 식별한 업무 범위에 합의된 팀원을 연결한 뒤 그 결과를 `tasks.md`와 GitHub Issue assignee에 반영한다. 합의되지 않은 담당자는 `미정`으로 기록하며 AI가 임의로 배정하지 않는다.
- 현재 Feature가 구현 단계에 들어가면 다른 담당자는 다음 Feature 하나의 `spec → clarify → plan → tasks`를 미리 준비할 수 있다.
- 구현보다 앞서 상세화하는 Feature는 최대 1개로 제한한다.
- 원칙적으로 `현재 Feature: 구현 중`, `다음 Feature: 상세 설계 준비 가능`, `그 이후 Feature: 상세 명세 미생성` 상태를 유지한다.
- 앞선 구현에서 API, ERD, 정책, 요구사항이 바뀌어 후속 명세가 낡는 일을 줄이기 위해 여러 후속 Feature를 한꺼번에 상세화하지 않는다.
- 하나의 Feature가 완전히 구현될 때까지 다음 Feature의 설계를 무조건 기다릴 필요는 없지만, 선행 Feature의 미확정 계약에 의존하는 내용은 확정된 것처럼 작성하지 않는다.
- 설치된 Spec Kit bundle workflow에는 포함되지 않은 `clarify`, 산출물 분석, GitHub Issue 변환도 각각 설치된 `speckit-clarify`, `speckit-analyze`, `speckit-taskstoissues`를 사용해 위 순서대로 수행한다.
- Feature 상태 변경은 전이를 일으킨 PR에 포함한다. 검증된 `tasks` 문서 PR은 `READY`, 첫 구현 Issue PR은 `IN_PROGRESS`, 전체 구현 완료 후 검증 PR은 `VERIFY`, 관련 PR의 `main` 병합 완료 후에는 `DONE`으로 갱신한다.

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

### 저장소 공용 skill 사용

`.agents/skills/`와 `.claude/skills/`에 vendor한 skill은 팀 공용이다. 각자 설치하지 않아도 되도록 저장소에 함께 commit했으므로, 해당 작업을 할 때 사용한다. 두 경로에 같은 skill이 있으면 내용을 함께 갱신한다.

| 작업 | skill |
|---|---|
| Spec Kit 산출물 작성·분석·Issue 변환 | `speckit-*` |
| Android Compose 화면 구현과 review | `compose-expert` |
| 색상·타이포·간격·컴포넌트·접근성 등 시각 설계 결정 | `ui-ux-pro-max` |
| API 설계와 계약 검토 | `api-design-principles` |
| 과잉설계 점검과 단순화 | `ponytail-*` |

- skill이 제안한 값과 구조는 권고이지 결정이 아니다. 이 저장소의 명세, `docs/design/ui-guidelines.md`, constitution과 충돌하면 저장소 기준이 우선한다.
- skill이 제안한 값을 그대로 채택하지 않는다. 저장소 기준으로 검증한 뒤 쓰고, 검증 결과와 조정한 값을 PR에 기록한다.
- skill을 실행하지 않고 판단했다면 그 사실과 이유를 PR에 남긴다.

## 10. 사용자 화면 작업 규칙

- Android 화면을 새로 만들거나 수정하기 전에 `docs/design/ui-guidelines.md`와 해당 feature의 화면 요구사항을 먼저 읽는다.
- Frontend Issue에서 화면을 새로 만들거나 시각·UX 결정을 수정하는 AI는 구현 전에 현재 Issue에 포함된 `tasks.md` task와 해당 feature의 `spec.md`·`plan.md`, `docs/design/ui-guidelines.md`, Figma Make 로컬 사본(`gilpick/figma-make`)의 관련 화면을 확인한다.
- 위 작업에서 AI는 `ui-ux-pro-max`를 시각·UX 결정과 완성도 검토에, `compose-expert`를 Jetpack Compose 구현과 review에 사용한다. 각 도구가 skill을 자동 발견하지 못하면 해당 도구용 경로의 `SKILL.md`를 직접 읽고 적용한다.
- 색상·간격·타이포 값을 화면 코드에 직접 쓰지 않는다. `com.gilpick.ui.theme`의 토큰에서 읽고, 필요한 값이 없으면 가이드라인에 토큰을 먼저 추가한다.
- 기능 동작만 구현한 임시 화면을 완료 상태로 간주하지 않는다. 임시 화면이면 task와 PR에 임시임을 명시하고 후속 작업을 남긴다.
- 화면 구현 task의 검증 기준에는 적용 가능한 `loading`·`empty`·`error`·`content` 상태, 접근성 기준, 실제 기기 또는 screenshot 확인을 포함한다. 적용되지 않는 상태나 검증 항목은 이유를 명시한다.
- screenshot 또는 실제 기기 확인에서 승인된 UI 기준을 충족하지 못하면 완료 처리하지 않고 수정·재검증한다.
- 승인된 디자인 기준과 구현이 충돌하면 임의로 해석하지 않고 차이를 Issue 또는 PR에 기록해 결정을 받는다.

## 11. Issue 완료 보고 규칙

- Issue 구현·검증·commit·push와 PR 생성을 마친 뒤, 터미널에 표시되는 최종 완료 응답을 아래 형식으로 작성한다.
- 확인한 사실과 추론을 구분하고, 실행하지 못한 검증이나 확인할 수 없는 관계는 명시한다.

### 1. 구현 결과

- 이번 Issue에서 실제로 구현한 기능
- 구현하지 않고 후속 Issue로 남긴 범위

### 2. 변경된 주요 파일

- 주요 파일과 각 파일의 역할
- 단순한 전체 파일 나열은 하지 않는다.

### 3. 설계 결정

#### 기존 명세에 따른 결정

- 구현에 적용한 주요 명세·정책

#### 구현 과정에서 새로 내린 결정

- 명세에 명시되지 않아 AI가 자체적으로 결정한 사항
- 각 결정의 이유를 간단히 설명
- 중요한 결정이 없다면 `없음`이라고 명시

### 4. 개발자가 이해해야 할 핵심

- 이 Issue를 담당하는 개발자가 반드시 이해해야 하는 개념만 설명
- 라이브러리 내부 구현 등 불필요하게 깊은 설명은 제외
- 중요도에 따라 3~5개 이내를 권장

### 5. 주요 처리 흐름

- 요청 → 처리 → DB·외부 API → 응답 등의 핵심 흐름
- 필요한 경우 간단한 텍스트 흐름도로 표현

### 6. 주의사항

- 보안, 데이터 정합성, 성능, 운영상 주의할 사항
- 해당 사항이 없다면 생략 가능

### 7. 검증 결과

- 수행한 핵심 테스트
- 전체 테스트 성공·실패 여부
- 실패하거나 검증하지 못한 항목

### 8. 후속 Issue와의 관계

- 이번 구현에 의존하는 후속 작업
- 아직 구현되지 않은 관련 기능

### 9. 팀 공유사항

- 다른 Backend 개발자가 알아야 할 변경사항
- Frontend·Android 개발자가 알아야 할 API 계약 또는 연동 변경사항
- 공유할 사항이 없다면 `없음`이라고 명시

---

## 개발자용 30초 요약

**① 뭘 만들었나?**

- 한두 문장

**② 어떻게 돌아가나?**

- 핵심 흐름만 설명

**③ 중요한 설계는?**

- 핵심 결정 1~3개

**④ 왜 이렇게 했나?**

- 주요 이유

**⑤ 다음엔 뭘 하나?**

- 후속 작업

**⑥ AI가 명세 밖에서 자체 결정한 중요한 사항이 있는가?**

- 있으면 결정과 이유
- 없으면 `없음`
