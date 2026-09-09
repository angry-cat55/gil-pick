---

description: "F007 위치 기반 감지 구현 task 목록"
---

# Tasks: 위치 기반 감지

**Input**: `specs/007-location-detection/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/detection.openapi.yaml`, `quickstart.md`

**Organization**: Feature Owner는 `hs`다. Backend 담당은 `ts`, Frontend Android 담당은 `hs`다(2026-09-08 합의). 테스트는 명세의 독립 검증·성공 기준과 constitution 품질 게이트를 만족하도록 구현보다 먼저 배치한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 미완료 선행 작업이 없고 수정 파일이 다른 작업
- **[Story]**: 해당 User Story 추적 표기
- 보조 메타데이터의 선행·검증 조건까지 완료해야 task 완료로 본다.

## Path Conventions

- Backend: `api/app/`, `api/migrations/`, `api/tests/`
- Android: `android/app/src/main/java/com/gilpick/`, `android/app/src/test/`, `android/app/src/androidTest/`

---

## Phase 1: Setup

**Purpose**: F007 계약을 공용 문서에 고정하고 Android 백그라운드 위치·지오펜스 준비와 Figma 부족 요소를 채운다.

- [x] T001 F007 계약·ERD·API 명세 동기화와 교차 review in specs/007-location-detection/contracts/detection.openapi.yaml, specs/006-trip-progress/contracts/progress.openapi.yaml, docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: 통합
  - 담당: ts
  - 교차 확인: hs
  - 선행: 없음
  - 검증: PROG-003 요청(`eventId`·`geofenceId`·`occurredAt`·`location`)과 응답(`accepted`·`rejectionReason`·`candidate`·`cancelledTransitionId`), PROG-004 `decision` 3종과 `undoDeadline` null 규칙(FR-020), PROG-005 응답(`restoredItems`·`dayStatus`·`detectionResumeAt`), 신설 오류 code 4종(`TRANSITION_NOT_PENDING`·`INVALID_DECISION`·`UNDO_WINDOW_EXPIRED`·`TRANSITION_NOT_UNDOABLE`), F006 PROG-001 응답 확장 3종(`detectionTargets`·`pendingCandidate`·`undoable`)이 api-spec에 반영되고 BE `ts`와 FE `hs`가 확인. F006 계약 파일의 `ProgressData`에도 세 field를 추가하고 "F007 확장" 주석으로 정의 위치를 `detection.openapi.yaml`로 가리킴(F006 파일 수정이므로 `jy` review). ERD 7.1·7.2와 10절 enum은 변경 없이 그대로 쓰는지 대조
- [x] T002 [P] Android 백그라운드 위치 권한과 지오펜스 receiver 선언 in android/app/src/main/AndroidManifest.xml, android/app/build.gradle.kts
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: `ACCESS_BACKGROUND_LOCATION` 선언과 지오펜스 broadcast receiver 등록 후 `--offline` debug build 성공. `play-services-location`은 F006이 이미 추가했으므로 새 의존성이 없음을 PR에 기록
- [x] T003 [P] Figma `ActiveTravelScreen` 부족 요소 반영과 저장소 사본 갱신 in docs/design/figma-make/src/screens/ActiveTravelScreen.tsx
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: 도착 확인 시트와 되돌리기 토스트는 이미 있으므로 그대로 두고, **출발 확인 시트**(도착 시트와 같은 구조, `출발했어요`·`아직 머무는 중`), 자동 처리 표시(UI-004), 자동 감지 꺼짐 안내(UI-005) 3건을 Figma에 추가한 뒤 MCP로 원본을 다시 받아 사본을 갱신. 추가 전후 차이를 PR에 기록

---

## Phase 2: Foundational

**Purpose**: 모든 User Story가 공유하는 이벤트 저장·검증 구조와 Android API 계층을 만든다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 User Story 구현을 시작하지 않는다.

- [x] T004 `progress_events` migration과 DB 제약 구현 in api/migrations/versions/007_create_progress_events.py
  - 영역: BE
  - 담당: ts
  - 선행: T001
  - 검증: upgrade·downgrade 왕복, `UNIQUE(client_event_id)`, `trip_days` cascade 삭제, `INDEX(trip_day_id, occurred_at DESC)`, `progress_transitions.trigger_event_id` FK 연결을 `api/tests/integration/test_detection_migration.py`로 검증. **`progress_transitions`에 컬럼을 추가하지 않음**(migration 005가 이미 생성)을 PR에 기록
