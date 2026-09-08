# Tasks: 여행 진행

**Input**: `specs/006-trip-progress/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/progress.openapi.yaml`, `quickstart.md`

**Organization**: Backend 담당은 `jh`, Frontend Android 담당은 `jy`다(2026-09-07 합의). 테스트는 명세의 독립 검증·성공 기준과 constitution 품질 게이트를 만족하도록 구현보다 먼저 배치한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 미완료 선행 작업이 없고 수정 파일이 다른 작업
- **[Story]**: 해당 User Story 추적 표기
- 보조 메타데이터의 선행·검증 조건까지 완료해야 task 완료로 본다.

## Phase 1: Setup

**Purpose**: F006 계약을 공용 문서에 고정하고 Android 위치 의존성·Figma 추가 요소를 준비한다.

- [X] T001 F006 계약·ERD·API 명세 동기화와 교차 review in specs/006-trip-progress/contracts/progress.openapi.yaml, specs/006-trip-progress/data-model.md, docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: 통합
  - 담당: jh
  - 교차 확인: jy
  - 선행: 없음
  - 검증: PROG-001 응답(`inboundTravel`, `nextItemId`, `progressVersion`), PROG-002 요청(`progressVersion`, `currentLocation`)·`Idempotency-Key`, PROG-006 요청·응답과 오류 code(`DAY_NOT_TODAY`, `DAY_EMPTY`, `DAY_NOT_STARTED`, `INVALID_STATUS_TRANSITION`), `trip_days.progress_version`·`progress_transitions`(`idempotency_key`, `progress_version_after`)·`progress_segments`가 api-spec·er-schema에 반영되고 BE `jh`와 FE `jy`가 확인
- [x] T002 [P] Android 위치 의존성과 권한 선언 in android/app/build.gradle.kts, android/app/src/main/AndroidManifest.xml
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: `play-services-location` 추가와 `ACCESS_FINE_LOCATION`·`ACCESS_COARSE_LOCATION` 선언 후 `--offline` debug build 성공, 백그라운드 위치 권한은 선언하지 않음(F007 범위)을 PR에 기록
- [x] T003 [P] Figma `ActiveTravelScreen` 추가 요소 반영과 저장소 사본 갱신 in docs/design/figma-make/src/screens/ActiveTravelScreen.tsx, docs/design/figma-make/src/screens/TripDetailScreen.tsx
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: 도착 상태 카드(`다음 장소로 출발`), 당일 완료 상태, 상태 수정 시트(spec UI-003 상태별 행동)와 `N분 지났어요` 문구가 Figma에 추가되고 사본이 갱신됨. F007·F008·F010·F011 요소(확인 시트·경고 배너·토스트·알림 버튼)는 F006 구현에서 제외함을 기록

---

## Phase 2: Foundational

**Purpose**: 모든 User Story가 공유하는 진행 저장 구조, DTO, 단일 구간 계산, Android API 계층을 만든다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 User Story 구현을 시작하지 않는다.

- [X] T004 진행 table migration과 DB 제약 구현 in api/migrations/versions/005_create_progress_tables.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: upgrade·downgrade 왕복, `trip_days.progress_version` 기본 0, `progress_transitions` FK·`UNIQUE(trip_day_id, idempotency_key)`, `progress_segments` FK cascade·`(trip_day_id, from_item_id, to_item_id)` unique(from null 포함)를 `api/tests/integration/test_progress_migration.py`로 검증
- [X] T005 [P] 진행 ORM model과 TripDay 확장 in api/app/models/progress.py, api/app/models/itinerary.py, api/app/models/__init__.py
  - 영역: BE
  - 담당: jh
  - 선행: T004
  - 검증: model과 migration column·constraint 일치, `affected_items` JSONB, relationship·cascade를 `api/tests/unit/test_progress_model.py`에서 확인
