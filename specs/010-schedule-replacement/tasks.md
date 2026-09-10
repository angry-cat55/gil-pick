---

description: "F010 일정 변경 구현 task 목록"
---

# Tasks: 일정 변경

**Input**: `specs/010-schedule-replacement/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/replacements.openapi.yaml`, `quickstart.md`

**Organization**: Feature Owner는 `hs`(명세 산출물)다. Backend 구현 담당은 `jh`, Frontend(Android) 구현 담당은 `hs`다(2026-09-09 합의).

- F006 파일 수정(`api/app/services/progress.py`, `api/app/schemas/progress.py`, Android `progress/*`)은 F006 담당 `jy` review를 받는다.
- F008 감지 결과 상태 전이 수정(T027)의 review 주체는 T027 시작 전에 팀에서 확인한다. `docs/planning/mvp-features.md`의 F008 Owner는 `ts`이고 구현 브랜치 이력(`feat/jh-f008-*`)은 `jh`를 가리켜 근거가 갈린다. 어느 쪽이든 감지 결과 상태를 함께 쓰는 F009 담당 `ts`(BE)·`jy`(FE)에게 교차 확인을 받는다.
- F009 파일(`com.gilpick.alternative`)은 읽기만 하고 수정하지 않는다. `onSelectPlace` 콜백 연결은 `MainActivity.kt`에서 한다.
- 테스트는 명세의 독립 검증·성공 기준과 constitution 품질 게이트를 만족하도록 구현보다 먼저 배치한다.

**선행 Feature**: 2026-09-09 기준 F009 Android의 `com.gilpick.alternative` 패키지(화면·ViewModel·`SelectedAlternative`)와 후보 추천 API가 `main`에 병합됐다. 다만 **`AlternativeNavigation.kt`와 `alternativeGraph(onSelectPlace)` 콜백은 F009 T026에서 만들어지며 아직 없다.**

- **T017(미리보기 navigation과 F009 연결)만 F009 T026 완료를 기다린다.** 나머지 FE task(T007·T008·T011·T015·T016·T020·T023·T026·T030·T031)는 지금 시작할 수 있다.
- Backend task는 F009 후보 토큰 검증 함수(`verify_candidate_token`)에만 의존하므로 먼저 시작할 수 있다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 미완료 선행 작업이 없고 수정 파일이 다른 작업
- **[Story]**: 해당 User Story 추적 표기
- 보조 메타데이터의 선행·검증 조건까지 완료해야 task 완료로 본다.

## Path Conventions

- Backend: `api/app/`, `api/tests/`
- Android: `android/app/src/main/java/com/gilpick/`, `android/app/src/test/`, `android/app/src/androidTest/`
- 문서: `docs/design/`, `docs/planning/`, `specs/010-schedule-replacement/`

---

## Phase 1: Setup

**Purpose**: 계약을 공용 문서와 대조해 고정하고, 조정 가능한 정책값과 Figma 기준을 확인한다.

- [x] T001 F010 REPL 계약·ERD·API 명세 대조와 교차 review in specs/010-schedule-replacement/contracts/replacements.openapi.yaml, docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: 통합
  - 담당: jh
  - 교차 확인: hs
  - 선행: 없음
  - 검증: `replacements.openapi.yaml`의 REPL-001~004 경로가 `api-spec.md` 8절과 일치하는지, 응답 구조 차이(`comparison` 네 항목, `detectionReason`, `detectionRestored`, `UndoableReplacement`)와 **`itineraryVersion` → `scheduleVersion` 이름 정정**(research 11절) 대상을 목록화. `er-schema.md`에 `route_previews`·`place_replacements` 두 테이블과 13절 API 매핑 추가 대상을 대조해 PR에 기록. 실제 문서 반영은 T034