- [x] T005 [P] `ProgressEvent` ORM model in api/app/models/progress.py, api/app/models/__init__.py
  - 영역: BE
  - 담당: ts
  - 선행: T004
  - 검증: model과 migration의 column·constraint 일치, `location` geography(Point,4326), relationship·cascade를 `api/tests/unit/test_detection_model.py`에서 확인
- [x] T006 [P] 감지 schema·enum·오류 code in api/app/schemas/progress.py
  - 영역: BE
  - 담당: ts
  - 선행: T001
  - 검증: 계약의 `ProgressEventRequest`(좌표 범위·정확도 validation), `TransitionCandidate`(`allowedDecisions`·`evidence`), `DecisionRequest`, `TransitionResult`, `UndoResult`, `DetectionTarget`을 `api/tests/unit/test_detection_schema.py`에서 검증. `rejection_reason` 7종과 신설 오류 code 4종 포함
- [x] T007 이벤트 검증·저장과 멱등 처리 in api/app/services/detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T005, T006
  - 검증: 정확도 초과 → `LOW_ACCURACY`, 유효 시간 경과 → `STALE`, 시작 전·완료 날짜 → `DAY_NOT_IN_PROGRESS`, 대상 아님 → `ITEM_NOT_ELIGIBLE`로 `accepted=false` 저장(행은 남김). 같은 `client_event_id` 재전송이 최초 결과를 그대로 반환. 소유권 위반은 `403`. quickstart BE 7-1·7-2·7-3·7-4·7-5. 좌표를 log에 남기지 않음
- [x] T008 [P] Android 감지 API DTO와 Retrofit 계약 in android/app/src/main/java/com/gilpick/progress/DetectionApi.kt
  - 영역: FE
  - 담당: hs
  - 선행: T001
  - 검증: 계약의 세 endpoint와 `DetectionTarget`·`TransitionCandidate`·`UndoableTransition` DTO 직렬화를 `android/app/src/test/java/com/gilpick/progress/DetectionApiTest.kt`에서 MockWebServer로 확인. 모르는 key 무시, nullable field는 key째 전송
- [x] T009 Android `DetectionRepository`와 오류 분류 in android/app/src/main/java/com/gilpick/progress/DetectionRepository.kt
  - 영역: FE
  - 담당: hs
  - 선행: T008
  - 검증: `AuthRepository.withAuthorizedCall` 재사용, 신설 오류 code 4종과 통신 실패·session 만료 분류를 `android/app/src/test/java/com/gilpick/progress/DetectionRepositoryTest.kt`에서 확인

**Checkpoint**: 이벤트를 받아 저장·검증할 수 있고 앱이 세 endpoint를 호출할 수 있다.

---

## Phase 3: User Story 1 - 다음 장소 도착을 앱이 먼저 묻는다 (Priority: P1) 🎯 MVP

**Goal**: 다음 장소 근처에 머무르면 도착 확인 질문이 생기고, 사용자가 답해 도착을 확정하거나 물릴 수 있다.

**Independent Test**: 진행 중인 날짜에서 대상 장소의 `DWELL` 이벤트를 보내 후보가 생기는지, `CONFIRM`으로 `ARRIVED`가 되고 뒤 장소 ETA가 재계산되는지, `NOT_ARRIVED`로 취소되고 재질문·상한이 지켜지는지 확인한다. 자동 확정과 되돌리기 없이 검증할 수 있다.

### Tests for User Story 1

> **NOTE: 구현 전에 작성해 실패를 확인한다.**

- [x] T010 [P] [US1] 도착 후보 contract·integration test in api/tests/contract/test_detection_contract.py, api/tests/integration/test_detection_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T007
  - 검증: quickstart BE 1(후보 생성·`CONFIRM`·`undoDeadline` null)·BE 2(거절·`nextPromptAt`·상한)·BE 6(복합 전환)·BE 8(후보 무효화)·BE 9(감지 대상 목록) 시나리오
