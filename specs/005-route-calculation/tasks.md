# Tasks: 경로 계산

**Input**: `specs/005-route-calculation/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Organization**: Backend 담당은 `jh`, Frontend Android 담당은 `jy`다. 테스트는 명세의 독립 검증·성공 기준과 constitution 품질 게이트를 만족하도록 구현보다 먼저 배치한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 미완료 선행 작업이 없고 수정 파일이 다른 작업
- **[Story]**: 해당 User Story 추적 표기
- 보조 메타데이터의 선행·검증 조건까지 완료해야 task 완료로 본다.

## Phase 1: Setup

**Purpose**: F004 경계와 F005 계약을 교차 확인하고 provider·지도 SDK 설정 기반을 준비한다.

- [ ] T001 Backend·Android 구현 전 F005 계약과 공용 문서 동기화·교차 review in specs/005-route-calculation/contracts/route.openapi.yaml, specs/005-route-calculation/data-model.md, docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: 통합
  - 담당: jh
  - 교차 확인: jy
  - 선행: F004 일정 저장·조회 계약 확정
  - 검증: 일정 PUT 성공 envelope의 `routeStatus`·`route`, ROUTE 조회·실패 전용 retry와 F006 재계산 분리, `scheduleVersion`, 오류 code, geometry·attribution·nullable 규칙을 공용 API·ERD에 먼저 반영하고 BE `jh`와 FE `jy`가 확인
- [ ] T002 [P] TMAP·ODsay 설정값과 secret 검증 추가 in api/app/core/config.py, api/.env.example
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: key 원문을 log하지 않고 호출 timeout 5초·전체 deadline 10초·동시성 상한의 기본값과 양수 validation을 `api/tests/unit/test_config.py`에서 확인
- [ ] T003 [P] Naver Maps Android SDK와 local secret 주입 설정 in android/app/build.gradle.kts, android/app/src/main/AndroidManifest.xml, android/gradle.properties
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: client ID가 source에 고정되지 않고 debug build·manifest merge가 성공하며 적용 SDK version과 공식 요구사항을 PR에 기록
- [ ] T004 [P] F005 계획 상태 디자인 차이 확인 in docs/design/figma-make/src/screens/DayRouteScreen.tsx, docs/design/figma-make/src/screens/TripDetailScreen.tsx, docs/design/ui-guidelines.md
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: F006 전용 현재 위치·진행 상태·도착예정시각과 정상 경로 수동 재계산 버튼을 F005에서 제외하고, loading·empty·error·content 및 실패 retry 표현의 정본 위치를 기록; 시각 변경이 필요하면 Feature Owner와 합의 후 Figma Make 사본과 가이드를 함께 갱신

---

## Phase 2: Foundational

**Purpose**: 모든 User Story가 공유하는 경로 저장 구조, provider adapter, API·Android DTO를 만든다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 User Story 구현을 시작하지 않는다.

- [ ] T005 Route table migration과 DB 불변 조건 구현 in api/migrations/versions/004_create_route_table.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: upgrade·downgrade 왕복 및 `trip_day_id` FK, `(trip_day_id, schedule_version)` unique, 현재 경로 partial unique index, READY·FAILED 필드 조건을 `api/tests/integration/test_route_migration.py`로 검증
- [ ] T006 [P] Route ORM model과 itinerary relationship 구현 in api/app/models/route.py, api/app/models/itinerary.py, api/app/models/__init__.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: model과 migration column·constraint 일치, cascade·relationship, 한 날짜 현재 경로 최대 하나를 `api/tests/unit/test_route_model.py`에서 확인
- [ ] T007 [P] Route schema·enum·envelope와 F004 DayItinerary route 확장 in api/app/schemas/route.py, api/app/schemas/itinerary.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: OpenAPI의 READY/FAILED/NOT_CALCULATED, segment 순서·수단·초·미터·GeoJSON·attribution, 1개 장소 0값, 오류 code를 `api/tests/unit/test_route_schema.py`에서 검증
- [ ] T008 [P] TMAP·ODsay 공통 provider protocol과 정규화 결과 model 구현 in api/app/clients/route_provider.py
  - 영역: BE
  - 담당: jh
  - 선행: T001, T002
  - 검증: provider SDK/원문 type이 service·API schema로 누출되지 않고 후보 하나·유효 geometry·비음수 시간/거리 validation을 unit test 가능하게 정의
- [ ] T009 [P] Android Route DTO·Retrofit service 구현 in android/app/src/main/java/com/gilpick/route/RouteApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: GET route와 POST retry 경로·`scheduleVersion`, 세 상태, geometry·failure 직렬화 round-trip을 `android/app/src/test/java/com/gilpick/route/RouteApiTest.kt`에서 확인
- [ ] T010 Android RouteRepository와 오류 분류 구현 in android/app/src/main/java/com/gilpick/route/RouteRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009
  - 검증: 인증 refresh/replay, 네트워크 오류, VERSION_CONFLICT, ROUTE_NOT_FAILED, provider 최종 실패를 `android/app/src/test/java/com/gilpick/route/RouteRepositoryTest.kt`에서 구분

**Checkpoint**: DB·provider 추상화·양쪽 DTO가 준비되어 story 작업을 시작할 수 있다.

---

## Phase 3: User Story 1 - 저장한 일정의 경로 계산 (Priority: P1) 🎯 MVP

**Goal**: 일정 저장 직후 저장된 순서·이동수단으로 모든 구간을 계산하고 현재 일정 version의 성공·실패 상태를 보존한다.

**Independent Test**: 서로 다른 이동수단의 장소 3개를 저장하여 두 구간과 합계가 일치하는지 확인하고, 한 구간 최종 실패에도 일정 저장과 항목이 유지되는지 검증한다.

### Tests for User Story 1

- [ ] T011 [P] [US1] TMAP client unit test와 provider fixture 작성 in api/tests/unit/test_tmap_client.py, api/tests/fixtures/tmap/
  - 영역: BE
  - 담당: jh
  - 선행: T008
  - 검증: 도보·자동차 요청 mapping, 기본 추천 후보 하나, WGS84 geometry, 누락·음수·순서 불일치 거부, timeout·429·5xx retryable 분류, 4xx 미재시도
- [ ] T012 [P] [US1] ODsay client unit test와 provider fixture 작성 in api/tests/unit/test_odsay_client.py, api/tests/fixtures/odsay/
  - 영역: BE
  - 담당: jh
  - 선행: T008
  - 검증: 대중교통 검색 기본 추천 후보와 `mapObj` 형상 조회, 두 호출의 한 구간 deadline 공유, 무경로·무효 형상·timeout·429·5xx 분류
- [ ] T013 [P] [US1] route calculation service unit test 작성 in api/tests/unit/test_route_service.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T007, T008
  - 검증: 0개 NOT_CALCULATED, 1개 READY/0초/0m·외부 미호출, 최대 9구간 제한 동시성, 전체 10초 deadline, 일시 오류 1회 retry, 한 구간 실패 시 전체 FAILED, 합계·순서·수단, version 변경 결과 폐기
- [ ] T014 [P] [US1] 일정 저장 자동 계산 contract·integration test 작성 in api/tests/contract/test_route_contract.py, api/tests/integration/test_route_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T007
  - 검증: 일정 PUT의 200/201 성공을 유지하면서 READY 또는 FAILED 반환, 저장 transaction 보존, GET 재조회 일치, 타인 403·삭제/기간 밖 404, 이전 경로 비활성화, 현재 경로 하나
- [ ] T015 [P] [US1] 일정 저장 후 Android 이동·실패 상태 ViewModel test 작성 in android/app/src/test/java/com/gilpick/trip/TripDetailViewModelTest.kt, android/app/src/test/java/com/gilpick/itinerary/ItineraryEditViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T010, F004 Android 일정 ViewModel 완료
  - 검증: 저장 중 초안 유지, READY·FAILED 모두 여행 상세 이동, FAILED일 때 일정 content 유지와 경로 영역 오류 분리, version이 다른 이전 경로 미표시

### Implementation for User Story 1

- [ ] T016 [US1] TMAP 도보·자동차 adapter 구현 in api/app/clients/tmap.py
  - 영역: BE
  - 담당: jh
  - 선행: T002, T008, T011
  - 검증: T011 통과, key·좌표 원문 미기록, 제공자 기본 추천 경로만 정규화
- [ ] T017 [US1] ODsay 대중교통 검색·형상 adapter 구현 in api/app/clients/odsay.py
  - 영역: BE
  - 담당: jh
  - 선행: T002, T008, T012
  - 검증: T012 통과, search와 geometry 호출이 동일 deadline을 공유하고 후보 비교·정렬 없음
- [ ] T018 [US1] 구간 orchestration·deadline·상태 전이 service 구현 in api/app/services/route.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T007, T013, T016, T017
  - 검증: T013 통과, 외부 호출 중 DB transaction 없음, 완료 시 version 재검증, request ID·provider·attempt·latency·결과 code log와 좌표·key 제외
- [ ] T019 [US1] 일정 저장 service에 자동 경로 계산과 실패 격리 연결 in api/app/services/itinerary.py, api/app/api/v1/itinerary.py
  - 영역: BE
  - 담당: jh
  - 선행: F004 일정 저장 구현, T014, T018
  - 검증: T014 통과, 입력 변화가 있을 때만 재계산, 일정 먼저 commit, 경로 실패가 일정 성공 envelope를 실패로 바꾸지 않음
- [ ] T020 [US1] 날짜별 경로 조회 endpoint와 router 등록 구현 in api/app/api/v1/route.py, api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T014, T018
  - 검증: 현재 일정 version만 READY/FAILED로 반환하고 0개 날짜는 NOT_CALCULATED, 인증·소유권·날짜 오류와 envelope가 계약 test를 통과
- [ ] T021 [US1] Android 일정 저장 응답과 여행 상세 경로 상태 연결 in android/app/src/main/java/com/gilpick/itinerary/ItineraryApi.kt, android/app/src/main/java/com/gilpick/itinerary/ItineraryEditViewModel.kt, android/app/src/main/java/com/gilpick/trip/TripDetailViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T010, T015, T019
  - 검증: T015 통과, 저장 성공 뒤 READY·FAILED 모두 상세로 이동하고 기존 일정 content와 경로 상태를 별도 StateFlow로 유지

**Checkpoint**: 지도 없이도 일정 저장→경로 계산→상태·구간 조회가 독립적으로 동작한다.

---

## Phase 4: User Story 2 - 지도에서 날짜별 경로 확인 (Priority: P2)

**Goal**: 날짜별 경로의 marker·polyline과 동일한 구간 정보를 접근 가능한 목록으로 확인한다.

**Independent Test**: READY fixture로 경로 화면을 열어 지도와 목록의 장소·구간 순서와 값이 같고 loading·empty·error·content가 전환되는지 검증한다.

### Tests for User Story 2

- [ ] T022 [P] [US2] RouteViewModel 상태·조회 test 작성 in android/app/src/test/java/com/gilpick/route/RouteViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T010
  - 검증: 1초 loading 지연, 0개 Empty, 1개 Content/0값, READY Content, FAILED·network Error, stale version 미표시, retry 후 동일 화면 갱신
- [ ] T023 [P] [US2] 날짜 경로 화면 Compose UI test 작성 in android/app/src/androidTest/java/com/gilpick/route/DayRouteScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T004
  - 검증: 네 UI 상태, marker와 목록 순서, 이동수단 아이콘+문구, 시간·거리·attribution, 정상 content 재계산 버튼 부재, 48dp·semantics·360dp·font scale 최대
- [ ] T024 [P] [US2] 날짜 경로 screenshot test 작성 in android/app/src/androidTest/java/com/gilpick/route/DayRouteScreenshotTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T004
  - 검증: Figma `DayRouteScreen` 기준 일반 phone·360dp·최대 글자 배율의 loading/empty/error/content와 혼합 이동수단 fixture 비교

### Implementation for User Story 2

- [ ] T025 [US2] RouteViewModel과 immutable map overlay model 구현 in android/app/src/main/java/com/gilpick/route/RouteViewModel.kt, android/app/src/main/java/com/gilpick/route/RouteUiState.kt
  - 영역: FE
  - 담당: jy
  - 선행: T022
  - 검증: T022 통과, ViewModel에 Naver SDK 객체를 보관하지 않고 `StateFlow`와 일회성 navigation/event를 분리
- [ ] T026 [US2] lifecycle-aware Naver RouteMap adapter 구현 in android/app/src/main/java/com/gilpick/route/RouteMap.kt
  - 영역: FE
  - 담당: jy
  - 선행: T003, T025
  - 검증: marker 번호·polyline·camera bounds·이동/확대/축소·attribution 동작을 실제 emulator 또는 기기에서 확인하고 lifecycle 재진입 시 중복 overlay 없음
- [ ] T027 [US2] DayRouteScreen의 지도·요약·구간 목록·네 상태 구현 in android/app/src/main/java/com/gilpick/route/DayRouteScreen.kt, android/app/src/main/java/com/gilpick/route/RouteLabels.kt
  - 영역: FE
  - 담당: jy
  - 선행: T023, T024, T025, T026
  - 검증: UI test·screenshot 통과, theme token만 사용, inset·48dp·색상 비의존·가로 스크롤 없음, F006 전용 진행 정보와 임의 재계산 미표시
- [ ] T028 [US2] 여행 상세 경로 요약과 날짜 경로 navigation 연결 in android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T021, T027
  - 검증: 상세 READY 영역에 구간·전체 이동시간과 경로 열기 제공, FAILED 영역은 일정과 독립, 상세→경로→뒤로가기 상태 보존을 `android/app/src/androidTest/java/com/gilpick/route/RouteNavigationTest.kt`에서 확인

**Checkpoint**: READY·빈 상태·오류 경로를 지도와 목록으로 독립 확인할 수 있다.

---

## Phase 5: User Story 3 - 실패한 경로 계산 다시 시도 (Priority: P3)

**Goal**: 실패 경로만 현재 일정 version의 같은 입력으로 멱등 재시도하며 정상 경로에는 재계산 행동을 노출하지 않는다.

**Independent Test**: FAILED fixture를 같은 version으로 두 번 재시도해 하나의 READY 경로만 유지되는지, 오래된 version과 정상 경로 요청은 거부되는지 검증한다.

### Tests for User Story 3

- [ ] T029 [P] [US3] 실패 경로 retry contract·integration test 작성 in api/tests/contract/test_route_contract.py, api/tests/integration/test_route_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T020
  - 검증: 같은 version 전체 경로 재계산, 중복·동시 요청에도 버전별 경로 한 행 upsert, stale version 409, READY 상태 `ROUTE_NOT_FAILED`, 실패 재응답도 200+FAILED, 현재 경로 하나
- [ ] T030 [P] [US3] Android retry ViewModel·UI test 보강 in android/app/src/test/java/com/gilpick/route/RouteViewModelTest.kt, android/app/src/androidTest/java/com/gilpick/route/DayRouteScreenTest.kt, android/app/src/androidTest/java/com/gilpick/trip/TripDetailScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T022, T023
  - 검증: FAILED에서만 retry, 클릭 중 중복 방지, 성공 시 Content, 재실패 시 원인 유지, version 충돌 시 최신 일정 안내, 정상 READY에 retry·후보 선택 없음

### Implementation for User Story 3

- [ ] T031 [US3] 실패 경로 멱등 retry service·endpoint 구현 in api/app/services/route.py, api/app/api/v1/route.py
  - 영역: BE
  - 담당: jh
  - 선행: T018, T020, T029
  - 검증: T029 통과, `(trip_day_id, schedule_version)` unique 제약과 upsert 멱등성, FAILED 선행 상태·version 재검증, 정상 경로 수동 재계산 불가
- [ ] T032 [US3] 경로 화면과 여행 상세의 실패 retry 동작 구현 in android/app/src/main/java/com/gilpick/route/RouteViewModel.kt, android/app/src/main/java/com/gilpick/route/DayRouteScreen.kt, android/app/src/main/java/com/gilpick/trip/TripDetailViewModel.kt, android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T028, T030, T031
  - 검증: T030 통과, 네트워크 단절은 즉시 오류, 일정 content 보존, retry touch target·문구·loading과 최대 글자 배율 검증

**Checkpoint**: 실패 복구는 가능하고 정상 경로의 임의 재탐색은 불가능하다.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T033 [P] Backend API·ERD·운영 설정 최종 일치 검증 in docs/design/api-spec.md, docs/design/er-schema.md, api/.env.example
  - 영역: BE
  - 담당: jh
  - 선행: T031
  - 검증: T001에서 동기화한 F004 일정 응답 확장, ROUTE 조회·실패 retry와 F006 `/recalculate` 구분, route table·오류 code·timeout·attribution을 최종 구현과 대조하고 `git diff --check` 통과
- [ ] T034 [P] Android 접근성·시각·실기기 최종 검증 in android/app/src/androidTest/java/com/gilpick/route/, specs/005-route-calculation/quickstart.md
  - 영역: FE
  - 담당: jy
  - 선행: T032
  - 검증: route 관련 unit/UI/screenshot test와 `assembleDebug`, 360dp·일반 phone·최대 글자 배율, 네 상태, 혼합 수단, 지도 gesture·inset·attribution 결과 기록
- [ ] T035 Backend 전체 자동 검증과 provider smoke test 기록 in api/tests/, specs/005-route-calculation/quickstart.md
  - 영역: BE
  - 담당: jh
  - 선행: T031, T033
  - 검증: route unit·contract·integration 및 전체 pytest·compileall 통과, mock clock으로 10초 deadline 검증, 승인된 local secret이 있을 때만 TMAP·ODsay 대표 구간 1개씩 호출하고 quota·권한·attribution 확인; 미실행 시 이유 기록
- [ ] T036 F005 종단간 계약 교차 검증 in specs/005-route-calculation/contracts/route.openapi.yaml, specs/005-route-calculation/quickstart.md
  - 영역: 통합
  - 담당: jh
  - 교차 확인: jy
  - 선행: T034, T035
  - 검증: 로컬 API+AVD로 혼합 일정 저장→자동 계산→상세→경로 화면, provider 실패→일정 보존→retry, 중복·version 충돌을 수행하고 BE·FE DTO·enum·nullable·단위·오류 code 및 SC-001~SC-006 충족 기록

---

## Dependencies & Execution Order

### Phase Dependencies

```text
Setup(T001~T004)
  → Foundational(T005~T010)
  → US1(T011~T021)
  → US2(T022~T028)
  → US3(T029~T032)
  → Polish(T033~T036)