- [x] T002 [P] 변경 정책 모듈 in api/app/services/replacement.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: `PREVIEW_TTL_MINUTES = 5`(research 7절), `UNDO_WINDOW_SECONDS = 30`(`docs/planning/requirements.md` REPL-02 확정값)을 이 모듈 상단에서만 읽는다. 두 값을 다른 파일에 중복해 쓰지 않는다. `api/tests/unit/test_replacement_rules.py`(T021)에서 값 접근 확인
- [x] T003 [P] Figma 부족 요소 2건 반영과 저장소 사본 갱신 in docs/design/figma-make/src/screens/RoutePreviewScreen.tsx
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: research 10절의 2건(`RoutePreviewScreen`의 **승인 진행 중·승인 실패** 상태, 비교 항목 **`정보 없음`** 표시)을 Owner가 Figma Make에 추가한 뒤 MCP로 원본을 다시 받아 사본을 갱신. 추가 전후 차이를 PR에 기록. 이미 있는 지도·기존/변경 범례·비교 표·`변경 승인`·`다른 후보 보기`는 그대로 둔다
  - 정정(2026-09-10): 처음에는 `ActiveTravelScreen`의 장소 변경 되돌리기 토스트도 부족 요소로 적었으나 Figma 원본에 이미 있다(research 10절 정정). 그 파일은 이 task의 대상이 아니다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 User Story가 공유하는 테이블·스키마·Android API 계층을 만든다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 User Story 구현을 시작하지 않는다.

- [x] T004 [P] 미리보기·변경 이력 모델 in api/app/models/replacement.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: `RoutePreview`·`PlaceReplacement`가 data-model 1.1·1.2의 컬럼과 제약을 갖는다. `uq_route_previews_pending_detection`(`detection_id`에 `status='PENDING'` 부분 unique), `uq_place_replacements_preview`(`preview_id` unique), `CHECK status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED')` 포함. **기존 테이블에 컬럼을 추가하지 않는다**
- [x] T005 신규 테이블 migration in api/alembic/versions/00X_add_route_previews_and_place_replacements.py
  - 영역: BE
  - 담당: jh
  - 선행: T004
  - 검증: `uv run alembic upgrade head` 후 두 테이블과 부분 unique 인덱스가 생성되고 `downgrade`로 원복된다. 기존 테이블 스키마가 바뀌지 않았음을 `alembic check` 또는 diff로 확인
- [x] T006 [P] REPL 요청·응답 스키마 in api/app/schemas/replacement.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: `contracts/replacements.openapi.yaml`의 `CreatePreviewRequest`·`RoutePreview`·`Replacement`·`ReplacementUndoResult`·`UndoableReplacement`·`ComparisonValue`를 그대로 옮긴다. `ApiModel`의 `to_camel` alias를 따르고 일정 version 필드는 `schedule_version`(직렬화 시 `scheduleVersion`)이다. `ComparisonValue`의 `before`/`after`가 `null`을 받는다
- [x] T007 [P] Android 변경 API 계층 in android/app/src/main/java/com/gilpick/replacement/ReplacementApi.kt, android/app/src/test/java/com/gilpick/replacement/ReplacementApiTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: 계약의 DTO와 오류 코드를 enum으로 옮기고 Retrofit `ReplacementService`(REPL-001~004)를 정의한다. MockWebServer로 네 경로·`Idempotency-Key` 헤더 전달·`comparison`의 `null` 값 파싱·오류 코드 분류를 확인. 서버가 아직 없어도 계약 JSON으로 검증한다
- [x] T008 Android 변경 repository in android/app/src/main/java/com/gilpick/replacement/ReplacementRepository.kt, android/app/src/test/java/com/gilpick/replacement/ReplacementRepositoryTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T007
  - 검증: F007 `DetectionRepository`와 같은 구조로 `AuthRepository.withAuthorizedCall`을 쓰고 401 갱신·replay를 재사용한다. `Idempotency-Key`는 같은 요청에 같은 값이 나오도록 내용에서 파생하고(FR-011) 재시도 시 재사용됨을 test로 고정. `ReplacementError` 11종(`ScheduleChanged`·`PreviewExpired`·`PreviewSuperseded`·`AlreadyApproved`·`AlreadyVisited`·`AlternativeUnavailable`·`DetectionNotActive`·`UndoExpired`·`FollowUpChangeExists`·`Network`·`Unexpected`) 변환 확인

---

## Phase 3: User Story 1 - 바꾸기 전에 무엇이 달라지는지 본다 (Priority: P1)

**Goal**: 후보를 골랐을 때 무엇이 달라지는지 비교를 만들어 보여 준다. 일정은 바꾸지 않는다.