- [X] T006 [P] 진행 schema·enum·오류 code 구현 in api/app/schemas/progress.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: OpenAPI의 `ProgressData`·`ProgressItem`·`InboundTravel`(nullable·`source`), 시작 요청 `currentLocation` 범위 validation, PATCH `status` enum 4개를 `api/tests/unit/test_progress_schema.py`에서 검증
- [X] T007 [P] 단일 구간 provider 계산 함수 노출 in api/app/services/route.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: `RouteCalculationService`의 기존 provider로 (출발 좌표, 도착 좌표, 이동수단) 한 구간을 시도당 5초·일시 오류 1회 재시도·전체 8초 deadline으로 계산해 이동시간·거리·provider만 돌려주고, F005 날짜 경로 저장에 영향이 없음을 `api/tests/unit/test_route_service.py`에 추가 검증
- [x] T008 [P] Android 진행 DTO·Retrofit service 구현 in android/app/src/main/java/com/gilpick/progress/ProgressApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: GET progress, POST start(`Idempotency-Key`, `currentLocation` nullable), PATCH status 경로·본문과 `ProgressData` 직렬화 round-trip(null ETA·`inboundTravel` null 포함)을 `android/app/src/test/java/com/gilpick/progress/ProgressApiTest.kt`에서 확인
- [x] T009 Android ProgressRepository와 오류 분류 구현 in android/app/src/main/java/com/gilpick/progress/ProgressRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008
  - 검증: 인증 refresh/replay, 네트워크 오류, `VERSION_CONFLICT`, `DAY_NOT_TODAY`, `DAY_EMPTY`, `DAY_NOT_STARTED`, `INVALID_STATUS_TRANSITION`을 `ProgressError`로 구분하고 요청별 멱등 키 생성·재시도 시 재사용을 `android/app/src/test/java/com/gilpick/progress/ProgressRepositoryTest.kt`에서 확인

**Checkpoint**: DB·DTO·단일 구간 계산·Android API 계층이 준비되어 story 작업을 시작할 수 있다.

---

## Phase 3: User Story 1 - 오늘 여행 시작과 도착 예정 시각 확인 (Priority: P1) 🎯 MVP

**Goal**: 오늘 날짜를 시작하면 시작 시각이 한 번 고정되고 첫 장소가 `이동 중`이 되며 장소별 ETA가 계산·표시된다.

**Independent Test**: 오늘 날짜·장소 3곳·READY 경로에서 시작 → 첫 장소 `EN_ROUTE`, ETA 3개가 규칙과 일치, 재시작·앱 재개에도 시작 시각 불변(quickstart BE 1~4, FE 시작 흐름).

### Tests for User Story 1

- [X] T010 [P] [US1] ETA 재계산 unit test 작성 in api/tests/unit/test_eta_service.py
  - 영역: BE
  - 담당: jh
  - 선행: T005, T006
  - 검증: data-model.md ETA 규칙(시작 위치 유무, 계획 구간·`progress_segments`·null 구간, `ARRIVED`·`COMPLETED` 기준 시각, `SKIPPED` 제외, `COMPLETED`·`SKIPPED` ETA 불변)을 fixture로 검증
- [X] T011 [P] [US1] 시작·조회 contract·integration test 작성 in api/tests/contract/test_progress_contract.py, api/tests/integration/test_progress_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T004, T006
  - 검증: quickstart BE 1~4·12(시작 성공, 멱등·동시 시작, `DAY_NOT_TODAY`·`DAY_EMPTY`·403/404, 위치 유효·무효, provider 실패 시 시작 성공·ETA null, F004 저장·F005 retry 후 ETA 갱신)
- [x] T012 [P] [US1] Android 시작 흐름 ViewModel·위치 취득 test 작성 in android/app/src/test/java/com/gilpick/trip/TripDetailViewModelTest.kt, android/app/src/test/java/com/gilpick/progress/CurrentLocationProviderTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009
  - 검증: 기간 밖·장소 0곳·시작됨 버튼 상태, 권한 거부·timeout·정확도 150m·5분 경과 위치는 null로 시작, 성공 시 navigation event, 실패 시 오류 상태

