<!--
Sync Impact Report
- Version change: unratified template → 1.0.0
- Added principles:
  - I. 사용자 통제와 안전한 fallback
  - II. 계약 우선 SDD와 문서 동기화
  - III. 상태 변경의 일관성·멱등성·추적 가능성
  - IV. 외부 의존성 실패 격리
  - V. 보안·소유권·최소 데이터
- Added sections:
  - 추가 제약 및 개발 기준
  - SDD 워크플로와 품질 게이트
- Modified principles: 해당 없음(최초 제정)
- Removed sections: 없음
- Follow-up TODOs: 없음
-->

# 길픽 프로젝트 Constitution

## Core Principles

### I. 사용자 통제와 안전한 fallback

- 위치 권한, 위치 정확도 또는 외부 데이터 부족으로 자동화가 불가능해도 여행 조회·일정 확인과
  수동 도착·출발·상태 수정 같은 핵심 진행 기능을 차단해서는 안 된다.
- 자동 도착·출발 및 일정 변경처럼 사용자 상태를 바꾸는 기능에는 명세에 정의된 확인,
  취소 또는 되돌리기 수단이 반드시 있어야 한다.
- 자동 판정은 서버 검증을 통과한 입력만 사용하며, 위치 이벤트만으로 도메인 상태를 즉시
  확정해서는 안 된다.

이 원칙은 불완전한 위치·외부 데이터 환경에서도 사용자가 여행을 계속 통제할 수 있게 한다.

### II. 계약 우선 SDD와 문서 동기화

- Feature 구현은 승인된 MVP 범위와 해당 feature의 `spec.md`, `plan.md`, `tasks.md`를
  기준으로 진행해야 한다.
- API 계약, DB 구조, 사용자 동작 또는 정책이 기존 명세와 충돌하면 코드를 임의로 변경하지
  말고 변경이 필요한 문서와 영향 범위를 먼저 식별해야 한다.
- 외부 계약 변경은 해당 feature 산출물과 관련 API 명세, ERD, 요구사항, user flow를 같은
  변경 범위에서 동기화해야 한다.
- 외부 동작에 영향이 없는 함수명 변경이나 내부 구조 refactoring은 상위 문서를 불필요하게
  수정하지 않는다.

명세와 구현의 불일치를 조기에 드러내고, 팀과 AI 도구가 동일한 계약을 기준으로 작업하기
위한 원칙이다.

### III. 상태 변경의 일관성·멱등성·추적 가능성

- 생성·승인 요청과 feature 명세에서 멱등 대상으로 분류한 상태 변경 요청은 멱등하게
  처리해야 한다.
- 일정과 진행 상태 수정은 version 또는 동등한 동시성 제어로 충돌을 감지해야 한다.
- 여러 entity에 걸친 도착·출발, 대체 장소 승인·되돌리기는 하나의 transaction으로
  처리하여 부분 성공 상태를 허용하지 않는다.
- feature plan에서 감사 대상으로 정의한 상태 변경에는 변경 전후 값, 처리 출처, 서버
  처리 시각과 원인이 추적 가능하게 기록되어야 한다.
- 시간과 여행 상태 판정은 명세된 timezone과 서버 시각을 기준으로 한다.

여행 진행은 여러 자동·수동 이벤트가 겹칠 수 있으므로 재시도, 동시 요청, 장애 상황에서도
중복 부수효과를 방지하고 도메인 불변식을 유지해야 한다.

### IV. 외부 의존성 실패 격리

- 외부 API의 timeout, 재시도, 최종 실패 처리는 명세된 정책을 따라야 한다.
- 날씨, 혼잡도, 평점처럼 선택적 데이터의 실패는 해당 변수에만 격리하고 사용 가능한 나머지
  데이터로 기능을 계속 제공해야 한다.
- 경로 계산 실패처럼 핵심 결과를 제공할 수 없는 경우에도 이미 입력한 일정 데이터는
  보존하고, 실패 상태와 사용자가 취할 수 있는 다음 행동을 명확히 제공해야 한다.
- 실패를 성공처럼 숨기거나 확인되지 않은 값을 임의로 생성해서는 안 된다.

길픽은 여러 외부 제공자에 의존하므로 한 제공자의 장애가 전체 서비스나 사용자 데이터
손실로 확산되지 않아야 한다.

### V. 보안·소유권·최소 데이터