**Independent Test**: 감지 결과 하나와 대체 장소 하나로 미리보기를 만들어 비교 네 항목이 오는지, 그 시점에 일정·경로·감지 결과가 전혀 바뀌지 않는지 확인한다. 승인·되돌리기 없이 검증할 수 있다.

### Tests for User Story 1

- [x] T009 [P] [US1] REPL-001·REPL-003 계약 test in api/tests/contract/test_replacement_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T006
  - 검증: 요청·응답 필드명과 타입이 `replacements.openapi.yaml`과 일치하고 `comparison` 네 항목이 모두 `{before, after}` 형태다. 오류 코드 집합(`INVALID_CANDIDATE`·`DETECTION_NOT_ACTIVE`·`ITEM_ALREADY_VISITED`·`PLACE_ALREADY_IN_SCHEDULE`·`DAY_NOT_IN_PROGRESS`·`ROUTE_PROVIDER_ERROR`·`ROUTE_PROVIDER_TIMEOUT`)이 계약과 같다
- [x] T010 [P] [US1] 미리보기 무결성 integration test in api/tests/integration/test_replacement_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: quickstart BE 1~3. 미리보기 생성 전후로 `schedule_version`·`itinerary_items.place_id`·활성 `routes`·`detections.status`가 하나도 바뀌지 않는다(FR-001, SC-002). 후보 식별자 검증 4가지(정상·다른 감지 결과·만료·`candidateId` 없이 직접 검색)와 `SUPERSEDED`·`PREVIEW_EXPIRED` 판정 포함
- [x] T011 [P] [US1] 미리보기 화면 UI test in android/app/src/androidTest/java/com/gilpick/replacement/RoutePreviewScreenTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T003
  - 검증: quickstart FE 2~3. 지도 범례가 **색이 아니라 문구로도** 기존/변경을 구분(UI-001), 장소 변경 문구·감지 이유·비교 네 항목의 이전/이후 값 표시, 나아짐·나빠짐을 색 단독이 아닌 문구나 기호로 구분(UI-002), `closesAt`이 `null`이면 `정보 없음`(FR-002), `loading` 1초 지연·`error`의 원인과 `다시 시도`·`다른 후보 보기`와 기존 일정 유지 문구(UI-004), 행동 48dp 이상(UI-008). `empty` 상태가 없는 이유를 test 주석에 남긴다

### Implementation for User Story 1

- [x] T012 [US1] 미리보기 생성 서비스 in api/app/services/replacement.py
  - 영역: BE
  - 담당: jh
  - 선행: T002, T005, T006, T009, T010
  - 검증: T009·T010 통과. 감지 결과·대상 항목·대체 장소를 검증하고(FR-003) F009 `verify_candidate_token`으로 `candidateId`를 확인한다(FR-004). **경로를 이 시점에 계산해** `route_payload`와 `comparison`을 저장한다(research 2절). 대체 장소가 `places`에 없으면 F004 `_upsert_places`와 같은 방식으로 저장한다. 같은 감지 결과의 기존 `PENDING`을 `SUPERSEDED`로 내린 뒤 삽입한다(FR-006). `expires_at = now + PREVIEW_TTL_MINUTES`
- [x] T013 [US1] 비교 항목 생성 in api/app/services/replacement.py
  - 영역: BE
  - 담당: jh
  - 선행: T012
  - 검증: data-model 2.1의 네 항목을 만든다. 기존 값은 **저장된 값**(활성 `routes`, `itinerary_items.estimated_arrival_at`)에서 읽고 새로 계산하지 않는다(research 8절). `closesAt`은 F008 `services/detection/operating_hours.py` 조회를 재사용하고, 실패하면 그 항목만 `null`로 두고 나머지를 제공한다(constitution IV). 값을 지어내지 않음을 unit test로 고정
- [x] T014 [US1] REPL-001·REPL-003 라우터 in api/app/api/v1/replacements.py, api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T012, T013
  - 검증: T009 통과. 인증과 여행 소유권을 검증하고(FR-021) `Idempotency-Key`를 F006 저장소로 처리한다(research 6절). REPL-003은 `PENDING`·`SUPERSEDED`·`REJECTED` 어디에 요청해도 `204`이고 `APPROVED`에는 `409 ALREADY_APPROVED`다. 경로 계산 실패는 `502`/`504`로 나가고 일정을 바꾸지 않는다(FR-007)