- [x] T011 [P] [US1] 감지 판정 service unit test in api/tests/unit/test_detection_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T007
  - 검증: 파생 판정 4종(질문 횟수 상한, 재질문 가능 시각, `EN_ROUTE`만 도착 대상, 한 item에 `PENDING` 하나) 경계값. `progress_transitions` 조회만으로 판정하고 새 컬럼을 읽지 않음
- [x] T012 [P] [US1] 도착 확인 시트 UI test in android/app/src/androidTest/java/com/gilpick/progress/ConfirmSheetTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T009
  - 검증: 장소명·감지 근거·자동 확정까지 남은 시간·두 행동 표시(UI-001), 응답 중 버튼 잠금과 진행 표시, 실패 시 원인+다시 시도와 후보 유지(UI-006), 시트를 닫아도 수동 진행 가능(UI-007), 48dp·8dp 간격(UI-008)
- [x] T013 [P] [US1] `GeofenceManager` 차이 반영 unit test in android/app/src/test/java/com/gilpick/progress/GeofenceManagerTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T009
  - 검증: `detectionTargets`와 현재 등록 상태의 차이만 등록·해제, 목록이 비면 전부 해제, 같은 목록 재수신 시 재등록 없음. 앱이 대상을 직접 계산하지 않음(research 4절)

### Implementation for User Story 1

- [x] T014 [US1] 도착 후보 생성·거절·상한 규칙 in api/app/services/detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T010, T011
  - 검증: T010·T011 통과. `DWELL` → `ARRIVAL` 후보(`PENDING_CONFIRMATION`, `source=GEOFENCE_DWELL`, `auto_finalize_at` 설정), `NOT_ARRIVED` → `CANCELLED`와 `nextPromptAt`, 상한 도달 시 `PROMPT_LIMIT_REACHED`. 후보 생성은 `progress_version`을 올리지 않음
- [x] T015 [US1] 확정 시 F006 전환 재사용과 복합 전환 in api/app/services/detection.py, api/app/services/progress.py
  - 영역: BE
  - 담당: ts
  - 선행: T014
  - 검증: `CONFIRM` 시 F006 전환 함수를 호출해 상태·ETA·당일 완료를 처리하고 규칙을 다시 구현하지 않음. 이전 EXIT 없이 다음 DWELL이면 `transition_type=COMPOSITE`로 이전 완료와 다음 도착을 한 transaction에 기록(FR-009, quickstart BE 6). 후보가 살아 있는 동안 대상이 수동 처리·삭제되면 `CANCELLED`(FR-010). F006 파일 수정이므로 `jy` review
- [x] T016 [US1] 감지 대상 산출과 PROG-001 응답 확장 in api/app/services/detection.py, api/app/api/v1/progress.py, api/app/schemas/progress.py
  - 영역: BE
  - 담당: ts
  - 선행: T014
  - 검증: quickstart BE 9. `detectionTargets`에 `EN_ROUTE`의 `ARRIVAL`과 `ARRIVED`의 `DEPARTURE`만, 상한·쉬는 시간 대상은 제외. 당일 완료 시 빈 배열이고, 상태 수정으로 그 날짜가 다시 `IN_PROGRESS`가 되면 목록이 다시 채워짐(FR-022 양방향). F004에서 장소 추가·삭제·순서 변경이 일어난 뒤 조회하면 최신 일정 기준으로 대상이 바뀜(FR-023). `pendingCandidate`가 함께 내려감. F006 응답 확장이므로 `jy` review
- [x] T017 [US1] PROG-003 이벤트 등록과 PROG-004 확인 응답 endpoint in api/app/api/v1/progress.py
  - 영역: BE
  - 담당: ts
  - 선행: T015, T016
  - 검증: T010 통과. 기준 미충족 이벤트도 `200`. PROG-003은 요청 본문의 `eventId`로 멱등 처리하고, PROG-004는 `Idempotency-Key`를 필수로 사용. 이미 처리된 후보는 `409 TRANSITION_NOT_PENDING`, 후보 종류에 없는 응답은 `409 INVALID_DECISION`. 소유권 검증 재사용