- 모든 보호 API는 인증된 사용자와 대상 여행·일정의 소유권을 검증해야 한다.
- token, 위치, 알림 정보는 기능 수행에 필요한 최소 범위로 저장하고 전송해야 한다.
- Refresh Token 원문과 secret을 저장소 또는 log에 남겨서는 안 되며, client token은
  플랫폼의 안전한 저장소를 사용해야 한다.
- push payload에는 전체 domain object나 민감정보 대신 서버 재조회에 필요한 최소
  식별자만 포함해야 한다.
- 개인 데이터와 상태 변경 log에는 목적에 맞는 접근 제어를 적용한다. 수집 전에 관련
  feature plan 또는 보안 문서에서 보존·삭제 정책을 정의하고 그 범위를 적용해야 한다.

인증정보와 위치 데이터는 침해 시 영향이 크므로 편의보다 소유권 검증과 데이터 최소화를
우선한다.

## 추가 제약 및 개발 기준

### MVP 범위와 기술 기준

- 새로운 Feature는 `docs/planning/mvp.md`의 목표와 성공 조건에 직접 기여해야 한다.
- MVP 제외 항목이나 새로운 제품 범위는 팀 합의와 관련 문서 변경 없이 추가할 수 없다.
- timeout, 반경, token 유효기간, 점수 가중치, 외부 제공자 같은 변경 가능한 세부값은
  constitution에 고정하지 않고 feature 명세와 설계 문서에서 관리한다.
- 백엔드 기본 담당은 `jh`, `ts`이고 프론트엔드 Android 기본 담당은 `jy`, `hs`다.
  Issue에서 합의하면 교차 영역을 담당할 수 있지만 영향받는 영역 담당자의 review를 받는다.
- API, DB, 인증, 공통 DTO처럼 양쪽 계약에 영향을 주는 변경은 백엔드와 프론트엔드
  담당자가 함께 영향 범위를 확인해야 한다.

### 코드 주석과 문서화 스타일

- 프로젝트 설명 문장과 팀이 작성하는 문서화 주석은 한글을 기본으로 하되, 코드 식별자,
  API 이름, enum, type 등 영어가 자연스러운 기술 항목은 원문을 유지한다.
- 백엔드 Python은 Google-style docstring을 사용하고, 프론트엔드 Kotlin은 KDoc을
  사용한다. 한 파일이나 module 안에서 서로 다른 형식을 혼용하지 않는다.
- public·protected 함수, API endpoint, service·repository의 공개 함수와 도메인 상태를
  변경하는 핵심 private 함수에는 문서화 주석이 반드시 있어야 한다.
- 함수 문서화에는 다음 내용 중 적용 가능한 항목을 포함한다.
  1. 함수가 수행하는 기능과 중요한 business rule
  2. 각 입력 parameter의 의미, 단위, 허용 범위와 null 가능성
  3. 반환 데이터의 의미, 형태와 실패·빈 결과 조건
  4. 발생 가능한 주요 exception 또는 외부로 노출되는 오류
  5. DB 변경, network 호출, 상태 전이 같은 부수효과와 transaction 경계
- Python docstring은 요약문 뒤에 필요한 `Args`, `Returns`, `Raises`, `Notes` section을
  사용한다. 반환값이 없는 함수는 오해 가능성이 없으면 `Returns`를 생략할 수 있다.
- Kotlin KDoc은 요약문과 함께 필요한 `@param`, `@return`, `@throws` tag를 사용한다.
  `Unit` 반환이 명확한 함수는 `@return`을 생략할 수 있다.
- public class, service, repository, domain model에는 책임, 생명주기 또는 지켜야 할
  invariant가 자명하지 않은 경우 class-level 문서화를 작성한다.
- inline comment는 코드가 무엇을 하는지 반복하지 않고, 해당 구현을 선택한 이유,
  business constraint, 외부 API의 예외 사항처럼 코드만으로 드러나지 않는 내용을 설명한다.
- 이름과 type만으로 동작이 명확한 단순 private helper, getter, setter, data mapping에는
  의미 없는 반복 주석을 강제하지 않는다.
- interface나 상위 class의 문서를 그대로 따르는 구현은 동작·제약이 달라질 때만 별도
  문서화를 추가한다.
- 오래된 주석, 주석 처리된 dead code, 코드와 모순되는 설명은 허용하지 않는다.
- 미완료 작업 주석은 `TODO(#Issue번호): 설명` 형식으로 작성하고 추적 가능한 GitHub
  Issue가 없으면 commit하지 않는다.

### 테스트와 관찰 가능성