- [x] T015 [US1] 미리보기 화면 상태와 ViewModel in android/app/src/main/java/com/gilpick/replacement/PreviewUiState.kt, android/app/src/main/java/com/gilpick/replacement/PreviewViewModel.kt, android/app/src/test/java/com/gilpick/replacement/PreviewViewModelTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T008
  - 검증: data-model 4.1의 `Loading`(1초 지연)·`Error`·`Content`. `empty`는 두지 않는다. 미리보기 생성 실패가 원인별로 구분되고 `다시 시도`가 같은 요청을 같은 `Idempotency-Key`로 재전송하는지 확인
- [x] T016 [US1] 미리보기 화면 in android/app/src/main/java/com/gilpick/replacement/RoutePreviewScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T003, T011, T015
  - 검증: T011 통과. Figma `RoutePreviewScreen` 대조. `com.gilpick.ui.theme` token만 쓰고 색·간격·타이포 값을 화면 코드에 직접 쓰지 않는다. 지도는 F005 `RouteMap`을 재사용하고 기존·변경 두 경로를 함께 그린다
- [x] T017 [US1] 미리보기 navigation과 F009 연결 in android/app/src/main/java/com/gilpick/replacement/ReplacementNavigation.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: hs
  - 선행: T016, F009 T026(`AlternativeNavigation.kt`)
  - 검증: quickstart FE 1. F009가 남긴 `alternativeGraph(onSelectPlace)` 콜백을 `RoutePreviewRoute`로 연결한다(F009 `MainActivity` no-op 교체). 추천 후보 선택과 직접 검색 선택 **양쪽 모두** 같은 화면이 열리고 `SelectedAlternative`의 `candidateId` 유무가 요청에 반영되는지 androidTest로 확인. **되돌아가기(UI-003)**: 미리보기에서 `다른 후보 보기`로 나가면 REPL-003이 호출되고 **같은 감지 결과의 F009 후보 목록으로 돌아가** 다른 후보를 다시 고를 수 있는지 확인(US1 시나리오 3). `com.gilpick.alternative` 파일은 수정하지 않는다

**Checkpoint**: 사용자가 후보를 고르면 무엇이 달라지는지 볼 수 있다. 승인 없이 단독으로 동작한다.

---

## Phase 4: User Story 2 - 승인하면 일정과 경로가 함께 바뀐다 (Priority: P2)

**Goal**: 미리보기를 승인해 일정 장소·경로·이력을 하나의 transaction으로 바꾼다.

**Independent Test**: 미리보기 하나를 승인해 장소가 바뀌고 경로가 확정되고 이력이 남고 일정 version이 오르는지 확인한다. 되돌리기 없이 검증할 수 있다.

### Tests for User Story 2

- [x] T018 [P] [US2] REPL-002 계약 test in api/tests/contract/test_replacement_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T006
  - 검증: 응답 필드와 오류 코드 집합(`PREVIEW_EXPIRED`·`PREVIEW_SUPERSEDED`·`PREVIEW_REJECTED`·`ALREADY_APPROVED`·`VERSION_CONFLICT`·`ITEM_ALREADY_VISITED`·`ALTERNATIVE_UNAVAILABLE`·`DETECTION_NOT_ACTIVE`)이 계약과 같다. `routeStatus`가 `READY`만 갖는 이유(미리보기에서 계산 완료)를 test 주석에 남긴다
- [x] T019 [P] [US2] 승인 원자성·재검증·멱등 integration test in api/tests/integration/test_replacement_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: quickstart BE 4~6. 승인 후 여섯 가지(`place_id` 교체, `item_id`·`sequence`·`planned_stay_minutes`·`transport_mode_to_next` 유지, `schedule_version` +1, 새 version 활성 `routes`와 이전 `HISTORICAL`, `place_replacements` 삽입과 `undo_expires_at`, `detections` `RESOLVED`)를 모두 확인. 재검증 5가지 각각에서 **일정이 하나도 바뀌지 않음**을 함께 확인(SC-007). 같은 `Idempotency-Key` 2회 요청 시 이력 1건·version 1회 상승·같은 응답(FR-011, SC-003)
- [x] T020 [P] [US2] 승인 UI test in android/app/src/androidTest/java/com/gilpick/replacement/RoutePreviewApproveTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T003
  - 검증: quickstart FE 4. 승인 중 행동 잠금과 진행 표시, 실패 5원인(`VERSION_CONFLICT`·`PREVIEW_EXPIRED`·`ITEM_ALREADY_VISITED`·`ALTERNATIVE_UNAVAILABLE`·통신 실패)이 **서로 다른 안내와 다른 다음 행동**을 보이는지(UI-005, SC-007), 다음 행동 버튼이 48dp 이상인지

