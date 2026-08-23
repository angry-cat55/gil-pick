---
name: "UI-UX-PRO-MAX"
description: "길픽 Android 화면의 UI/UX 설계 계약(UI-SPEC)을 작성하거나, 이미 구현된 화면을 6대 품질 축으로 감사해 UI-REVIEW를 만드는 종합 스킬. 대상 화면·기능명과 모드(design 또는 review)를 지정해 사용한다."
argument-hint: "<feature 또는 화면 이름> [design|review]"
compatibility: "Kotlin/Jetpack Compose 기반 Android 화면과 Naver Map SDK 연동, GitHub Spec Kit feature 구조(specs/<feature>/)를 전제로 한다. 해당 feature 산출물이 없으면 docs/design/ui/ 아래 독립 문서로 출력한다."
metadata:
  author: "길픽 팀 자체 제작"
  source: "custom (project-specific)"
user-invocable: true
disable-model-invocation: false
---

## 목적

길픽 Android 화면 작업을 위한 두 가지 모드를 제공하는 종합 UI/UX 스킬이다.

- **DESIGN 모드**: 화면을 구현하기 전에 디자인 토큰, 레이아웃, 상태, 접근성 요구사항을 `ui-spec.md` 계약으로 명세한다.
- **REVIEW 모드**: 이미 구현된 화면을 6대 품질 축으로 감사해 `ui-review.md`를 산출한다.

이 스킬은 Spec Kit 워크플로(`speckit-plan`, `speckit-implement`)를 대체하지 않는다. `spec.md`·`plan.md`·`tasks.md`가 다루지 않는 시각·상호작용·접근성 세부사항을 보완하는 역할이다.

## 언제 사용하나

- **DESIGN**: `speckit-plan` 완료 후, 해당 feature의 화면 구현에 착수하기 전.
- **REVIEW**: 화면 구현이 끝나고 PR을 올리기 전, 또는 `verify` 단계에서 UI/UX 관점 점검이 필요할 때.

## User Input

```text
$ARGUMENTS
```

`$ARGUMENTS`에서 대상 화면/기능명과 모드(`design` 또는 `review`)를 추출한다. 둘 중 하나라도 명확하지 않으면 임의로 정하지 말고 사용자에게 확인한다.

## 실행 전 공통 확인 사항

1. `AGENTS.md`와 `.specify/memory/constitution.md`를 읽고 원칙을 확인한다. 특히 다음 원칙이 UI/UX 판단에 직접 관련된다.
   - **I. 사용자 통제와 안전한 fallback**: 위치 권한·정확도 부족, 외부 데이터 실패에도 조회·수동 진행 기능은 차단하면 안 되고, 자동 상태 변경에는 확인·취소·되돌리기 수단이 있어야 한다.
   - **III. 상태 변경의 일관성·멱등성·추적 가능성**: 낙관적 UI 업데이트를 쓴다면 서버 확정 실패 시 롤백 방법을 명세해야 한다.
   - **IV. 외부 의존성 실패 격리**: 날씨·혼잡도·평점 등 선택적 데이터 실패는 해당 요소만 격리하고 나머지 화면은 정상 동작해야 한다.
2. 대상 feature의 `specs/<feature>/spec.md`, `plan.md`, `tasks.md` 존재 여부를 확인한다. 있으면 반드시 먼저 읽고 범위·용어·완료 조건을 그 문서 기준으로 맞춘다. 없으면 어떤 화면/기능인지, 관련 feature 산출물이 정말 없는지 사용자에게 먼저 확인하고 임의로 요구사항을 만들지 않는다.
3. 관련 배경 문서를 필요한 범위만 읽는다: `docs/planning/mvp.md`(MVP 범위), `docs/planning/user-flow.md`, `docs/planning/functional-spec.md`, `docs/planning/requirements.md`, `docs/design/api-spec.md`, `docs/design/er-schema.md`, `docs/decisions/tech-stack.md`.
4. 같은 화면에 대한 기존 `ui-spec.md`/`ui-review.md`가 있으면 먼저 로드해 중복 작성 대신 갱신·비교 대상으로 삼는다.

## DESIGN 모드 워크플로

1. **범위 확정**: 대상 feature의 `spec.md`에서 관련 user story와 기능 요구사항(FR)을 추출해 화면 단위로 정리한다. spec에 없는 화면이나 기능을 새로 만들지 않는다.
2. **디자인 토큰 정의**: color, typography scale, spacing(4dp/8dp 기준 grid), corner radius, elevation을 정의한다.
   - `docs/design/` 아래 이미 정의된 토큰 문서가 있으면 그것을 우선 따른다.
   - 없으면 Material Design 3 기본값을 출발점으로 삼되, 임의로 확정하지 말고 팀이 정할 항목은 `NEEDS CLARIFICATION`으로 표시한다(예: 다크 모드 지원 여부, 브랜드 컬러 hex 값).
