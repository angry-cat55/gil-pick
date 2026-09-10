# Tasks: 사용자 설정

**Input**: Design documents from `/specs/012-user-settings/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: `spec.md`의 독립 검증과 성공 기준을 구현 완료 조건으로 요구하므로 Backend unit·integration test, Android JVM·Compose UI test와 실제 기기 또는 동등 환경 검증을 포함한다.

**Organization**: 사용자 스토리별로 독립 구현·검증할 수 있게 구성한다. 합의된 담당자는 Frontend Android `hs`, Backend `jh`다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일을 다루고 미완료 선행 관계가 없어 병렬 실행 가능
- **[Story]**: `spec.md`의 사용자 스토리
- 모든 task에 영역, 담당, 선행 관계와 검증 방법을 기록

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 구현 정본과 환경 입력을 먼저 확정한다.

- [x] T001 Figma Make 원본과 저장소 사본에서 다중 알림 토글·감지 기준·초기화를 제거하고 단일 장소 변경 제안 알림 토글로 동기화 in docs/design/figma-make/src/screens/SettingsScreen.tsx
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: Figma 원본과 저장소 사본에 단일 토글만 존재하고 계정·앱·정책·로그아웃 항목의 순서와 문구가 일치하는지 screenshot 비교

- [ ] T002 [P] 개인정보처리방침·이용약관 URL의 Android BuildConfig 입력 경계를 구성 in android/app/build.gradle.kts
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: 실제 URL 없이도 US1을 build할 수 있고 두 값을 외부에서 주입할 수 있으며 secret이나 머신 종속 경로가 commit되지 않는지 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 사용자 스토리가 공통으로 사용할 설정 화면 경계와 API DTO를 만든다.

**⚠️ CRITICAL**: 이 단계가 끝나야 사용자 스토리 구현을 시작한다.

- [x] T003 [P] Android 설정 API DTO·오류 code·Retrofit interface의 계약 test 작성 in android/app/src/test/java/com/gilpick/settings/SettingsApiTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: `preferences.openapi.yaml`의 camelCase field, GET/PATCH path, 공통 envelope와 malformed 응답 test가 구현 전 실패

- [X] T004 [P] Backend 설정 request·response schema validation test 작성 in api/tests/unit/test_preferences_schema.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: required strict boolean과 extra field 거부, camelCase 직렬화 test가 구현 전 실패

- [X] T005 Backend 설정 request·response schema 구현 in api/app/schemas/preferences.py
  - 영역: BE
  - 담당: jh
  - 선행: T004
  - 검증: T004 통과 및 `specs/012-user-settings/contracts/preferences.openapi.yaml`과 field·required 조건 일치

- [x] T006 Android 설정 API DTO·Retrofit interface 구현 in android/app/src/main/java/com/gilpick/settings/SettingsApi.kt
  - 영역: FE
  - 담당: hs
  - 선행: T003
  - 검증: T003 통과 및 기존 공통 `SuccessEnvelope`·`AuthResult` 계약 재사용 확인

**Checkpoint**: Backend와 Android가 같은 PREF 계약을 공유한다.

---

## Phase 3: User Story 1 - 장소 변경 제안 알림을 통제한다 (Priority: P1) 🎯 MVP

**Goal**: 저장된 단일 알림 설정을 조회·변경하고 실패·연속 입력·다기기 변경에도 서버 정본과 일치시킨다.

**Independent Test**: 설정 ON/OFF 후 재진입과 다른 기기 재조회에서 마지막 성공값이 보이고, 실패 시 이전 성공값으로 복원되며, OFF 동안 장소 변경 제안만 생성되지 않는지 확인한다.

### Tests for User Story 1

- [X] T007 [P] [US1] 설정 조회·원자적 갱신 service unit test 작성 in api/tests/unit/test_preferences_service.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: 기본·저장값 조회, 동일값 재시도, `UPDATE ... RETURNING`, 겹친 요청의 마지막 성공값 test가 구현 전 실패

- [X] T008 [P] [US1] 인증·입력 오류·GET/PATCH 종단 API integration test 작성 in api/tests/integration/test_preferences_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: 200/400/401 공통 envelope, 다른 사용자 row 불변, 재조회·동시 변경 test가 구현 전 실패

- [x] T009 [P] [US1] 설정 repository와 빠른 연속 선택 ViewModel unit test 작성 in android/app/src/test/java/com/gilpick/settings/SettingsRepositoryTest.kt and android/app/src/test/java/com/gilpick/settings/SettingsViewModelTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: 초기 조회, 단일 in-flight와 마지막 희망값 합산, 성공 확정, 실패 rollback, 늦은 응답 무시 test가 구현 전 실패

- [X] T010 [US1] 설정 조회·갱신 service와 request ID 기반 최소 log 구현 in api/app/services/preferences.py
  - 영역: BE
  - 담당: jh
  - 선행: T007
  - 검증: T007 통과, 인증 user row만 조회·갱신하고 Token·user ID·설정값을 log에 남기지 않으며 public service 함수에 Constitution 형식의 Google-style docstring이 있는지 확인

- [X] T011 [US1] PREF router를 구현하고 API app에 등록 in api/app/api/v1/preferences.py and api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T008, T010
  - 검증: T008 통과, 생성 OpenAPI가 feature 계약의 path·status·schema와 일치하고 endpoint에 Constitution 형식의 Google-style docstring이 있는지 확인

- [X] T012 [US1] 알림 설정 OFF·재활성화·도착출발 알림 독립성 회귀 test 보강 in api/tests/unit/test_notification_service.py and api/tests/integration/test_notification_detection_hook.py
  - 영역: BE
  - 담당: jh
  - 선행: T011
  - 검증: OFF 뒤 새 장소 변경 제안 미생성, detection 계속 생성, 도착·출발 확인 계속 생성, 재활성화 뒤 누락분 미소급 test 통과

- [x] T013 [US1] 인증된 PREF 호출과 서버 정본 mapping을 구현 in android/app/src/main/java/com/gilpick/settings/SettingsRepository.kt
  - 영역: FE
  - 담당: hs
  - 선행: T006, T009
  - 검증: GET/PATCH가 F001 `withAuthorizedCall`의 401 refresh/replay를 재사용하고 repository 관련 T009가 통과하며 public repository 함수에 필요한 KDoc이 있는지 확인

- [x] T014 [US1] loading·content·error와 단일 in-flight·마지막 희망값 상태 전이를 구현 in android/app/src/main/java/com/gilpick/settings/SettingsUiState.kt and android/app/src/main/java/com/gilpick/settings/SettingsViewModel.kt
  - 영역: FE
  - 담당: hs
  - 선행: T013
  - 검증: ViewModel 관련 T009 통과, 조회 1초 초과 전에는 loading을 노출하지 않고 실패 시 마지막 성공값과 재시도를 유지하며 public 상태 변경 함수에 필요한 KDoc이 있는지 확인

- [x] T015 [US1] 단일 장소 변경 제안 알림 설정 영역과 상태별 UI 구현 in android/app/src/main/java/com/gilpick/settings/SettingsScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T001, T014
  - 검증: content·지연 loading·error·저장 중 상태 확인, empty 미적용, 48×48dp·8dp·색 외 상태 전달·최대 글자 크기 기준 충족

- [x] T016 [US1] 설정 변경 성공·실패·연속 선택 Compose UI test 작성 in android/app/src/androidTest/java/com/gilpick/settings/SettingsPreferenceTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T015
  - 검증: 저장 중 표시, 실패 rollback·재시도, 최종 선택 유지와 독립 행동 활성 상태 test 통과

**Checkpoint**: 단일 알림 설정이 API와 Android에서 독립적으로 동작하고 F011 동작을 정확히 통제한다.

---

## Phase 4: User Story 2 - 정책 문서를 확인한다 (Priority: P2)

**Goal**: 개인정보처리방침과 이용약관을 승인된 HTTPS Custom Tab으로 구분해 열고 실패 후 재시도한다.

**Independent Test**: 두 항목이 올바른 문서를 열고 복귀 시 설정·session이 유지되며, 빈 URL·HTTP URL·Custom Tab 실행 실패가 화면을 닫지 않는지 확인한다. 실행 뒤 network·HTTP·문서 로딩 오류는 브라우저가 표시한다.

### Tests for User Story 2

- [ ] T017 [P] [US2] HTTPS URL 검증과 Custom Tab 실행 결과 unit test 작성 in android/app/src/test/java/com/gilpick/settings/PolicyDocumentLauncherTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T002
  - 검증: 개인정보처리방침·이용약관 구분, blank·HTTP·launch 실패가 재시도 가능한 실패로 반환되는 test가 구현 전 실패

### Implementation for User Story 2

- [ ] T018 [US2] 승인 URL 검증과 Custom Tabs launcher 구현 in android/app/src/main/java/com/gilpick/settings/PolicyDocumentLauncher.kt
  - 영역: FE
  - 담당: hs
  - 선행: T017, 제품·운영 담당자의 승인된 두 HTTPS URL 제공
  - 검증: T017 통과, URL·탐색 내용이 log에 남지 않고 실행 뒤 network·HTTP·문서 로딩 상태를 앱이 추적하지 않으며 별도 WebView·정책 화면을 만들지 않았고 public launcher 함수에 필요한 KDoc이 있는지 확인

- [ ] T019 [US2] 정책 항목·열기 실패·재시도 상호작용을 설정 화면에 연결 in android/app/src/main/java/com/gilpick/settings/SettingsScreen.kt and android/app/src/main/java/com/gilpick/settings/SettingsViewModel.kt
  - 영역: FE
  - 담당: hs
  - 선행: T015, T018
  - 검증: 설정 저장 상태와 독립적으로 두 정책 항목 사용 가능, 실패 시 화면·session 유지, 복귀 후 마지막 성공 설정 유지

- [ ] T020 [US2] 정책 문서 진입·복귀·오류 Compose UI test 작성 in android/app/src/androidTest/java/com/gilpick/settings/SettingsPolicyTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T019
  - 검증: Compose UI test로 문서 구분·복귀·빈 URL·HTTP URL·Custom Tab 실행 실패와 재시도를 검증하고, 실제 기기 또는 emulator에서 각 정책 문서 열기 5회가 모두 3초 이내인지 별도로 수동 측정

**Checkpoint**: 정책 문서 기능은 PREF API 상태와 독립적으로 검증 가능하다.

---

## Phase 5: User Story 3 - 현재 기기에서 로그아웃한다 (Priority: P2)

**Goal**: 설정 화면에서 F001의 현재 기기 로그아웃을 실행하고 다른 기기 session은 유지한다.

**Independent Test**: online·offline에서 현재 기기가 즉시 로그인 화면으로 이동하고 다른 기기는 계속 인증되는지 확인한다.

### Tests for User Story 3

- [ ] T021 [P] [US3] 설정 화면 로그아웃과 최상위 인증 전환 UI test 작성 in android/app/src/androidTest/java/com/gilpick/settings/SettingsLogoutTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T015
  - 검증: 중복 선택에도 한 번만 실행, online·offline 모두 즉시 로그인 화면 이동, F001 서버 폐기 재시도 경계 test가 구현 전 실패

### Implementation for User Story 3

- [ ] T022 [US3] 설정 로그아웃을 F001 AuthViewModel 흐름에 연결하고 여행 목록의 임시 로그아웃 진입을 제거 in android/app/src/main/java/com/gilpick/settings/SettingsNavigation.kt, android/app/src/main/java/com/gilpick/MainActivity.kt, and android/app/src/main/java/com/gilpick/trip/TripListScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T021
  - 검증: T021과 기존 F001 logout test 통과, Token 직접 삭제·별도 logout 상태·다른 기기 폐기 호출이 추가되지 않았는지 확인

**Checkpoint**: 설정 화면 로그아웃을 F001 기능과 분리해 독립 검증할 수 있다.

---

## Phase 6: User Story 4 - 계정과 앱 정보를 확인한다 (Priority: P3)

**Goal**: session의 계정 정보와 설치 앱 버전을 보여 주고 누락값에는 안전한 대체 표시를 사용한다.

**Independent Test**: 닉네임·프로필 이미지의 존재·누락 조합과 현재 versionName을 확인하고 설정 기능이 영향을 받지 않는지 검증한다.

### Tests for User Story 4

- [ ] T023 [P] [US4] 계정 정보 mapping과 누락 대체 표시 UI test 작성 in android/app/src/androidTest/java/com/gilpick/settings/SettingsAccountInfoTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T015
  - 검증: nickname·profile URL 조합별 대체 표시, 카카오 연동 상태, versionName 표시 test가 구현 전 실패

### Implementation for User Story 4

- [ ] T024 [US4] F001 session 계정 정보와 BuildConfig.VERSION_NAME을 설정 상태·화면에 연결 in android/app/src/main/java/com/gilpick/settings/SettingsUiState.kt, android/app/src/main/java/com/gilpick/settings/SettingsViewModel.kt, and android/app/src/main/java/com/gilpick/settings/SettingsScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T023
  - 검증: T023 통과, 인증 session 존재를 카카오 연동으로 표시하고 프로필 조회 API·session provider field·프로필 수정 기능을 추가하지 않으며 null 값을 지어내지 않는지 확인

**Checkpoint**: 모든 사용자 스토리가 개별 완료 조건을 충족한다.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 계약·최상위 탐색·adaptive UI와 전체 회귀를 최종 검증한다.

- [X] T025 [P] PREF 구현 계약을 공용 API 문서와 feature OpenAPI에 대조·동기화 in docs/design/api-spec.md and specs/012-user-settings/contracts/preferences.openapi.yaml
  - 영역: BE
  - 담당: jh
  - 선행: T011
  - 검증: path·method·field·200/400/401·공통 envelope가 생성 OpenAPI와 일치하고 DB migration 불필요가 확인됨

- [ ] T026 최상위 Settings destination과 선택 상태를 연결하되 진행 여행 선택 정책은 추가하지 않음 in android/app/src/main/java/com/gilpick/settings/SettingsNavigation.kt and android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: hs
  - 선행: T016, T020, T022, T024
  - 검증: Settings → 다른 최상위 화면 → Settings 왕복 뒤 마지막 성공 설정값이 유지되고 기존 여행·진행 route 회귀 test가 통과하며, F002 중복 기간 정책 미구현 상태에서 임의의 진행 여행을 고르지 않음

- [ ] T027 Android 설정 화면 adaptive·접근성·Figma screenshot 검증 in android/app/src/androidTest/java/com/gilpick/settings/SettingsAdaptiveTest.kt and specs/012-user-settings/quickstart.md
  - 영역: FE
  - 담당: hs
  - 선행: T026
  - 검증: 360dp, phone/tablet 세로·가로, 시스템 최대 글자 크기, system/navigation bar inset에서 잘림·겹침·가로 scroll·접근 불가 0건이고 승인 Figma와 content screenshot 일치

- [X] T028 Backend 전체 정적 분석·관련 회귀·계약 검증 실행 using specs/012-user-settings/quickstart.md
  - 영역: BE
  - 담당: jh
  - 선행: T012, T025
  - 검증: `pytest` PREF·notification 관련 suite, `ruff check app tests`, `mypy app`, OpenAPI YAML·생성 schema 대조 결과를 Backend PR에 기록하고 public API·service docstring 확인

- [ ] T029 Android 전체 unit·instrumented·정적 분석과 종단간 검증 실행 using specs/012-user-settings/quickstart.md
  - 영역: FE
  - 담당: hs
  - 선행: T011, T027
  - 검증: `testDebugUnitTest`, `connectedDebugAndroidTest`, lint, 설정 변경 수동 검증 5회가 모두 30초 이내인지 확인, 동시 변경·재조회 자동 검증 10회 일치, 정책 진입·복귀, 두 기기 로그아웃, 구현 담당자를 제외한 팀원 2명의 첫 시도 위치 찾기 결과와 미실행 항목·이유를 Android PR에 기록

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작 가능하다. T002는 URL 주입 경계만 만들므로 승인 URL 없이 완료할 수 있다.
- **Foundational (Phase 2)**: Setup과 병렬 착수 가능하지만 T001·T002 결과가 필요한 UI 구현 전에 완료해야 한다.
- **US1 (Phase 3)**: T005·T006 이후 BE와 FE를 병렬 구현할 수 있다. 실제 Backend 없이 계약과 mock으로 완료 가능하다.
- **US2 (Phase 4)**: T002와 공통 설정 화면 T015 이후 시작할 수 있고, T018 완료에는 승인된 두 HTTPS URL이 필요하다.
- **US3 (Phase 5)**: 공통 설정 화면 T015 이후 F001을 재사용해 독립 구현 가능하다.
- **US4 (Phase 6)**: 공통 설정 화면 T015 이후 F001 session을 재사용해 독립 구현 가능하다.
- **Polish (Phase 7)**: 선택한 모든 story가 완료된 뒤 수행한다.

### User Story Dependencies

```text
Setup ─┬─ Foundational ── US1(P1) ──┬─ US2(P2) ─┐
       │                            ├─ US3(P2) ─┼─ Polish
       └─ 승인 정책 URL ──────────── US2(P2)