### Implementation for User Story 2

- [x] T021 [US2] 승인 서비스와 재검증 in api/app/services/replacement.py, api/tests/unit/test_replacement_rules.py
  - 영역: BE
  - 담당: jh
  - 선행: T012, T018, T019
  - 검증: T018·T019 통과. data-model 3.1의 순서를 **하나의 transaction**으로 수행하고 `trip_days`를 `FOR UPDATE`로 잠근다. **transaction 안에서 외부 provider를 호출하지 않는다**(research 2절). 재검증 5가지를 원인별 오류로 구분하고(FR-010) 하나라도 어긋나면 전체를 되돌린다. `undo_expires_at = approved_at + UNDO_WINDOW_SECONDS`
- [x] T022 [US2] REPL-002 라우터 in api/app/api/v1/replacements.py
  - 영역: BE
  - 담당: jh
  - 선행: T021
  - 검증: T018 통과. 소유권 검증과 `Idempotency-Key` 처리. 같은 키 재요청은 저장된 `response_snapshot`을 그대로 반환하고 다른 키의 재승인은 `409 ALREADY_APPROVED`다
- [x] T023 [US2] 승인 연결과 실패 안내 in android/app/src/main/java/com/gilpick/replacement/PreviewViewModel.kt, android/app/src/main/java/com/gilpick/replacement/RoutePreviewScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T016, T020
  - 검증: T020 통과. `approving` 중 행동 잠금, `approveFailure` 원인별 다음 행동 5가지(다시 만들기·다시 만들기·후보 목록으로·후보 목록으로·다시 시도) 연결. 승인 성공 시 되돌리기 정보를 들고 진행 화면으로 돌아간다

**Checkpoint**: 사용자가 앱 안에서 계획을 고칠 수 있다. 되돌리기 없이 단독으로 동작한다.

---

## Phase 5: User Story 3 - 바꾸자마자 아니다 싶으면 되돌린다 (Priority: P3)

**Goal**: 승인 후 30초 안에 장소·경로·감지 결과를 승인 전으로 복원한다.

**Independent Test**: 승인 직후 되돌리기를 실행해 장소·경로·일정 version이 복원되는지, 시간이 지나거나 후속 변경이 있으면 거절되고 그 이유가 안내되는지 확인한다.

### Tests for User Story 3

- [x] T024 [P] [US3] REPL-004 계약 test in api/tests/contract/test_replacement_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T006
  - 검증: 응답 필드(`restored`·`scheduleVersion`·`routeStatus`·`detectionRestored`)와 오류 코드(`UNDO_EXPIRED`·`FOLLOW_UP_CHANGE_EXISTS`)가 계약과 같다. `UndoableReplacement`가 PROG-001 응답에 실리는 형태도 확인
- [x] T025 [P] [US3] 되돌리기와 감지 결과 복귀 integration test in api/tests/integration/test_replacement_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: quickstart BE 7~9. 되돌리기 후 `place_id` 원복·`schedule_version` +1·승인 전 경로 재활성(SC-005), 2회 요청 시 일정 불변(FR-017), 거절 2가지에서 일정 불변(SC-006). **감지 결과 복귀 두 갈래를 모두** 확인: 충돌 없으면 `ACTIVE` 복귀·`resolved_at` null·`detectionRestored=true`, 같은 `fingerprint`의 `ACTIVE`가 이미 있으면 되돌린 쪽 `INVALIDATED`·`detectionRestored=false`이고 **`uq_detections_active_fingerprint` 위반 없이 성공**(research 5절)
- [ ] T026 [P] [US3] 되돌리기 토스트와 우선순위 UI test in android/app/src/androidTest/java/com/gilpick/progress/ReplacementUndoTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T003
  - 검증: quickstart FE 5. 장소 변경 되돌리기 문구·남은 시간·`되돌리기` 표시, 만료 후 행동만 감추고 문구는 유지, 되돌리는 중 잠금, 실패 시 원인과 **일정 편집 안내**(FR-019, UI-007), 48dp. **우선순위(UI-006a)**: 두 되돌리기가 동시에 가능할 때 겹쳐 보이지 않고 장소 변경이 먼저, 그것이 끝나면 F007 자동 확정이 이어서 보이는지(SC-008)

