# Implementation Plan: 사용자 설정

**Branch**: `012-user-settings` | **Date**: 2026-09-10 | **Spec**: `specs/012-user-settings/spec.md`

**Input**: Feature specification from `/specs/012-user-settings/spec.md`

## Summary

인증된 사용자가 장소 변경 제안 알림 전체 ON/OFF 한 개를 조회·변경하고, 계정·앱 정보를 확인하며, 개인정보처리방침과 이용약관을 Custom Tabs로 열고, F001의 현재 기기 로그아웃을 실행할 수 있는 설정 화면을 제공한다. Backend는 기존 `users.replacement_suggestion_enabled`를 직접 조회·원자적으로 갱신하는 PREF API 두 개만 추가한다. Android는 F001 인증 경계와 공통 API 결과 형식을 재사용하고 `settings` 패키지에 화면 상태와 통신을 최소 구성으로 둔다.

## Technical Context

**Language/Version**: Python 3.13, Kotlin/JVM 17

**Primary Dependencies**: FastAPI 0.141.1, SQLAlchemy 2.0.52 async, Pydantic 2.x, PostgreSQL; Android Compose BOM 2026.08.00, Material 3, Navigation Compose 2.10.0, Retrofit/OkHttp, Kotlin Serialization, Coil 3.6.0, AndroidX Browser 1.10.0

**Storage**: PostgreSQL `users.replacement_suggestion_enabled`(기본 `true`); Android에는 별도 설정 사본을 영구 저장하지 않고 서버 응답과 화면 수명 내 마지막 성공값만 유지

**Testing**: pytest 8.4.2/pytest-asyncio 1.2.0 기반 unit·integration·contract 검증; Android JVM unit test, Compose UI test, 실제 기기 또는 동등한 emulator와 screenshot 검증

**Target Platform**: Linux ASGI API 서버, Android API 26+ (`compileSdk 37`, `targetSdk 36`)

**Project Type**: Android mobile app + FastAPI web service

**Performance Goals**: 대표 Android 환경의 수동 검증 5회에서 설정 변경을 30초 안에 완료하고, 각 정책 문서 열기 5회를 3초 안에 표시; 설정 API는 단일 행 조회·갱신으로 처리

**Constraints**: 인증된 현재 사용자만 접근; 마지막으로 서버가 성공 처리한 값이 최종값; 실패한 낙관적 UI 값을 성공 상태로 남기지 않음; Token·정책 URL·불필요한 개인정보·설정값을 log에 남기지 않음; 승인된 HTTPS 정책 URL만 허용; 신규 사용자 설정·가중치·임계값·초기화 기능 금지