3. **레이아웃과 반응형**: 소형 폰부터 태블릿까지 화면 밀도별 대응, 세로/가로 모드 처리 여부, 긴 텍스트(장소명·여행명 등 2~30자 제한 반영)에서의 줄바꿈·말줄임 규칙을 명세한다.
4. **화면 상태 매트릭스**: 최소 loading / empty / error / success / offline(또는 권한 거부) 상태를 모두 정의한다. error는 원인별(네트워크, 외부 API 실패, 권한 거부)로 구분하고, 부분 실패 시 어떤 요소만 숨겨지고 어떤 요소는 유지되는지 constitution IV 원칙에 맞춰 명세한다.
5. **길픽 도메인 특화 규칙** — 해당 화면과 관련될 때만 포함한다:
   - **지도 화면(Naver Map SDK)**: 마커·경로선 스타일, 현재 위치 표시, 지도 로드 실패 시 fallback UI.
   - **여행 진행 상태**: DWELL/EXIT 지오펜스 이벤트에 따른 도착·출발 후보 카드, 자동 확정까지 남은 시간 카운트다운 표시, `아직이에요`/`다음 장소로 출발`/`아직 머무는 중` 버튼과 각 동작의 화면 반응.
   - **되돌리기(undo) 어포던스**: 자동 도착·출발 확정 후 5분, 대체 장소 변경 후 30초 되돌리기 창구의 타이머 UI와 만료 시 상태 전환.
   - **변수 감지·대체 장소 제안**: 알림/카드에 점수 근거(거리·평점·혼잡·날씨)를 어떻게 노출할지, 후보 선택 후 경로 미리보기 확인 단계.
   - **권한·정확도 부족 fallback**: 위치 권한 거부 또는 정확도 부족 시 수동 도착·출발 진행 UI로의 전환 경로(constitution I 원칙 — 핵심 진행 기능 차단 금지).
6. **접근성**: 최소 터치 타깃 48dp, TalkBack용 콘텐츠 설명, 명도 대비 WCAG AA(텍스트 4.5:1) 이상, 시스템 폰트 확대에 대한 대응을 명세한다.
7. **인터랙션**: 전환 애니메이션 유무, 로딩 인디케이터 등장 지연 기준(예: 300ms 이상 걸릴 때만 표시), 낙관적 UI 업데이트 사용 여부와 실패 시 롤백 방법(constitution III 원칙)을 명세한다.
8. **산출물 작성**: 아래 "산출물 위치와 형식"에 따라 `ui-spec.md`를 작성한다. 설명 문장은 한글로, 컴포넌트명·색상 토큰명·API 필드명 등 기술 식별자는 영어를 유지한다.
9. **모호점 처리**: 팀 확정이 필요한 항목은 값을 지어내지 말고 문서 상단에 `## NEEDS CLARIFICATION` 목록으로 모아 보고한다.
10. **충돌 확인**: 작성 중 `spec.md`나 `docs/design/`의 기존 내용과 충돌이 발견되면 문서를 임의로 고치지 말고 충돌 내용을 보고해 팀 결정을 받는다.

## REVIEW 모드 워크플로

1. **구현 위치 확인**: 대상 화면에 해당하는 Compose 함수 또는 layout XML 파일을 찾는다.
2. **기준 문서 로드**: 대응하는 `ui-spec.md`가 있으면 계약 대비 감사하고 드리프트를 우선 확인한다. 없으면 Material Design 3와 이 문서의 "길픽 도메인 특화 규칙"을 암묵적 기준으로 사용하고, 그 사실을 보고서에 명시한다.
3. **6대 품질 축 감사**: 각 항목에 `PASS` / `FLAG` / `BLOCK`을 부여하고 근거를 `file:line`으로 남긴다.
   1. **비주얼 일관성** — 디자인 토큰(색상·타이포·여백·코너 radius) 하드코딩 여부, 기존 공용 컴포넌트 재사용 여부.
   2. **레이아웃과 반응형** — 다양한 화면 크기·밀도, 화면 회전, 긴 텍스트(장소명·여행명) 대응.
   3. **상태 커버리지** — loading/empty/error/success/offline 상태가 실제로 모두 처리되는지, 선택적 데이터 실패가 해당 요소로 격리되는지(constitution IV).
   4. **접근성** — contentDescription, 터치 타깃 크기, 명도 대비, 포커스 순서.
   5. **성능·체감 성능** — 불필요한 recomposition, 무한 스크롤 목록의 페이지네이션 처리, 지도 렌더링 비용.
   6. **도메인 정확성** — 지오펜스 카운트다운·되돌리기 타이머가 명세된 시간(5분/30초)과 일치하는지, 자동 확정과 수동 fallback 버튼이 constitution I 원칙을 지키는지, 알림 카드가 명세된 재알림 횟수·간격(10분 간격, 장소당 최대 2회)을 넘지 않는지.