### Implementation for User Story 3

- [x] T027 [US3] 되돌리기 서비스와 감지 결과 복귀 in api/app/services/replacement.py
  - 영역: BE
  - 담당: jh
  - 교차 확인: ts, jy
  - 선행: T021, T024, T025
  - 검증: T024·T025 통과. data-model 3.2를 **하나의 transaction**으로 수행한다. 되돌리기 가능 3조건(미되돌림·미만료·`schedule_version = approved_schedule_version`)으로 판정하고 만료는 서버 시각으로 본다(FR-015). 승인 전 경로는 `routes`의 `HISTORICAL` 행을 복사해 쓰고 다시 계산하지 않는다. data-model 3.3의 fingerprint 충돌 분기를 구현한다. **F008 감지 결과 상태 전이를 건드리므로 F009 담당 `ts`·`jy` 교차 확인**
- [x] T028 [US3] REPL-004 라우터 in api/app/api/v1/replacements.py
  - 영역: BE
  - 담당: jh
  - 선행: T027
  - 검증: T024 통과. 소유권 검증. 이미 되돌린 변경에 다시 요청하면 `409`가 아니라 `200`으로 이미 되돌아간 상태를 반환한다(FR-017)
- [x] T029 [US3] PROG-001 응답에 되돌릴 수 있는 장소 변경 추가 in api/app/services/progress.py, api/app/schemas/progress.py
  - 영역: BE
  - 담당: jh
  - review: jy
  - 선행: T027
  - 검증: T024 통과. 되돌릴 수 있는 조건 3가지를 만족하는 `place_replacements` 행이 있으면 `undoableReplacement`로 싣고 없으면 `null`이다. F007 `undoable`과 **동시에 실릴 수 있다**. 필드가 없던 기존 응답과의 호환을 test로 고정. **F006 소유 파일이므로 `jy` review**
- [ ] T030 [US3] 진행 화면 되돌리기 상태와 우선순위 in android/app/src/main/java/com/gilpick/progress/ProgressApi.kt, android/app/src/main/java/com/gilpick/progress/ProgressUiState.kt, android/app/src/main/java/com/gilpick/progress/UndoToast.kt
  - 영역: FE
  - 담당: hs
  - review: jy
  - 선행: T003, T026, T029
  - 검증: T026 통과. `ProgressData`에 `undoableReplacement`를 기본값 `null`로 추가해 기존 응답 호환을 유지한다. data-model 4.2의 세 필드와 **표시 우선순위 판단**을 `ProgressUiState.Content`의 파생값으로 둔다. `UndoToast`에 장소 변경 문구를 더하되 같은 composable을 공유한다. **F006 소유 파일이므로 `jy` review**
- [ ] T031 [US3] 진행 화면 되돌리기 실행 in android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt
  - 영역: FE
  - 담당: hs
  - review: jy
  - 선행: T030
  - 검증: quickstart FE 5-3·5-6. `undoReplacement()`가 REPL-004를 호출하고 성공 시 진행을 다시 조회해 일정·경로·감지 대상을 함께 맞춘다. 실패 시 원인만 남기고 토스트를 유지한다. 만료·후속 변경은 재조회해 토스트가 사라지게 한다. **F006 소유 파일이므로 `jy` review**

**Checkpoint**: 잘못 바꾼 사용자가 30초 안에 원래대로 돌아갈 수 있다.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T032 [P] 소유권과 권한 test in api/tests/integration/test_replacement_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T028
  - 검증: quickstart BE 10. 다른 사용자 token으로 REPL-001~004를 호출해 모두 `403 TRIP_FORBIDDEN`이고 대상 데이터가 바뀌지 않는다(FR-021, constitution V)