### Implementation for User Story 1

- [X] T013 [US1] ETA 재계산 service 구현 in api/app/services/eta.py
  - 영역: BE
  - 담당: jh
  - 선행: T010
  - 검증: T010 통과, 활성 route payload·`progress_segments` 조회를 한 함수로 캡슐화하고 transaction 안에서 호출 가능
- [X] T014 [US1] 시작 service와 진행 현황 조회 구현 in api/app/services/progress.py
  - 영역: BE
  - 담당: jh
  - 선행: T007, T013
  - 검증: 오늘(KST)·장소 수 검증, `FOR UPDATE`로 동시 시작 직렬화, 이미 시작이면 저장값 반환, 위치 유효성(100m·2분) 서버 재검증, `START` transition·`progress_version+1` 커밋 후 transaction 밖 도보 구간 계산 → `progress_segments` 저장·ETA 재계산, PROG-001 `inboundTravel`·`nextItemId` 파생. T011 통과
- [X] T015 [US1] 진행 router 등록(GET progress, POST start) in api/app/api/v1/progress.py, api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T014
  - 검증: 인증·소유권 dependency 재사용, `Idempotency-Key` header 필수, request ID·trip/day·transition_type log(좌표 미기록), T011 contract 통과
- [X] T016 [US1] F004 저장·F005 경로 확정 뒤 ETA 재계산 연결 in api/app/services/itinerary.py, api/app/services/route.py
  - 영역: BE
  - 담당: jh
  - 선행: T013
  - 검증: 진행 중(`IN_PROGRESS`) 날짜의 `save_day` 성공과 route READY 활성화 뒤 `eta` 재계산이 호출되고, `NOT_STARTED` 날짜에는 호출하지 않음을 `api/tests/integration/test_progress_flow.py`·`test_itinerary_flow.py`에서 확인
- [x] T017 [US1] 1회 현재 위치 취득 구현 in android/app/src/main/java/com/gilpick/progress/CurrentLocationProvider.kt
  - 영역: FE
  - 담당: jy
  - 선행: T002, T012
  - 검증: `FusedLocationProviderClient.getCurrentLocation` 10초 timeout, 정확도 100m 이하·2분 이내만 반환, 권한 없음·실패 시 null, test 시드 인터페이스 제공. T012 통과
- [x] T018 [US1] 여행 상세 시작 버튼과 startToday 흐름 구현 in android/app/src/main/java/com/gilpick/trip/TripDetailViewModel.kt, android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009, T017
  - 검증: 오늘 날짜 PROG-001 조회로 `오늘 여행 시작`/`여행 진행 화면으로`/비활성+`오늘은 여행 날짜가 아닙니다`/`장소 추가` 안내 분기, `rememberLauncherForActivityResult` 권한 요청 후 위치 취득 → start → navigation. 카운트다운 미구현 기록. `android/app/src/androidTest/java/com/gilpick/trip/TripDetailScreenTest.kt` 보강
- [x] T019 [US1] ProgressUiState·ProgressViewModel 조회 구현 in android/app/src/main/java/com/gilpick/progress/ProgressUiState.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009
  - 검증: F004 overview + 오늘 PROG-001 병렬 조회로 `Loading`/`Empty`/`Error`/`Content`, 1초 초과 시만 loading, `onResume` 재조회, 남은·지난 시간 1분 갱신을 `android/app/src/test/java/com/gilpick/progress/ProgressViewModelTest.kt`에서 확인