- [x] T018 [US1] `GeofenceManager`·`GeofenceReceiver` 구현 in android/app/src/main/java/com/gilpick/progress/GeofenceManager.kt, android/app/src/main/java/com/gilpick/progress/GeofenceReceiver.kt
  - 영역: FE
  - 담당: hs
  - 선행: T013, T002
  - 검증: T013 통과. 도착용 반경·`loiteringDelay`로 `DWELL`, 출발용 반경으로 `EXIT`·`ENTER` 등록(`geofenceId`는 `{itemId}:{kind}`). broadcast를 `client_event_id`와 함께 전송하고 실패 시 재시도. 등록 실패는 자동 감지만 끄고 진행을 막지 않음(FR-024)
- [x] T019 [US1] 도착 확인 시트와 `ProgressViewModel` 후보 상태 in android/app/src/main/java/com/gilpick/progress/ConfirmSheet.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt
  - 영역: FE
  - 담당: hs
  - 선행: T012, T018, T003
  - 검증: T012 통과. Figma `ActiveTravelScreen` 도착 확인 시트 대조, `pendingCandidate`는 서버 응답에서만 오고 앱이 추정하지 않음, 남은 시간은 표시만 하고 만료 판정 없음, theme token만 사용. 360dp·최대 글자 배율 잘림 없음(UI-009)

**Checkpoint**: 사용자가 묻는 말에 답하는 것만으로 도착을 처리할 수 있다. 자동 확정 없이 단독으로 동작한다.

---

## Phase 4: User Story 2 - 답하지 못해도 진행이 멈추지 않는다 (Priority: P2)

**Goal**: 무응답이면 자동으로 도착이 확정되고, 사실과 다르면 정해진 시간 안에 되돌릴 수 있다.

**Independent Test**: 후보를 만든 뒤 응답하지 않고 자동 확정 시각을 지나게 해 확정되는지, 되돌리기로 상태와 ETA가 복원되는지, 만료 후에는 거부되는지 확인한다.

### Tests for User Story 2

- [x] T020 [P] [US2] 지연 확정·되돌리기 contract·integration test in api/tests/contract/test_detection_contract.py, api/tests/integration/test_detection_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T017
  - 검증: quickstart BE 3(자동 확정·되돌리기·만료 `409`)·BE 4(되돌린 뒤 재개). 서버 시각을 고정해 만료를 재현하고 기기 시각과 무관함을 확인(FR-018, SC-007)
- [x] T021 [P] [US2] 되돌리기 토스트·자동 처리 표시 UI test in android/app/src/androidTest/java/com/gilpick/progress/UndoToastTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T019
  - 검증: quickstart FE 2·FE 3. 바뀐 내용·남은 시간·`되돌리기` 표시(UI-003), 남은 시간이 0이면 사라지고 누를 수 없음, 자동 처리된 행이 색이 아닌 문구로 구분됨(UI-004)

### Implementation for User Story 2

- [x] T022 [US2] 지연 확정 진입점과 무응답 자동 확정 in api/app/services/progress.py, api/app/services/detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T020
  - 검증: T020 통과. 그 날짜에 대한 모든 요청(PROG-001·003·004·005·006) 처리 전에 만료된 `PENDING_CONFIRMATION`을 먼저 확정하고 본 요청을 같은 transaction에서 이어 처리. `status=AUTO_CONFIRMED`, `source=GEOFENCE_AUTO`, `confirmed_at`·`undo_deadline`은 저장된 `auto_finalize_at` 기준(실행 시각과 무관). 주기 작업자를 도입하지 않음(research 2절). F006 파일 수정이므로 `jy` review
- [x] T023 [US2] PROG-005 되돌리기 endpoint와 복원 in api/app/api/v1/progress.py, api/app/services/detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T022
  - 검증: T020 통과. `affected_items` 전부와 날짜 상태 스냅샷을 확정 직전으로 복원하고 ETA 재계산(FR-017·FR-019), `status=UNDONE`·`undone_at` 기록, `progress_version` +1, 한 transaction. 만료는 `409 UNDO_WINDOW_EXPIRED`, 사용자 확인 전환은 `409 TRANSITION_NOT_UNDOABLE`. `Idempotency-Key` 멱등
- [x] T024 [US2] 되돌린 뒤 감지 재개 규칙과 `undoable` 응답 in api/app/services/detection.py, api/app/schemas/progress.py
  - 영역: BE
  - 담당: ts
  - 선행: T023
  - 검증: quickstart BE 4. 재개 시각은 `max(undone_at) + 재질문 간격`으로 파생 계산하고 새 컬럼을 만들지 않음(FR-017a, research 3절). 쉬는 시간 중 이벤트는 `DETECTION_PAUSED`. 재개 후 질문도 상한에 포함. PROG-001 응답에 `undoable`이 내려감