```

- **US1**: Backend 설정 기능과 공통 Settings 화면을 제공하므로 먼저 완료한다.
- **US2**: US1의 Settings 화면을 확장하지만 PREF API 성공 여부와 독립 검증한다.
- **US3**: US1의 Settings 화면을 확장하며 F001 logout에만 의존한다.
- **US4**: US1의 Settings 화면을 확장하며 F001 session 표시 정보에만 의존한다.

### Parallel Opportunities

- T001과 T002는 서로 병렬 가능하다.
- T003과 T004는 FE/BE 계약 test로 병렬 가능하다.
- T005와 T006은 각 선행 test 이후 병렬 가능하다.
- US1에서 Backend T007·T008과 Android T009를 병렬로 작성할 수 있다.
- T011 완료 전까지 Backend T010과 Android T013~T016을 계약과 mock으로 병렬 구현할 수 있다. 실제 API 종단간 검증 T029만 T011을 기다린다.
- T015 완료 뒤 US2의 T017~T020, US3의 T021~T022, US4의 T023~T024는 파일 소유권 순서를 조율한 뒤 진행할 수 있다. `SettingsScreen.kt`와 `SettingsViewModel.kt`를 공유하므로 같은 시간에 수정하지 않는다.
- T025는 Android story 마무리와 병렬 가능하다.

## Parallel Execution Examples

### User Story 1

```text
Backend jh: T007 → T010, T008 → T011 → T012
Frontend hs: T009 → T013 → T014 → T015 → T016
통합 지점: T011 완료 뒤 T013의 실제 PREF 연동 검증
```

### User Stories 2~4

```text
US2: T017 → T018 → T019 → T020
US3: T021 → T022
US4: T023 → T024
```

세 story는 기능적으로 독립적이지만 동일 Android 화면 파일을 수정하므로 `hs`가 US2 → US3 → US4 순으로 적용하거나 commit 단위로 파일 소유권을 조율한다.

## Implementation Strategy

### MVP First (User Story 1)

1. T001과 Phase 2 계약 기반을 완료한다.
2. Backend T007~T012와 Frontend T009~T016을 구현한다.
3. 설정 ON/OFF, 실패 rollback, 다기기 최종값, F011 회귀를 독립 검증한다.
4. US1 checkpoint에서 중단해도 핵심 F012 가치가 동작한다.

### Incremental Delivery

1. Setup + Foundational → 계약과 시각 정본 확정
2. US1 → 단일 알림 설정 MVP
3. US2 → 정책 문서
4. US3 → 현재 기기 로그아웃 진입
5. US4 → 계정·앱 정보
6. Polish → 최상위 탐색, adaptive screenshot, 전체 회귀

## Notes

- `[P]`는 파일과 미해결 선행 관계가 겹치지 않을 때만 표시했다.
- 테스트 task는 구현 전에 실패를 확인하고 해당 구현 뒤 통과시킨다.
- Backend와 Frontend는 별도 Issue·branch·PR로 나누고 실제 API 연동과 종단간 검증은 dependency를 명시한다.
- 승인 정책 URL이 없어도 T002와 US1은 완료할 수 있지만 T018·US2·최종 종단간 검증은 완료 처리하지 않는다.
- F012는 진행 중 여행 선택이나 기간 중복 방지 구현을 포함하지 않는다.