- [x] T020 [US1] ActiveTravelScreen 기본 구조와 네 상태 구현 in android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/progress/ProgressLabels.kt, android/app/src/main/res/values/strings.xml
  - 영역: FE
  - 담당: jy
  - 선행: T003, T019
  - 검증: header(`여행 중`, `N일차 · x/y 완료`, 여행명), 날짜 진행 표시, 다음 장소 카드(장소명·예상 도착·남은 시간·이전 장소 이동수단·시간·거리, `정보 없음`), 지도 자리(F005 `RouteMap` 재사용), 일정 목록(상태 문구+아이콘, 실제 시각/ETA), `장소 추가`, loading/empty/error. Figma 정본 대조, 48dp·색 단독 금지. `android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenTest.kt` 기본 케이스
- [x] T021 [US1] 진행 navigation과 MainActivity 연결 in android/app/src/main/java/com/gilpick/progress/ProgressNavigation.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T018, T020
  - 검증: `ActiveTravelRoute(tripId)`, `progressGraph(navController, onSessionExpired, repository, map)` test 시드, 여행 상세 → 진행 화면 이동을 `android/app/src/androidTest/java/com/gilpick/progress/ProgressNavigationTest.kt`에서 확인

**Checkpoint**: 시작·ETA 조회가 BE·FE에서 독립 동작한다(전환 버튼은 아직 동작하지 않아도 됨).

---

## Phase 4: User Story 2 - 수동 도착·출발·건너뛰기로 하루 진행 (Priority: P1)

**Goal**: 버튼만으로 도착 → 출발 → 건너뛰기 → 마지막 도착까지 진행하고 당일 완료에 도달한다.

**Independent Test**: `도착했어요 → 다음 장소로 출발 → 건너뛰기 → 도착했어요`로 상태 `도착→완료→건너뜀→도착`, ETA 재계산, 당일 완료 표시(quickstart BE 5~8·10·11, FE 진행 화면·당일 완료).

### Tests for User Story 2

- [X] T022 [P] [US2] 전환 파생 규칙·불변식 unit test 작성 in api/tests/unit/test_progress_service.py
  - 영역: BE
  - 담당: jh
  - 선행: T005, T006
  - 검증: research 결정 3 표의 `ARRIVED`·`COMPLETED`·`SKIPPED` 행, `EN_ROUTE`·`ARRIVED` 각 ≤1, 남은 장소 0이면 당일 완료·`detection_active=false`, 허용되지 않는 조합 `INVALID_STATUS_TRANSITION`, 실제 시각 저장
- [X] T023 [P] [US2] 상태 전환 contract·integration test 작성 in api/tests/contract/test_progress_contract.py, api/tests/integration/test_progress_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T004, T006
  - 검증: quickstart BE 5~8·10·11(도착·출발·건너뛰기 ETA, 건너뛰기 `progress_segments` 생성·provider 실패 시 ETA null·전환 성공, 당일 완료 두 경로, `DAY_NOT_STARTED`·`VERSION_CONFLICT`, 같은 키 재전송 최초 결과, transition 기록)
- [x] T024 [P] [US2] ProgressViewModel 전환 test 작성 in android/app/src/test/java/com/gilpick/progress/ProgressViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T019
  - 검증: 행동→목표 상태 매핑, 요청 중 `pendingAction`·버튼 비활성·내용 유지, 성공 시 응답으로 교체, 실패 시 상태 유지+오류, `VERSION_CONFLICT` 재조회
- [x] T025 [P] [US2] 진행 화면 전환·완료 UI test 작성 in android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T020
  - 검증: `이동 중` 카드의 `도착했어요`·`건너뛰기`, `도착` 카드의 `다음 장소로 출발`, `N분 지났어요`, 당일 완료 표시와 출발 행동 부재, 요청 중 비활성

### Implementation for User Story 2

- [X] T026 [US2] 도착·출발·건너뛰기 전환 service 구현 in api/app/services/progress.py
  - 영역: BE
  - 담당: jh
  - 선행: T014, T022
  - 검증: 항목→날짜→여행 소유권, `DAY_NOT_STARTED`, `Idempotency-Key` 기존 기록 반환, `progress_version` 비교, 파생 변경·실제 시각·당일 완료·transition(`affected_items` 전부)·`progress_version+1`을 한 transaction으로 적용. T022 통과