- [x] T025 [US2] 되돌리기 토스트와 만료 시각 재조회 in android/app/src/main/java/com/gilpick/progress/UndoToast.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt
  - 영역: FE
  - 담당: hs
  - 선행: T021, T024
  - 검증: T021 통과. Figma 토스트 대조, `undoDeadline`은 표시만 하고 만료 판정은 서버가 함, `autoFinalizeAt`·`undoDeadline`에 로컬 알람을 걸어 그 시각에 진행 재조회. 자동 처리 표시(UI-004)를 목록 행에 반영

**Checkpoint**: 사용자가 앱을 보지 않아도 진행이 이어지고, 잘못된 확정을 되돌릴 수 있다.

---

## Phase 5: User Story 3 - 장소를 떠나면 출발도 앱이 묻는다 (Priority: P3)

**Goal**: 장소를 벗어나면 출발을 묻고, 재진입·`아직 머무는 중`·무응답 자동 출발을 규칙대로 처리한다.

**Independent Test**: `ARRIVED` 항목에서 `EXIT`를 보내 후보가 생기는지, `REENTER`로 취소되는지, `STILL_HERE`로 그 날짜 감지가 멈추는지, 무응답이면 출발이 확정되고 되돌릴 수 있는지 확인한다.

### Tests for User Story 3

- [x] T026 [P] [US3] 출발 후보·재진입·오판 test in api/tests/contract/test_detection_contract.py, api/tests/integration/test_detection_flow.py, api/tests/unit/test_detection_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T024
  - 검증: quickstart BE 5 전체(후보·`REENTER` 취소·`STILL_HERE` 중단·무응답 자동 출발·두 번째 되돌리기 중단). 수동 출발 후 뒤늦은 `EXIT`가 추가 전환을 만들지 않음(US3 Acceptance 5). 반복 회귀: 도착은 되돌리기 → 재개 → 상한 도달로, 출발은 되돌리기 2회로 각각 유한 횟수 안에 멈추고 같은 장소에서 무한 반복이 생기지 않음(SC-011)
- [X] T027 [P] [US3] 출발 확인 시트 UI test in android/app/src/androidTest/java/com/gilpick/progress/ConfirmSheetTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T025
  - 검증: `출발했어요`·`아직 머무는 중` 두 행동과 도착 시트와 같은 상태 처리(UI-002·UI-006). 같은 composable을 행동 라벨만 바꿔 쓰는지 확인

### Implementation for User Story 3

- [x] T028 [US3] 출발 후보 생성과 재진입 취소 in api/app/services/detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T026
  - 검증: T026 통과. `EXIT` → `DEPARTURE` 후보(`source=GEOFENCE_EXIT`), `REENTER` → `CANCELLED`와 `cancelledTransitionId` 반환(FR-012). `ARRIVED` 항목에만 후보 생성(FR-003). 출발 확정(`CONFIRM`·무응답 자동 모두)은 T015가 쓰는 F006 전환 함수를 그대로 호출해 현재 장소 `COMPLETED`·다음 예정 장소 `EN_ROUTE`·남은 ETA 재계산을 처리하고 규칙을 다시 구현하지 않음(FR-014·FR-016)
- [x] T029 [US3] `STILL_HERE`와 반복 되돌리기 중단 규칙 in api/app/services/detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T028
  - 검증: T026 통과. `STILL_HERE` 이후 그 날짜 자동 출발 중단(`DEPARTURE_DETECTION_STOPPED`, FR-013), 같은 장소의 출발 되돌리기 2회 이후 중단(FR-017a). 두 규칙 모두 `progress_transitions` 파생 계산이며 새 컬럼 없음
- [X] T030 [US3] 출발 확인 시트 연결 in android/app/src/main/java/com/gilpick/progress/ConfirmSheet.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt
  - 영역: FE
  - 담당: hs
  - 선행: T027, T003
  - 검증: T027 통과. Figma에 추가한 출발 확인 시트(T003) 대조, 도착 시트와 한 composable을 공유하고 `allowedDecisions`로 행동을 고름