4. **판정 기준**:
   - `BLOCK`: 명세 위반, 심각한 접근성 결함, 되돌리기·수동 fallback 같은 안전장치 누락처럼 배포 전 반드시 고쳐야 하는 항목.
   - `FLAG`: 개선을 권장하지만 배포를 막지는 않는 항목.
   - `PASS`: 기준을 충족하는 항목.
5. **산출물 작성**: 아래 "산출물 위치와 형식"에 따라 `ui-review.md`를 작성한다. 최상단에 `BLOCK` 항목 수와 한 줄 요약을 둔다.
6. 구현 코드는 사용자가 명시적으로 요청한 경우에만 수정한다. 기본적으로는 발견 사항만 보고한다. 다른 Spec Kit 산출물과 충돌하는 발견 사항은 팀 결정이 필요한 항목으로 별도 표기한다.

## 산출물 위치와 형식

- 대상 feature 디렉터리(`specs/<feature>/`)가 있으면 그 안에 `ui-spec.md`, `ui-review.md`로 저장한다.
- feature 디렉터리가 없으면 `docs/design/ui/<screen-slug>-ui-spec.md`, `docs/design/ui/<screen-slug>-ui-review.md`로 저장한다(`docs/design/ui/` 디렉터리가 없으면 새로 만든다).
- `ui-spec.md` 기본 구조:

  ```markdown
  # UI-SPEC: [화면/기능명]

  **Feature**: [spec 경로 또는 없음] | **작성일**: [DATE]

  ## 화면 개요
  ## 디자인 토큰
  ## 레이아웃과 반응형
  ## 화면 상태 매트릭스 (loading/empty/error/success/offline)
  ## 도메인 특화 규칙 (해당 시)
  ## 접근성 요구사항
  ## 인터랙션과 애니메이션
  ## NEEDS CLARIFICATION
  ```

- `ui-review.md` 기본 구조:

  ```markdown
  # UI-REVIEW: [화면/기능명]

  **대상 구현**: [file 경로] | **기준 문서**: [ui-spec 경로 또는 "암묵적 기준(Material Design 3 + 도메인 규칙)"] | **작성일**: [DATE]

  ## 요약 (BLOCK 개수, 한 줄 평가)

  ## 1. 비주얼 일관성
  ## 2. 레이아웃과 반응형
  ## 3. 상태 커버리지
  ## 4. 접근성
  ## 5. 성능·체감 성능
  ## 6. 도메인 정확성

  각 절 안에서 `- [BLOCK|FLAG|PASS] file:line — 설명` 형식으로 작성한다.

  ## 팀 결정이 필요한 항목
  ```

## 제약 사항

- 문서 설명 문장은 한글로 작성하고, 컴포넌트명·색상 토큰명·API 필드명 등 영어가 자연스러운 기술 식별자는 원문을 유지한다(`AGENTS.md` 2절).
- `docs/planning/mvp.md`의 MVP 범위 밖 화면·기능을 새로 만들지 않는다. 범위를 벗어난 요구가 발견되면 만들지 말고 보고한다.
- `.specify/`, `.claude/skills/speckit-*`, `.agents/skills/speckit-*` 등 Spec Kit이 생성·관리하는 파일은 이 스킬의 작업 범위에서 수정하지 않는다.
- 불확실한 디자인 값이나 정책은 추측해 확정하지 말고 `NEEDS CLARIFICATION` 또는 "팀 결정이 필요한 항목"으로 표시한다.
- 이 스킬의 산출물은 `spec.md`·`plan.md`·`tasks.md`를 대체하지 않는다. 기능 요구사항 자체가 바뀌어야 한다면 해당 feature 문서와 `speckit-clarify`를 통해 처리하도록 안내한다.

## Report

작업 완료 시 다음을 보고한다:
- 실행한 모드(DESIGN/REVIEW)와 대상 화면/기능
- 산출물 전체 경로
- DESIGN 모드: `NEEDS CLARIFICATION` 항목 수와 목록
- REVIEW 모드: `BLOCK`/`FLAG` 개수와 가장 중요한 BLOCK 항목 요약