- [X] T027 [US2] 건너뛰기 인접 구간 계산 연결 in api/app/services/progress.py
  - 영역: BE
  - 담당: jh
  - 선행: T007, T026
  - 검증: 커밋 후 transaction 밖에서 `이전 장소 transport_mode_to_next`로 단일 구간 계산 → `progress_segments` upsert → ETA 재계산. 이미 있는 쌍은 재호출하지 않음. 실패 시 ETA null·전환 성공. T023 통과
- [X] T028 [US2] 상태 전환 endpoint 등록 in api/app/api/v1/progress.py
  - 영역: BE
  - 담당: jh
  - 선행: T027
  - 검증: `PATCH /itinerary-items/{itemId}/status`, `Idempotency-Key` 필수, 오류 code 매핑, T023 contract 통과
- [x] T029 [US2] ProgressViewModel 전환 행동 구현 in android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt, android/app/src/main/java/com/gilpick/progress/ProgressUiState.kt
  - 영역: FE
  - 담당: jy
  - 선행: T024
  - 검증: `arrive`·`depart`·`skip` 행동, `pendingAction`, 응답 교체, 오류·409 처리. T024 통과
- [x] T030 [US2] 다음 장소 카드 세 모드와 당일 완료 표시 구현 in android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/progress/ProgressLabels.kt
  - 영역: FE
  - 담당: jy
  - 선행: T029, T025
  - 검증: `EN_ROUTE`(도착했어요·건너뛰기)/`ARRIVED`(다음 장소로 출발, 마지막이면 없음)/완료 모드, `N분 남았어요`·`N분 지났어요`, 요청 중 비활성, Figma 정본 대조. T025 통과
- [x] T031 [US2] RouteMap 시작 위치·상태별 marker와 경로 화면 진행 상태 표시 in android/app/src/main/java/com/gilpick/route/RouteMap.kt, android/app/src/main/java/com/gilpick/route/RouteUiState.kt, android/app/src/main/java/com/gilpick/route/RouteViewModel.kt, android/app/src/main/java/com/gilpick/route/DayRouteScreen.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T020
  - 검증: marker 모델에 상태 필드 추가(완료 체크·이동 중·예정 숫자, 시작 `현위치`), 시작된 날짜의 `DayRouteScreen`은 PROG-001을 함께 조회해 marker·구간 목록에 `완료`·`이동 중`·`건너뜀`을 문구+아이콘으로 구분(F005 UI-010의 F006 자리 채움), 시작 전 날짜는 기존 계획 표시 유지, F005 기존 test 회귀 없음. `android/app/src/androidTest/java/com/gilpick/route/DayRouteScreenTest.kt` 보강

**Checkpoint**: 하루 전체를 수동 버튼만으로 진행하고 완료할 수 있다.

---

## Phase 5: User Story 3 - 상태 수정과 취소 (Priority: P2)

**Goal**: 완료 취소·건너뛰기 취소·`도착으로 변경`으로 진행 위치를 바로잡고 완료된 날짜를 복귀시킨다.

**Independent Test**: 완료 취소 → 뒤 장소 `예정` 초기화, 건너뛰기 취소 → `예정`, 예정 장소 `도착으로 변경` → 앞 `도착` 자동 완료·뒤 초기화, 완료 날짜 복귀(quickstart BE 9, FE 상태 수정 시트).

### Tests for User Story 3

- [ ] T032 [P] [US3] 상태 수정 unit·integration test 작성 in api/tests/unit/test_progress_service.py, api/tests/integration/test_progress_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T026
  - 검증: `COMPLETED→ARRIVED`(뒤 `PLANNED`, 완료 날짜 `IN_PROGRESS` 복귀·`completed_at` null·`detection_active=true`), `SKIPPED→PLANNED`(`EN_ROUTE`·`ARRIVED` 없으면 첫 `PLANNED`→`EN_ROUTE`), 임의 `PLANNED`→`ARRIVED`(앞 `ARRIVED`→`COMPLETED`, 앞 `EN_ROUTE`→`PLANNED`, 뒤 초기화), 도착 확정 시 ETA 재계산, `actual_arrived_at` 초기화