**Checkpoint**: 도착과 출발 모두 자동으로 감지되고 오판을 되돌릴 수 있다.

---

## Phase 6: User Story 4 - 위치를 쓸 수 없어도 여행은 계속된다 (Priority: P4)

**Goal**: 권한이 없거나 정확도가 부족해도 F006 수동 진행이 그대로 동작하고, 자동 감지가 꺼진 이유를 안내한다.

**Independent Test**: 백그라운드 권한 거부 상태와 정확도 미달 상태 각각에서 수동 진행 행동이 모두 되는지, 안내가 보이고 무시할 수 있는지 확인한다.

### Tests for User Story 4

- [X] T031 [P] [US4] 권한 축소 동작 UI test in android/app/src/androidTest/java/com/gilpick/progress/DetectionPermissionTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T030
  - 검증: quickstart FE 5. 권한 없음 상태에서 F006 수동 진행 행동 전부 동작, 자동 감지 꺼짐 안내에 원인과 켜는 방법이 있고 무시 가능(UI-005), 진행 중 권한 회수 시 이미 확정된 상태 유지
- [x] T032 [P] [US4] 정확도 미달 이벤트 회귀 test in api/tests/integration/test_detection_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T029
  - 검증: 정확도가 계속 기준 미달일 때 후보가 생기지 않고 수동 전환은 정상 동작(US4 Acceptance 4, SC-006)

### Implementation for User Story 4

- [X] T033 [US4] 백그라운드 위치 권한 2단계 요청 in android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T031
  - 검증: T031 통과. 진행 시작 후 자동 감지를 켤 때만 백그라운드 권한을 별도 요청하고, 거부해도 시작·수동 진행을 막지 않음(research 8절, FR-024). F006이 받는 앱 사용 중 권한과 이어짐
- [X] T034 [US4] 자동 감지 꺼짐 안내 in android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T033, T003
  - 검증: T031 통과. 원인(권한 없음·정확도 부족)과 다음 행동을 함께 안내하고 Figma에 추가한 모양을 따름(UI-005). 색 단독으로 알리지 않음

**Checkpoint**: 위치를 쓸 수 없는 사용자도 F006 수준의 진행을 그대로 쓴다.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T035 Backend F007 전체 자동 test와 문서 동기화 in docs/design/api-spec.md, api/tests/
  - 영역: BE
  - 담당: ts
  - 선행: T032
  - 검증: quickstart Backend 명령 전체 통과, `api-spec.md` PROG-003·004·005와 PROG-001 응답 확장 갱신, `compileall`, `git diff --check`, 공개 함수 docstring(constitution 문서화 기준)
- [x] T036 Android F007 전체 자동 test·build와 AVD 검증 in android/app/src/test/java/com/gilpick/progress/, android/app/src/androidTest/java/com/gilpick/progress/
  - 영역: FE
  - 담당: hs
  - 선행: T034
  - 검증: quickstart Android 항목 전체. `testDebugUnitTest`·`connectedDebugAndroidTest` 통과, `Pixel_9_Pro`(API 36)에서 도착 확인·출발 확인·되돌리기·만료·감지 꺼짐 5개 상태를 360dp와 최대 글자 배율로 screenshot(UI-010), `adb emu geo fix`로 앱 종료 상태의 지오펜스 발화 확인
- [x] T037 F007 종단간 검증과 Feature 상태 갱신 in docs/planning/mvp-features.md, specs/007-location-detection/quickstart.md
  - 영역: 통합
  - 담당: hs
  - 교차 확인: ts
  - 선행: T035, T036
  - 검증: quickstart 종단간 5단계를 로컬 API + AVD로 수행하고 결과를 PR에 기록. mvp-features.md의 F007 상태를 `VERIFY`로 갱신. 미실행 항목과 이유를 명시

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(T001~T003)**: T001이 먼저다. T002·T003은 T001과 무관하게 병렬 가능
- **Foundational(T004~T009)**: Setup 완료 후. 모든 User Story를 차단한다
- **US1(T010~T019)** → **US2(T020~T025)** → **US3(T026~T030)** → **US4(T031~T034)**
- **Polish(T035~T037)**: 원하는 User Story가 모두 끝난 뒤