- 정상 흐름뿐 아니라 권한 거부, 중복 요청, 동시 수정, 외부 연동 실패, 부분 실패와
  수동 fallback을 완료 조건과 test에 포함해야 한다.
- API·DB 계약 변경에는 contract 또는 integration test와 migration 영향을 확인해야 한다.
- 실행하지 않은 test를 통과했다고 기록해서는 안 되며, 미실행 항목과 이유를 PR에 남긴다.
- 운영 문제를 재현할 수 있도록 request ID, 오류 code, 주요 상태 전이 식별자를 log에
  남기되 token, 정밀 위치 등 민감정보는 기록하지 않는다.

## SDD 워크플로와 품질 게이트

- 새 작업은 최신 `main`에서 Issue별 branch를 만들어 시작하며 `main`은 항상 통합 가능한
  상태를 유지한다.
- 전체 순서는 MVP Feature 목록, constitution, feature별 `spec → clarify → plan → tasks`,
  GitHub Issue 분리, 담당자 지정, 구현·test, 검증, PR·merge를 따른다.
- 현재 Feature가 구현 단계에 들어간 뒤 다음 Feature 하나만 상세 설계를 준비할 수 있다.
  그 이후 Feature는 선행 구현의 계약과 정책이 확정될 때까지 상세화하지 않는다.
- `tasks.md`는 프론트엔드·백엔드·통합 작업을 구분하고, 담당자, 선행 관계, 정확한 대상
  경로와 검증 방법을 포함해야 한다. 병렬 가능한 task에만 `[P]`를 표시한다.
- 하나의 Issue는 한 사람이 독립적으로 구현·test할 수 있고 다른 팀원이 독립적으로
  review할 수 있는 단위여야 한다.
- 작업 브랜치에서는 하나의 task 또는 독립적으로 검증 가능한 하위 작업이 완료될 때마다
  관련 검증 후 commit한다. 서로 무관한 변경을 하나의 commit에 쌓아두지 않는다.
- commit 메시지는 `<type>: <한글 요약>` 형식을 사용한다. `feat`, `fix`, `docs`,
  `refactor`, `test`, `chore` 등 변경 성격을 나타내는 type은 영어로 쓰고, 요약은
  변경 결과가 드러나는 짧은 한글로 작성한다.
- 완료 전에는 Issue 완료 조건, 관련 test·정적 분석, 문서 동기화, 명세와 구현의 일치
  여부를 검증해야 한다.
- 모든 완료 조건을 충족한 PR만 `Closes #번호`를 사용하며, 일부 해결은 `Refs #번호`로
  연결한다.
- API·DB·인증 등 양쪽 계약 변경 PR은 백엔드 담당자와 프론트엔드 담당자가 각각 영향을
  확인한 기록이 있어야 한다.
- PR은 팀 review를 거쳐 squash merge하는 것을 기본으로 한다.

## Governance

- 이 constitution은 프로젝트의 최상위 개발 원칙이다. `AGENTS.md`, feature 산출물,
  Issue, 구현이 충돌하면 constitution을 우선하고 차이를 문서화하여 해결한다.
- constitution 개정은 변경 이유, 영향받는 원칙·문서·진행 중 Feature, 적용 또는 migration
  계획을 포함한 PR로 제안해야 한다.
- 개정 PR은 백엔드와 프론트엔드에서 각각 한 명 이상의 approval을 받고, 해결되지 않은
  blocking review가 없어야 merge할 수 있다. 이 조건을 constitution 개정에 대한 팀
  합의로 본다.
- 버전은 Semantic Versioning을 따른다.
  - MAJOR: 기존 원칙 제거 또는 호환되지 않는 재정의
  - MINOR: 새로운 원칙이나 필수 section 추가, 실질적인 범위 확대
  - PATCH: 의미를 바꾸지 않는 명확화, 예시, 오탈자 수정
- 모든 feature spec과 PR review는 관련 constitution 원칙 준수 여부를 확인해야 한다.
- 예외가 필요한 경우 이유, 범위, 위험, 종료 조건을 Issue와 PR에 기록하고 팀 승인을
  받아야 하며, 승인되지 않은 예외는 허용하지 않는다.
- runtime 협업 절차와 AI 도구별 세부 지침은 `AGENTS.md`를 따르되 constitution과
  충돌하는 규칙은 적용하지 않는다.

**Version**: 1.0.0 | **Ratified**: 2026-08-22 | **Last Amended**: 2026-08-22