- [x] T033 [P] [US3] 상태 수정 시트 UI·screenshot test 작성 in android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenTest.kt, android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenshotTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T020
  - 검증: 장소 행 탭 → 상태별 행동만 표시(UI-003 표), 오늘 아닌 날짜·시작 전에는 시트 없음, 시트 내용 inline 렌더 screenshot

### Implementation for User Story 3

- [ ] T034 [US3] 완료 취소·건너뛰기 취소·도착으로 변경·날짜 복귀 규칙 구현 in api/app/services/progress.py
  - 영역: BE
  - 담당: jh
  - 선행: T032
  - 검증: research 결정 3 표의 나머지 행과 FR-011·FR-014, transition_type `UNDO_COMPLETE`·`UNDO_SKIP`·`ARRIVE`, T032 통과
- [x] T035 [US3] 상태 수정 시트와 목록 행 연결 구현 in android/app/src/main/java/com/gilpick/progress/StatusSheet.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T030, T033
  - 검증: `ModalBottomSheet`로 상태별 행동, `완료 취소`→`ARRIVED`·`건너뛰기 취소`→`PLANNED`·`도착으로 변경`→`ARRIVED`·`건너뛰기`→`SKIPPED` 매핑, 완료 날짜에서도 시트 동작, T033 통과

**Checkpoint**: 오조작을 상태 수정으로 복구할 수 있고 완료 날짜가 복귀한다.

---

## Phase 6: User Story 4 - 진행 화면에서 다른 날짜 확인과 일정 연결 (Priority: P3)

**Goal**: 다른 날짜 조회, `오늘로 돌아가기`, `장소 추가`(F004), `경로 보기`(F005) 연결.

**Independent Test**: 5일 여행 2일차 진행 중 1·3일차 선택 시 `지난/예정 일정`과 행동 부재, 복귀; `장소 추가` 후 돌아오면 새 장소 `예정`·ETA 갱신(quickstart FE 다른 날짜, BE 12).

### Tests for User Story 4

- [x] T036 [P] [US4] 날짜 전환·화면 연결 test 작성 in android/app/src/androidTest/java/com/gilpick/progress/ProgressNavigationTest.kt, android/app/src/test/java/com/gilpick/progress/ProgressViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T021
  - 검증: `viewingDate` 전환 시 카드·행동·시트 부재와 `N일차 · 지난/예정 일정`·`오늘로 돌아가기`, `장소 추가`→`ItineraryEditRoute(date=오늘, openSearch=true)`, `경로 보기`→`DayRouteRoute`, 편집 복귀 후 재조회

### Implementation for User Story 4

- [x] T037 [US4] 다른 날짜 조회와 F004·F005 연결 구현 in android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt, android/app/src/main/java/com/gilpick/progress/ProgressNavigation.kt
  - 영역: FE
  - 담당: jy
  - 선행: T036
  - 검증: 날짜 점 선택·`오늘로 돌아가기`, 오늘이 아니면 카드·행동·시트 미표시, `장소 추가`·`경로 보기` navigation, 복귀 시 재조회. T036 통과. F005 경로 화면의 상태 구분(spec US4 시나리오 3)은 T031 marker 확장으로 충족됨을 확인

**Checkpoint**: 진행 화면이 F004·F005와 왕복 연결된다.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T038 [P] Backend 문서·설정 최종 일치와 전체 자동 검증 기록 in docs/design/api-spec.md, docs/design/er-schema.md, api/.env.example, specs/006-trip-progress/quickstart.md
  - 영역: BE
  - 담당: jh
  - 선행: T028, T034
  - 검증: 구현된 응답·오류 code·table이 계약·ERD와 일치, `uv run pytest tests/unit tests/contract tests/integration` 결과와 미실행 항목을 quickstart에 기록, 시작·전환 응답 시간(위치 없음 2초, 구간 계산 포함 10초 이내, SC-001)을 integration test 또는 로컬 측정으로 기록, log에 좌표·key 없음