```

### User Story Dependencies

- **US1**: F004 일정 저장 구현과 Foundational 완료 후 시작한다. F005 Backend MVP다.
- **US2**: 조회 API(T020)와 Android Repository(T010)에 의존한다. 지도·목록 표시 increment다.
- **US3**: US1의 실패 상태와 US2의 상태 UI를 재사용하므로 두 story 뒤에 진행한다.

### Parallel Opportunities

- T002·T003·T004는 T001 후 BE 설정·Android SDK·디자인 파일이 달라 병렬 가능하다.
- T006·T007·T008·T009는 T005/T001/T002 조건 충족 후 소유 파일이 달라 병렬 가능하다.
- US1에서 T011·T012·T013·T014·T015는 각 선행 조건 뒤 BE provider/service/contract와 FE 상태 test로 병렬 작성할 수 있다.
- US2에서 T022·T023·T024는 Repository·디자인 기준 확정 후 병렬 작성할 수 있다.
- US3에서 T029와 T030은 BE·FE 파일이 달라 병렬 가능하다.
- 같은 `route.py`, `TripDetailScreen.kt`, `RouteViewModel.kt`, `DayRouteScreen.kt`를 수정하는 task끼리는 병렬 처리하지 않는다.

## Parallel Examples

### User Story 1

```text
BE jh: T011 TMAP test / T012 ODsay test / T013 service test / T014 contract·integration test
FE jy: T015 일정 저장·상세 상태 test
```

### User Story 2

```text
FE jy: T022 ViewModel test / T023 UI test / T024 screenshot test
BE jh: T020 조회 endpoint 안정화와 contract test 확인
```

### User Story 3

```text
BE jh: T029 test → T031 retry endpoint
FE jy: T030 test → T032 retry UI
```

## Implementation Strategy

### MVP First

1. Setup·Foundational로 계약, 저장 구조, provider 경계와 양쪽 DTO를 고정한다.
2. US1을 완료해 일정 저장 후 계산·조회·실패 격리까지 Backend MVP로 검증한다.
3. US2에서 지도·목록 UI를 추가하고 US3에서 실패 복구를 완성한다.

### Issue Grouping Guidance

- BE `jh`: (a) T001·T002·T005~T008 기반, (b) T011~T020 US1, (c) T029·T031 retry, (d) T033·T035 검증
- FE `jy`: (a) T003·T004·T009·T010 기반, (b) T015·T021 저장/상세 상태, (c) T022~T028 지도 화면, (d) T030·T032 retry, (e) T034 검증
- 통합: T001은 `jh` 주도·`jy` 교차 확인, T036은 양쪽 최종 확인

## Notes

- F004가 아직 구현 중이면 해당 일정 저장·Android ViewModel 선행 task가 병합된 뒤 T019·T021을 시작한다.
- TMAP·ODsay 운영 quota·권한·attribution은 공식 console과 live smoke test에서 재확인한다. 확인 전 값을 사실로 단정하지 않는다.
- F006의 진행 중 `/route/recalculate`와 F005 실패 전용 `/route/retry`를 합치지 않는다.
- 구현·검증 완료 전 `mvp-features.md` 상태를 `READY`로 변경하지 않는다.