**Scale/Scope**: Backend endpoint 2개와 기존 사용자 컬럼 1개, Android 설정 화면 1개 및 최상위 진입점; 사용자별 단일 boolean 설정

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`, Figma Make `Design UI from Reference`의 `SettingsScreen`, 저장소 사본 `docs/design/figma-make/src/screens/SettingsScreen.tsx`. 구현 전에 Figma 원본과 저장소 사본을 FR-001의 단일 토글로 동기화하며, 동기화된 Figma를 모양의 정본으로 사용한다.

**Tokens & Components**: `com.gilpick.ui.theme`의 색상·타이포·간격·모서리 토큰과 기존 공통 button/loading/error 표현을 재사용한다. Material 3 `Switch`, scroll container, Coil profile image를 조합하되 화면 코드에 색상·간격 값을 직접 쓰지 않는다. Figma 동기화 결과에 없는 재사용 abstraction은 만들지 않는다.

**State & Interaction**: `SettingsUiState`는 계정·앱·정책 정보와 `preferencePhase`(`Loading`, `Content(lastConfirmedValue, isSaving)`, `Error(lastConfirmedValue?, retryable)`)를 가진다. 초기 조회가 1초를 넘을 때만 설정 영역에 loading을 드러낸다. 토글 요청은 한 번에 하나만 보내고 처리 중 추가 입력은 마지막 희망값 하나로 합쳐 순서대로 반영한다. 성공 응답값만 확정값으로 교체하고 실패하면 마지막 성공값으로 복원한다. 정책 문서와 로그아웃은 설정 저장과 독립적으로 동작한다. 인증된 사용자에게 값이 항상 있으므로 empty 상태는 두지 않는다.

**Navigation**: `SettingsRoute`를 최상위 설정 진입점으로 추가하고 기존 여행·진행 destination을 재사용한다. F012는 설정 destination과 선택 상태만 소유하며, 진행 중 여행을 고르는 정책이나 `ActiveTravelRoute`의 식별자 결정은 새로 구현하지 않는다. 최상위 진행 탭 연결이 필요하면 사용자별 여행 기간 중복 금지 구현 결과에 의존하며, 그 전에는 기존 여행 목록·상세에서 제공하는 진행 진입 흐름을 유지한다.

**Account Display**: MVP 인증 provider는 카카오 하나뿐이므로 F001의 인증된 session 존재를 카카오 연동 상태로 표시한다. `SessionEnvelope`에 provider를 추가하거나 별도 profile API를 만들지 않는다.

**Accessibility & Adaptive Layout**: 모든 터치 대상 48×48dp 이상, 대상 간 8dp 이상을 보장한다. 토글과 오류는 문구·상태 표현을 함께 제공하고 아이콘 전용 control에는 식별 가능한 설명을 둔다. `verticalScroll`, system bar inset, 하단 탐색 inset을 적용해 360dp와 phone/tablet 세로·가로 및 시스템 최대 글자 크기에서 가로 스크롤·잘림·겹침이 없도록 한다.

**Visual Validation**: 동기화된 Figma와 설정 `content` screenshot을 비교하고, 설정 영역의 지연 loading·조회 오류·변경 성공·변경 실패 복원·빠른 연속 선택, 정책 문서 진입·복귀·앱이 감지 가능한 URL 검증 및 Custom Tab 실행 실패, 로그아웃 이동을 emulator 또는 실제 기기에서 확인한다. Custom Tab 실행 뒤의 network·HTTP·문서 로딩 오류는 브라우저 표시 범위다. 360dp, 대표 phone/tablet의 세로·가로, 시스템 최대 글자 크기를 포함한다.

## Constitution Check

*GATE: Phase 0 전 및 Phase 1 설계 후 재검토 완료.*

| 원칙 | 판정 | 설계 근거 |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | PASS | 알림 설정을 사용자가 직접 변경하며 실패 시 마지막 성공값과 재시도를 제공한다. 설정 장애가 정책·로그아웃을 차단하지 않는다. |
| II. 계약 우선 SDD와 문서 동기화 | PASS | 기존 PREF 계약과 ERD를 재사용하고 feature OpenAPI 계약을 작성한다. Figma 충돌은 구현 전에 원본·사본을 동기화한다. |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | PASS | 단일 SQL 갱신과 응답값 확인으로 부분 성공을 없앤다. 요청 ID와 결과만 기록하며 마지막 성공 처리값 정책을 명시한다. boolean 절대값 PATCH는 같은 값 재시도에도 동일 결과다. |
| IV. 외부 의존성 실패 격리 | PASS | 정책 URL 실패는 설정 화면과 session을 유지하며, 설정 API 실패도 독립 행동에 전파하지 않는다. |
| V. 보안·소유권·최소 데이터 | PASS | 인증 principal의 `user_id`만 사용하고 경로로 사용자 ID를 받지 않는다. URL·Token·프로필·설정값은 log에서 제외한다. |

Phase 1 재검토 결과 위반과 승인 필요 예외가 없으며 `NEEDS CLARIFICATION`도 없다. 단, 승인된 정책 HTTPS URL과 Figma 동기화는 구현 전 충족해야 하는 외부 의존성이다.

## Project Structure

### Documentation (this feature)

```text
specs/012-user-settings/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── preferences.openapi.yaml
└── tasks.md                  # $speckit-tasks 단계에서 생성
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── api/v1/preferences.py
│   ├── schemas/preferences.py
│   ├── services/preferences.py
│   └── main.py
└── tests/
    ├── unit/test_preferences_*.py
    └── integration/test_preferences_flow.py

android/app/src/
├── main/java/com/gilpick/
│   ├── MainActivity.kt
│   └── settings/
│       ├── SettingsApi.kt
│       ├── SettingsRepository.kt
│       ├── SettingsUiState.kt
│       ├── SettingsViewModel.kt
│       ├── SettingsScreen.kt
│       ├── SettingsNavigation.kt
│       └── PolicyDocumentLauncher.kt
├── test/java/com/gilpick/settings/
└── androidTest/java/com/gilpick/settings/

docs/design/figma-make/src/screens/SettingsScreen.tsx
```

**Structure Decision**: 기존 단일 Android app module과 단일 FastAPI app 구조를 유지한다. Backend는 현재 router/schema/service 경계를 따르며 단일 컬럼을 위해 repository나 새 table을 추가하지 않는다. Android도 feature package 하나만 추가하고 인증·공통 API·theme·Custom Tabs 의존성을 재사용한다.

## Complexity Tracking

Constitution 위반이 없어 추가 복잡성 정당화 항목은 없다.