- [ ] T039 [P] Android 접근성·시각·screenshot 최종 검증 in android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenshotTest.kt, specs/006-trip-progress/quickstart.md
  - 영역: FE
  - 담당: jy
  - 선행: T035, T037
  - 검증: 시작 전·이동 중·도착·완료·건너뜀 포함·당일 완료·다른 날짜 + loading/empty/error를 360dp·최대 글자 배율로 screenshot, 48dp·8dp·색 단독 금지 확인, unit·connected 결과 기록
- [ ] T040 F006 종단간 검증과 Feature 상태 갱신 in specs/006-trip-progress/quickstart.md, docs/planning/mvp-features.md
  - 영역: 통합
  - 담당: jy
  - 교차 확인: jh
  - 선행: T038, T039
  - 검증: `gilpick_api36_play` + 로컬 API에서 시작(위치 있음/권한 거부)·도착·출발·건너뛰기·상태 수정·당일 완료·앱 재시작 복원 흐름을 실제로 수행하고 결과·screenshot을 기록, `mvp-features.md` F006 `VERIFY`

---

## Dependencies & Execution Order

### Phase Dependencies

```text
Setup(T001~T003)
  → Foundational(T004~T009)
  → US1(T010~T021)
  → US2(T022~T031)
  → US3(T032~T035)
  → US4(T036~T037)
  → Polish(T038~T040)
```

### User Story Dependencies

- **US1**: Foundational 완료 후 시작. BE 시작·ETA·조회(T013~T016)가 FE 화면 실데이터 검증의 선행 조건이지만 FE T017~T021은 MockWebServer로 먼저 진행할 수 있다.
- **US2**: BE T026~T028과 FE T029~T031은 각각 US1의 service·ViewModel에 의존한다. FE 실서버 검증은 T028 뒤.
- **US3**: US2의 전환 service·카드 UI 위에 규칙과 시트를 더한다.
- **US4**: FE만 있으며 US1 navigation(T021) 뒤 언제든 진행 가능하다.

### Parallel Opportunities

- T002·T003은 서로 다른 파일이라 병렬 가능하고 T003은 T001을 기다리지 않는다.
- T005·T006·T007·T008은 각 선행 조건 뒤 소유 파일이 달라 병렬 가능하다.
- US1의 T010·T011·T012, US2의 T022~T025, US3의 T032·T033은 BE·FE test 파일이 달라 병렬 작성할 수 있다.
- 같은 `services/progress.py`, `ProgressViewModel.kt`, `ActiveTravelScreen.kt`를 수정하는 task끼리는 병렬 처리하지 않는다.

## Parallel Examples

### User Story 1

```text
BE jh: T010 ETA test / T011 contract·integration test → T013 → T014 → T015 → T016
FE jy: T012 시작 흐름 test → T017 → T018 ; T019 → T020 → T021
```

### User Story 2

```text
BE jh: T022 / T023 → T026 → T027 → T028
FE jy: T024 / T025 → T029 → T030 ; T031
```

### User Story 3·4

```text
BE jh: T032 → T034
FE jy: T033 → T035 ; T036 → T037
```

## Implementation Strategy

### MVP First

1. Setup·Foundational로 계약, 저장 구조, 단일 구간 계산, Android API 계층을 고정한다.
2. US1로 시작·ETA·조회를 BE·FE에서 끝내 진행 화면을 실데이터로 띄운다.
3. US2로 수동 전환과 당일 완료를 완성하면 F006 핵심 가치가 성립한다.
4. US3 상태 수정, US4 연결, Polish 검증 순으로 마무리한다.