### User Story Dependencies

- **US1(P1)**: Foundational 이후 시작. 다른 story에 의존하지 않는다
- **US2(P2)**: US1의 후보 생성이 있어야 확정할 대상이 생긴다. T017 이후 시작
- **US3(P3)**: 출발 확정이 US2의 자동 확정·되돌리기 경로를 그대로 쓴다. T024 이후 시작
- **US4(P4)**: 앞 세 story가 만든 자동 감지를 끄는 경로라 마지막이다. 다만 T032(BE 회귀 test)는 T029 이후 독립적으로 가능

### 담당자별 흐름

```text
BE ts: T001 → T004~T007 → T010·T011 → T014~T017 → T020 → T022~T024 → T026 → T028·T029 → T032 → T035
FE hs: T002·T003 → T008·T009 → T012·T013 → T018·T019 → T021 → T025 → T027 → T030 → T031 → T033·T034 → T036
통합:  T037
```

BE가 US1 endpoint(T017)를 끝내기 전까지 FE는 MockWebServer로 진행할 수 있다. 두 담당자가 같은 파일을 동시에 고치지 않는다.

### Parallel Opportunities

- T002·T003은 서로 다른 파일이라 동시 진행 가능
- T005·T006은 model과 schema로 파일이 분리돼 병렬 가능. 둘 다 T004·T001 이후
- T010·T011은 BE에서 병렬 가능. T008·T009는 T009가 T008의 DTO를 쓰므로 순서대로 진행한다
- T012·T013은 UI test와 unit test로 파일이 달라 병렬 가능
- 각 story의 test task(`[P]` 표기)는 같은 story 안에서 병렬 가능
- **주의**: `api/app/services/detection.py`는 T007·T014·T015·T016·T024·T028·T029가 함께 건드린다. 순서대로만 진행하고 병렬로 나누지 않는다. `ProgressViewModel.kt`·`ActiveTravelScreen.kt`·`ConfirmSheet.kt`도 마찬가지다

## Parallel Example: User Story 1

```text
# test를 먼저 병렬로 작성한다
Task: "도착 후보 contract·integration test in api/tests/contract/test_detection_contract.py"   # ts
Task: "감지 판정 service unit test in api/tests/unit/test_detection_service.py"                 # ts
Task: "도착 확인 시트 UI test in .../androidTest/.../ConfirmSheetTest.kt"                       # hs
Task: "GeofenceManager 차이 반영 unit test in .../test/.../GeofenceManagerTest.kt"              # hs
```

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 US1 완료
3. **STOP and VALIDATE**: 확인 질문에 답하는 것만으로 도착이 처리되는지 확인
4. 이 시점에 F006 대비 실제 개선(앱이 먼저 묻는다)이 이미 있다

### Incremental Delivery

1. Setup + Foundational → 이벤트를 받고 검증할 수 있다
2. US1 → 도착을 묻고 답으로 확정한다 (MVP)
3. US2 → 답하지 않아도 진행이 이어지고 되돌릴 수 있다
4. US3 → 출발도 자동으로 감지한다
5. US4 → 위치를 못 쓰는 사용자에게 보장 범위를 명시한다

### 교차 review 조건

- `api/app/services/progress.py`, `api/app/api/v1/progress.py`의 F006 Backend 코드 수정(T015·T016·T022)은 원 담당자 `jy` review를 받는다
- `progress/ProgressViewModel.kt`, `progress/ActiveTravelScreen.kt`의 F006 Android 코드 수정(T019·T025·T030·T033·T034)도 원 담당자 `jy` review를 받는다. 진행 화면과 상태 모델은 F006이 소유한다
- 계약과 파생 판정 규칙(T001)은 BE `ts`와 FE `hs`가 구현 전에 함께 확인한다

## Notes

- `[P]`는 파일이 다르고 미완료 선행이 없는 작업이다
- 이 Feature는 신규 테이블 1개(`progress_events`)와 파생 판정만으로 구현한다. `progress_transitions`에 컬럼을 추가하는 task는 없다
- 시각 판정(자동 확정·되돌리기 만료)은 모두 서버 시각이다. 앱은 표시만 한다
- 미실행 검증은 통과했다고 적지 않고 이유와 함께 PR에 남긴다