- [x] T033 [P] 자동 감지 대상 연동 확인 in api/tests/integration/test_replacement_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T021
  - 검증: quickstart FE 6의 서버 쪽. 승인 후 PROG-001의 `detectionTargets`가 바뀐 장소를 따르고 없어진 장소의 대상이 빠진다(FR-013). `item_id`와 감지 종류가 유지되므로 `geofenceId`와 정책 반경은 같고 좌표가 바뀐다
- [x] T034 계약·ERD·명세 문서 동기화 in docs/design/api-spec.md, docs/design/er-schema.md, docs/planning/functional-spec.md, specs/008-variable-detection/, specs/009-alternative-places/data-model.md, specs/006-trip-progress/data-model.md
  - 영역: 통합
  - 담당: jh
  - 교차 확인: hs, ts, jy
  - 선행: T001, T027, T029
  - 검증: data-model 5절의 5건을 반영한다. 구현 중 이 Feature의 `spec.md`와 설계가 어긋나면 같은 PR에서 `spec.md`도 함께 고친다(constitution II). (1) api-spec 2절 구현 현황 REPL-001~004와 8절 REPL 절 갱신, **`itineraryVersion` → `scheduleVersion`**. (2) er-schema에 두 테이블과 API 매핑 추가. (3) F008·F009 감지 결과 상태 전이에 `RESOLVED → ACTIVE`·`RESOLVED → INVALIDATED` 추가. (4) F006 data-model에 PROG-001 `undoableReplacement`와 `ProgressUiState.Content` 확장. (5) functional-spec 5.3에 경로 계산을 미리보기 시점에 끝낸다는 순서 반영
- [x] T035 Backend 전체 검증 in api/tests/
  - 영역: BE
  - 담당: jh
  - 선행: T032, T033
  - 검증: quickstart BE 1~10 전체 수행. `uv run pytest` 전체 통과와 `uv run alembic upgrade head`·`downgrade` 확인. **REPL-001 응답 시간을 측정해 3초 이내인지 기록한다(SC-001)** — 경로 provider 호출을 포함하므로 실제 값 확인이 필요하다. 실행한 명령과 결과를 PR에 기록하고 미실행 항목은 이유를 남긴다
- [ ] T036 Android 전체 검증과 접근성·적응형·screenshot in android/app/src/androidTest/java/com/gilpick/replacement/, android/app/src/androidTest/java/com/gilpick/progress/
  - 영역: FE
  - 담당: hs
  - 선행: T017, T023, T031
  - 검증: quickstart FE 1~7 전체. `./gradlew :app:testDebugUnitTest`와 `connectedDebugAndroidTest`(`com.gilpick.replacement`·`com.gilpick.progress`) 통과. 모든 행동 48dp 이상·8dp 이상 간격(UI-008), 360dp + `font_scale 2.0`에서 비교 항목과 행동 문구 잘림 없음(UI-009), **screenshot 5장**(미리보기 `content`·`error`, 승인 실패, 승인 직후 되돌리기 가능, 되돌리기 만료)(UI-010). 확인 후 화면 설정을 원복한다
- [ ] T037 종단간 확인 in specs/010-schedule-replacement/quickstart.md
  - 영역: 통합
  - 담당: hs
  - 교차 확인: jh
  - 선행: T035, T036
  - 검증: quickstart "종단간 확인" 5단계를 로컬 API + AVD로 수행. 배너 → 후보 선택 → 미리보기 → 승인 → 되돌리기 → 재승인 후 30초 만료까지 한 흐름으로 확인하고 결과를 PR에 기록. **후보 선택부터 승인 완료까지의 소요 시간을 재어 60초 이내인지 기록한다(SC-004).** 실행하지 못한 단계는 이유를 남긴다

---

## Dependencies

### User Story 완료 순서

**Setup(T001~T003)** → **Foundational(T004~T008)** → **US1(T009~T017)** → **US2(T018~T023)** → **US3(T024~T031)** → **Polish(T032~T037)**

- US2는 US1의 미리보기 생성(T012)에 의존한다. 승인할 대상이 있어야 한다.
- US3은 US2의 승인 서비스(T021)에 의존한다. 되돌릴 변경이 있어야 한다.
- 세 story는 각각 이전 단계까지만 있으면 독립적으로 검증할 수 있다.