### Issue Grouping Guidance

- BE `jh`: (a) T001·T004~T007 기반, (b) T010·T011·T013~T016 시작·ETA·조회, (c) T022·T023·T026~T028 전환, (d) T032·T034 상태 수정, (e) T038 검증
- FE `jy`: (a) T002·T003·T008·T009 기반, (b) T012·T017·T018 시작 흐름, (c) T019~T021 진행 화면 조회, (d) T024·T025·T029~T031 전환 UI, (e) T033·T035 상태 수정 시트, (f) T036·T037 연결, (g) T039 검증
- 통합: T001은 `jh` 주도·`jy` 교차 확인, T040은 `jy` 주도·`jh` 교차 확인

## Requirements Traceability

| 요구사항 | 담당 task |
|---|---|
| FR-001·FR-002·FR-003 시작 조건·시각 고정·첫 장소 이동 중 | T011, T014, T015 |
| FR-004·FR-004a·FR-005 ETA 규칙·미계획 구간·`정보 없음` | T007, T010, T013, T014, T027 |
| FR-004b·FR-020 1회 위치·권한과 무관한 수동 진행 | T002, T012, T017, T018, T040 |
| FR-006~FR-009 상태 흐름·도착·출발·건너뛰기 | T022, T023, T026, T027, T028, T029, T030 |
| FR-010·FR-011 완료·건너뛰기 취소, 도착으로 변경 | T032, T034, T035 |
| FR-012 `이동 중`·`도착` 각 최대 1, 다음 장소 규칙 | T022, T026, T034 |
| FR-013·FR-014 당일 완료·복귀 | T022, T026, T032, T034, T030 |
| FR-015 F004 저장·F005 경로 뒤 ETA 재계산 | T016 |
| FR-016 소유권·시작 전·기간 밖 거부 | T011, T023 |
| FR-017 멱등·version | T004, T009, T023, T026 |
| FR-018 전환 기록 | T004, T005, T026 |
| FR-019 시작 전 감지 없음·`detection_active` | T014, T022 |
| FR-021 서버 기준 상태·재조회 | T019, T037, T040 |
| FR-022 범위 제외 | T003 기록, Notes |
| UI-001·UI-004·UI-008 화면 구조·목록·네 상태 | T003, T020 |
| UI-002·UI-006 카드 세 모드·경과 표시·당일 완료 | T025, T030 |
| UI-003 상태 수정 시트 | T003, T033, T035 |
| UI-005 다른 날짜 조회 | T036, T037 |
| UI-007 여행 상세 시작 버튼 | T012, T018 |
| UI-009·UI-010·UI-012 접근성·360dp·screenshot | T039 |
| UI-011 지도·시작 위치·상태 marker | T031 |
| SC-001 응답 시간 | T038 |
| SC-002 시작 시각 불변 | T011 |
| SC-003 권한 거부 기기 전 흐름 | T040 |
| SC-004 카드·목록·경로 화면 일치 | T025, T031 |
| SC-005 중복 요청 기록 1건 | T023 |
| SC-006 체류 변경 시 이후 ETA만 변경 | T010, T016 |
| SC-007 360dp·글자 배율 | T039 |

## Notes

- FE 실서버 검증이 필요한 task는 대응 BE endpoint(T015·T028) merge 뒤 수행하고, 그 전에는 MockWebServer·fixture로 진행한다.
- Figma 3개 추가 요소(T003)가 확정되기 전에는 T020·T030·T035의 모양을 확정하지 않는다.
- F007이 `progress_transitions`에 후보·자동 확정 상태를 추가할 예정이므로 F006 enum을 닫아 두지 않는다(varchar 유지).
- 구현·검증 완료 전 `mvp-features.md` 상태를 `READY` 이후로 올리지 않는다. 문서 PR에서 `READY`, 첫 구현 PR에서 `IN_PROGRESS`, T040에서 `VERIFY`.