### 담당자별 흐름

- **BE `jh`**: T001·T002 → T004 → T005 → T006 → T009·T010 → T012 → T013 → T014 → T018·T019 → T021 → T022 → T024·T025 → T027 → T028 → T029 → T032·T033 → T034 → T035
- **FE `hs`**: T003 → T007 → T008 → T011 → T015 → T016 → (F009 T026 대기) → T017 → T020 → T023 → T026 → T030 → T031 → T036 → T037

### 부분 선행 조건

- **T017**만 F009 T026(`AlternativeNavigation.kt`·`alternativeGraph`)을 기다린다. Issue 전체를 차단하지 않고 이 task만 대기한다. 나머지 FE task는 F009의 이미 병합된 `SelectedAlternative`·화면·ViewModel만 있으면 된다.
- **T015·T016**은 서버 없이 MockWebServer와 고정 상태로 검증할 수 있다. 실서버 연동 확인은 T036·T037에서 한다.
- **T012**는 F009 `verify_candidate_token`이 필요하다. F009 Backend(ALT-001) 병합 이후에 시작한다.

## Parallel Execution

- **Setup**: T002(BE)·T003(FE)는 동시에 할 수 있다. T001은 두 사람의 교차 확인이 필요해 먼저 끝낸다.
- **Foundational**: T004·T006(BE)와 T007(FE)은 파일이 달라 동시에 가능하다. T005는 T004 뒤다.
- **각 story의 test task**: T009·T010·T011 / T018·T019·T020 / T024·T025·T026은 각각 서로 다른 파일이라 동시에 가능하다. 다만 T009·T018·T024는 같은 `test_replacement_contract.py`를 쓰므로 **작성 순서를 조율**한다. T010·T019·T025도 같은 `test_replacement_flow.py`다.
- **Polish**: T032·T033은 관심사가 달라 `[P]`지만 **같은 `test_replacement_flow.py`를 고치므로** 작성 순서를 조율한다.

**주의**: `[P]`라도 같은 파일을 동시에 고치지 않는다. `test_replacement_contract.py`(T009·T018·T024), `test_replacement_flow.py`(T010·T019·T025·T032·T033) 두 파일이 여기 해당한다.

## Implementation Strategy

### MVP 범위

**US1(T001~T017)만으로 배포 가능한 가치가 있다.** 사용자는 후보를 골랐을 때 이동 시간과 도착 시각이 어떻게 달라지는지 보고 스스로 판단할 수 있다. 일정 변경은 F004 일정 편집으로 직접 하면 된다.

### 증분 전달

1. **US1**: 비교를 볼 수 있다. 일정은 아직 앱이 바꾸지 않는다.
2. **US2**: 앱 안에서 계획을 고칠 수 있다.
3. **US3**: 잘못 고쳐도 30초 안에 되돌릴 수 있다.

### Issue 묶음 제안

`$speckit-taskstoissues`가 참고할 응집 단위다. 담당자와 영역이 같고 하나의 흐름을 완성하며 한 PR에서 review할 수 있는 크기로 묶었다.

| Issue 후보 | task | 담당 | 비고 |
|---|---|---|---|
| 계약 대조와 정책값 | T001·T002 | jh | `hs` 교차 확인 |
| Figma 부족 요소 반영 | T003 | hs | 다른 FE task의 선행 |
| 테이블·스키마 기반 | T004·T005·T006 | jh | |
| Android 변경 API 계층 | T007·T008 | hs | |
| 미리보기 생성 API | T009·T010·T012·T013·T014 | jh | |
| 미리보기 화면 | T011·T015·T016·T017 | hs | T017은 F009 T026 대기 |
| 승인 API | T018·T019·T021·T022 | jh | |
| 승인 화면 연결 | T020·T023 | hs | |
| 되돌리기 API와 감지 결과 복귀 | T024·T025·T027·T028·T029 | jh | `ts`·`jy` 교차 확인, T029는 `jy` review |
| 진행 화면 되돌리기 | T026·T030·T031 | hs | `jy` review |
| Backend 검증과 문서 동기화 | T032·T033·T034·T035 | jh | |
| Android 전체 검증 | T036 | hs | |
| 종단간 확인 | T037 | hs | `jh` 교차 확인 |
