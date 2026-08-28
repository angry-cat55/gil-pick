# Tasks: 여행 관리

**Input**: Design documents from `/specs/002-trip-management/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/trips.openapi.yaml](contracts/trips.openapi.yaml), [quickstart.md](quickstart.md)

**담당자**: 팀 합의에 따라 Backend는 `ts`, Frontend Android는 `hs`가 담당한다. 영역이 `통합`인 task(Phase 8)는 `ts, hs`가 함께 확인한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미해결 선행 없음)
- **[Story]**: 해당 task가 속한 user story (US1~US5)
- 각 task 아래에 영역·담당·선행·검증을 들여쓴 보조 메타데이터로 기록한다.

---

## Phase 1: Setup

**Purpose**: F002 구현에 필요한 최소 뼈대 준비

- [x] T001 Alembic revision 뼈대 생성 in `api/migrations/versions/002_create_trip_table.py`
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `upgrade`/`downgrade` 함수만 있는 빈 revision이 `alembic upgrade head`·`downgrade -1`로 왕복 성공
- [x] T002 [P] 여행 오류 코드 상수 추가 in `api/app/api/errors.py`
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `INVALID_TRIP_PERIOD`, `TRIP_NOT_FOUND`, `TRIP_LOCKED`, `VERSION_CONFLICT`, `CONFIRMATION_REQUIRED` 추가 후 기존 인증 오류 상수와 이름 충돌 없이 import 성공

**Checkpoint**: 마이그레이션 파일과 오류 코드가 준비되어 Foundational 작업을 시작할 수 있다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 user story가 공통으로 쓰는 모델·DTO·라우팅 뼈대

**🚨 CRITICAL**: 이 phase가 끝나기 전에는 어떤 user story 작업도 시작하지 않는다.

- [x] T003 Trip ORM 모델 구현 in `api/app/models/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: T001
  - 검증: [data-model.md](data-model.md) 컬럼·제약(`CHECK` 길이·기간)·인덱스와 1:1 대응, `python -m compileall` 통과
- [x] T004 [P] Trip 요청·응답 DTO 구현 in `api/app/schemas/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: [contracts/trips.openapi.yaml](contracts/trips.openapi.yaml)의 `CreateTripRequest`, `UpdateTripRequest`, `Trip`, `ErrorEnvelope` 스키마와 필드·필수 여부 일치
- [x] T005 trips API 라우터 뼈대와 `/api/v1` 등록 in `api/app/api/v1/trips.py`, `api/app/main.py`
  - 영역: BE
  - 담당: ts
  - 선행: T003, T004
  - 검증: 5개 endpoint(TRIP-001~005) 시그니처만 있는 상태로 앱 기동 성공, `Authorization` dependency는 F001 것을 재사용
- [ ] T006 [P] Android Trip DTO·Retrofit 인터페이스 구현 in `android/app/src/main/java/com/gilpick/trip/TripApi.kt`
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: F001 `AuthApi.kt` 패턴대로 `contracts/trips.openapi.yaml` 5개 endpoint에 대응하는 함수·DTO 정의, 컴파일 성공

**Checkpoint**: Foundation 준비 완료, user story 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 여행 생성 (Priority: P1) 🎯 MVP

**Goal**: 인증된 사용자가 여행명과 기간을 입력해 새 여행을 만든다.

**Independent Test**: 인증된 사용자로 여행명·기간을 입력해 생성 요청을 보내고, 생성된 여행이 조회되는지 확인한다.

### Tests for User Story 1

> **NOTE: 아래 test를 먼저 작성하고 구현 전에 실패하는 것을 확인한다**

- [x] T007 [P] [US1] Backend contract test: 생성 성공·검증 실패(이름 길이/trim, 기간, `startDate>endDate`)·동일 이름 재생성 성공 in `api/tests/contract/test_trip_contract.py`
  - 영역: BE
  - 담당: ts
  - 선행: T005
  - 검증: FR-001, FR-001a, FR-001b 대응 케이스 포함, FR-002(동일 사용자 내 여행명 중복 생성이 거부되지 않고 성공하는 케이스) 포함, 구현 전 실패
- [x] T008 [P] [US1] Backend integration test: `Idempotency-Key` 재전송 시 단일 생성 in `api/tests/integration/test_trip_flow.py`
  - 영역: BE
  - 담당: ts
  - 선행: T005
  - 검증: FR-003, 동일 키 재전송 2회가 동일한 `tripId` 1건만 반환
- [ ] T009 [P] [US1] Android unit test: 생성 폼 검증(길이, 기간 초과, 시작일>종료일, 공백 이름) in `android/app/src/test/java/com/gilpick/trip/TripFormValidationTest.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: US1 Acceptance Scenario 2·3, 구현 전 실패

### Implementation for User Story 1

- [x] T010 Alembic migration 완성(`trips` 테이블 컬럼·`CHECK`·인덱스) in `api/migrations/versions/002_create_trip_table.py`
  - 영역: BE
  - 담당: ts
  - 선행: T001, T003
  - 검증: `alembic upgrade head` 성공, [data-model.md](data-model.md) 인덱스(`(user_id, deleted_at)`, `(user_id, lower(name)) WHERE deleted_at IS NULL`) 존재 확인
- [x] T011 [US1] `TripService.create_trip` 구현(trim·길이·기간 검증, Idempotency-Key 처리) in `api/app/services/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: T007, T008, T010
  - 검증: T007·T008 test 통과
- [x] T012 [US1] `POST /api/v1/trips` endpoint 연결 in `api/app/api/v1/trips.py`
  - 영역: BE
  - 담당: ts
  - 선행: T011
  - 검증: `201` 응답이 [contracts/trips.openapi.yaml](contracts/trips.openapi.yaml) `TripEnvelope`와 일치
- [ ] T013 [P] [US1] `TripRepository.createTrip` 구현 in `android/app/src/main/java/com/gilpick/trip/TripRepository.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: MockWebServer로 `201`/`422` 응답 매핑 확인
- [ ] T014 [US1] `TripFormScreen`(생성 모드)·`TripFormViewModel` 구현 in `android/app/src/main/java/com/gilpick/trip/TripFormScreen.kt`, `android/app/src/main/java/com/gilpick/trip/TripFormViewModel.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T009, T013
  - 검증: T009 test 통과, 생성 성공 시 서버가 반환한 `tripId`로 상세 화면 이동 준비(실제 이동 연결은 US3)

**Checkpoint**: 여행 생성이 독립적으로 동작하고 테스트 가능하다.

---

## Phase 4: User Story 2 - 여행 목록 조회·검색·필터 (Priority: P1)

**Goal**: 사용자가 자신의 여행 목록을 상태 그룹 순으로 보고, 이름으로 검색하거나 상태로 거른다.

**Independent Test**: 서로 다른 상태의 여행 여러 건을 만든 뒤 목록 조회, 검색, 필터, 추가 조회(스크롤)를 확인한다.

### Tests for User Story 2

- [x] T015 [P] [US2] Backend contract test: 목록 조회 정렬·검색·필터·cursor pagination in `api/tests/contract/test_trip_contract.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-005~FR-009, 구현 전 실패
- [x] T016 [P] [US2] Backend integration test: 다른 사용자 여행 비노출, 상태 그룹(여행 중→예정→완료) 순서 in `api/tests/integration/test_trip_flow.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-004, FR-005, SC-005
- [ ] T017 [P] [US2] Android unit test: 검색어·상태 필터 조합 상태 관리·무결과 상태 표시 in `android/app/src/test/java/com/gilpick/trip/TripListViewModelTest.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: US2 Acceptance Scenario 2·3, 검색어+상태 필터를 동시 적용해 결과가 없을 때 빈 화면 문구가 표시되는 케이스 포함, 구현 전 실패

### Implementation for User Story 2

- [x] T018 [US2] `TripService.list_trips` 구현(상태 파생 계산, 검색, 필터, cursor pagination, 소유권 필터) in `api/app/services/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: T015, T016
  - 검증: T015·T016 test 통과
- [x] T019 [US2] `GET /api/v1/trips` endpoint 연결 in `api/app/api/v1/trips.py`
  - 영역: BE
  - 담당: ts
  - 선행: T018
  - 검증: 응답이 `TripListResponse`(cursor·`hasNext`) 계약과 일치
- [ ] T020 [P] [US2] `TripRepository.listTrips` 구현 in `android/app/src/main/java/com/gilpick/trip/TripRepository.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: cursor 페이지네이션 응답을 다음 페이지 요청에 올바르게 반영
- [ ] T021 [US2] `TripListScreen`·`TripListViewModel`(검색·필터·무한 스크롤) 구현 in `android/app/src/main/java/com/gilpick/trip/TripListScreen.kt`, `android/app/src/main/java/com/gilpick/trip/TripListViewModel.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T017, T020
  - 검증: T017 test 통과, 여행 없음 상태의 빈 화면 문구 표시
- [ ] T022 [US2] `MainActivity` 진입점을 `AuthenticatedHomeScreen`에서 `TripListScreen`으로 교체 in `android/app/src/main/java/com/gilpick/MainActivity.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T021
  - 검증: 로그인 성공 후 실제 여행 목록이 표시됨(F001의 빈 shell 대체)

**Checkpoint**: US1+US2로 생성→목록 확인까지 되는 MVP가 완성된다.

---

## Phase 5: User Story 3 - 여행 상세 조회 (Priority: P2)

**Goal**: 사용자가 목록에서 여행을 선택해 이름·기간·상태를 확인한다.

**Independent Test**: 생성된 여행 하나를 상세 조회해 이름·기간·상태가 정확히 표시되는지 확인한다.

### Tests for User Story 3

- [x] T023 [P] [US3] Backend contract test: 상세 조회 성공·소유권 거부(`403`)·미존재/삭제됨(`404`) in `api/tests/contract/test_trip_contract.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-004, FR-017, US3 Acceptance Scenario 1~3, 구현 전 실패
- [ ] T024 [P] [US3] Android unit test: 상세 화면 상태 매핑(로딩·성공·오류) in `android/app/src/test/java/com/gilpick/trip/TripDetailViewModelTest.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: 구현 전 실패

### Implementation for User Story 3

- [x] T025 [US3] `TripService.get_trip` 구현(소유권 검증, `403`/`404` 분기) in `api/app/services/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: T018, T023
  - 검증: T023 test 통과
- [x] T026 [US3] `GET /api/v1/trips/{tripId}` endpoint 연결 in `api/app/api/v1/trips.py`
  - 영역: BE
  - 담당: ts
  - 선행: T025
  - 검증: 응답이 `TripEnvelope` 계약과 일치
- [ ] T027 [P] [US3] `TripRepository.getTrip` 구현 in `android/app/src/main/java/com/gilpick/trip/TripRepository.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T020
  - 검증: `403`/`404` 오류를 도메인 오류로 매핑
- [ ] T028 [US3] `TripDetailScreen`·`TripDetailViewModel` 구현 in `android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt`, `android/app/src/main/java/com/gilpick/trip/TripDetailViewModel.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T024, T027
  - 검증: T024 test 통과
- [ ] T029 [US3] 목록에서 상세로 이동하는 navigation 연결 in `android/app/src/main/java/com/gilpick/trip/TripListScreen.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T021, T028
  - 검증: 목록 항목 탭 시 해당 `tripId` 상세로 이동

**Checkpoint**: US1~US3로 생성·목록·상세 흐름이 완성된다.

---

## Phase 6: User Story 4 - 여행 기간 수정 (Priority: P2)

**Goal**: 사용자가 여행 이름·기간을 수정하고, 기간 축소로 일정이 삭제될 경우 미리 안내받는다. 완료된 여행은 이름만 수정할 수 있다.

**Independent Test**: 기간을 축소하는 수정 요청을 보내 삭제 안내와 결과를, 완료 상태 여행에는 기간 수정 거부를 확인한다.

### Tests for User Story 4

- [ ] T030 [P] [US4] Backend contract test: 수정 성공, 버전 충돌(`409 VERSION_CONFLICT`), 완료 상태 기간 수정 거부(`409 TRIP_LOCKED`), 소유권 없는 사용자의 수정 거부(`403`) in `api/tests/contract/test_trip_contract.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-010, FR-010a, FR-011, FR-011a, US4 Acceptance Scenario 5~8, FR-017(다른 사용자가 `PATCH`를 시도하면 `403`으로 거부되는 케이스, T023의 상세 조회 소유권 테스트와 동일한 방식) 포함, 구현 전 실패
- [ ] T031 [P] [US4] Backend integration test: 기간 축소 확인 흐름(`409 CONFIRMATION_REQUIRED` → `confirmDeleteOutOfRangeItems=true` 재요청) in `api/tests/integration/test_trip_flow.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-012, FR-013. F002 시점에는 `trip_days`/`itinerary_items`가 없어 `deletedItemCount`가 항상 0임을 확인([data-model.md](data-model.md) "범위 밖")
- [ ] T032 [P] [US4] Backend unit test: 완료 상태는 이름만 허용, trim·길이·기간 재검증 in `api/tests/unit/test_trip_service.py`
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: 구현 전 실패
- [ ] T033 [P] [US4] Android unit test: 완료 여행 기간 입력 비활성화, 버전 충돌·잠금 오류를 사용자 메시지로 매핑 in `android/app/src/test/java/com/gilpick/trip/TripFormValidationTest.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T006
  - 검증: 구현 전 실패

### Implementation for User Story 4

- [ ] T034 [US4] `TripService.update_trip` 구현(버전 검증, 완료 상태 잠금, trim·기간 검증, 삭제 확인 플래그 처리, T025의 소유권 검증 재사용) in `api/app/services/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: T025, T030, T031, T032
  - 검증: T030~T032 test 통과(FR-017 소유권 거부 케이스 포함)
- [ ] T035 [US4] `PATCH /api/v1/trips/{tripId}` endpoint 연결 in `api/app/api/v1/trips.py`
  - 영역: BE
  - 담당: ts
  - 선행: T034
  - 검증: `409`/`422` 오류 코드가 `contracts/trips.openapi.yaml`과 일치
- [ ] T036 [P] [US4] `TripRepository.updateTrip` 구현 in `android/app/src/main/java/com/gilpick/trip/TripRepository.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T027
  - 검증: `version` 필드를 요청에 포함, `409` 오류를 재조회 안내로 매핑
- [ ] T037 [US4] `TripFormScreen` 수정 모드(버전 전달, 완료 상태 기간 입력 잠금, 삭제 확인 다이얼로그) 구현 in `android/app/src/main/java/com/gilpick/trip/TripFormScreen.kt`, `android/app/src/main/java/com/gilpick/trip/TripFormViewModel.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T033, T036
  - 검증: T033 test 통과
- [ ] T038 [US4] 상세 화면에서 수정 진입 연결 in `android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T028, T037
  - 검증: 상세 화면 → 수정 화면 이동, 저장 후 상세로 복귀하며 최신 `version` 반영

**Checkpoint**: US1~US4로 생성·목록·상세·수정까지 완성된다.

---

## Phase 7: User Story 5 - 여행 삭제 (Priority: P3)

**Goal**: 사용자가 더 이상 필요 없는 여행을 삭제한다. 완료된 여행은 삭제할 수 없다.

**Independent Test**: 여행을 삭제 요청한 뒤 목록·상세에서 더 이상 나타나지 않는지, 완료 상태 여행은 삭제가 거부되는지 확인한다.

### Tests for User Story 5

- [ ] T039 [P] [US5] Backend contract test: 삭제 성공(`204`), 반복 요청 멱등, 완료 상태 거부(`409 TRIP_LOCKED`), 소유권 거부(`403`) in `api/tests/contract/test_trip_contract.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-014, FR-016, US5 Acceptance Scenario 1~4, 구현 전 실패
- [ ] T040 [P] [US5] Backend integration test: 삭제 후 목록·상세·검색 결과에서 즉시 제외 in `api/tests/integration/test_trip_flow.py`
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: FR-015, SC-006

### Implementation for User Story 5

- [ ] T041 [US5] `TripService.delete_trip` 구현(soft delete, 완료 상태 잠금, 멱등 처리) in `api/app/services/trip.py`
  - 영역: BE
  - 담당: ts
  - 선행: T025, T039, T040
  - 검증: T039·T040 test 통과
- [ ] T042 [US5] `DELETE /api/v1/trips/{tripId}` endpoint 연결 in `api/app/api/v1/trips.py`
  - 영역: BE
  - 담당: ts
  - 선행: T041
  - 검증: `204`/`409 TRIP_LOCKED` 응답 확인
- [ ] T043 [P] [US5] `TripRepository.deleteTrip` 구현 in `android/app/src/main/java/com/gilpick/trip/TripRepository.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T027
  - 검증: 삭제 성공·잠금 오류 매핑
- [ ] T044 [US5] 상세 화면 삭제 버튼·확인 다이얼로그·목록 갱신 연결 in `android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt`, `android/app/src/main/java/com/gilpick/trip/TripListViewModel.kt`
  - 영역: FE
  - 담당: hs
  - 선행: T028, T043
  - 검증: 삭제 후 상세 화면 종료, 목록에서 즉시 제외

**Checkpoint**: US1~US5 전체가 독립적으로 동작한다.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T045 [P] quickstart.md 시나리오 실행 및 결과 기록 in `specs/002-trip-management/quickstart.md`
  - 영역: 통합
  - 담당: ts, hs
  - 선행: T012, T019, T026, T035, T042, T022, T029, T038, T044
  - 검증: Backend curl 시나리오·Android 확인 항목을 모두 실행하고 실행 명령·결과를 PR에 기록. 실행하지 못한 항목은 이유를 남긴다.
- [ ] T046 [P] `docs/design/api-spec.md`·`docs/design/er-schema.md`와 실제 구현 최종 일치 확인 in `docs/design/api-spec.md`, `docs/design/er-schema.md`
  - 영역: 통합
  - 담당: ts, hs
  - 선행: T045
  - 검증: TRIP-001~005 실제 응답·오류 코드가 문서 예시와 일치, 불일치 시 문서 수정 PR 별도 제안
- [ ] T047 Backend Google-style docstring·Android KDoc 보완 in `api/app/services/trip.py`, `api/app/api/v1/trips.py`, `android/app/src/main/java/com/gilpick/trip/*.kt`
  - 영역: 통합
  - 담당: ts, hs
  - 선행: T041, T044
  - 검증: constitution "코드 주석과 문서화 스타일" 절 기준 충족(공개 함수·핵심 상태 변경 함수 문서화)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음
- **Foundational (Phase 2)**: Setup 완료 후 — 모든 user story를 막는다
- **User Stories (Phase 3~7)**: Foundational 완료 후 시작 가능
  - US1(P1) → US2(P1) → US3(P2) → US4(P2) → US5(P3) 순으로 우선순위가 있지만, US3~US5는 US1(생성)·US2(목록)가 만든 데이터·화면 진입점에 의존하므로 실질적으로 순차 진행이 자연스럽다.
  - US4·US5는 각각 T025(상세 조회 서비스)에 의존한다.
- **Polish (Phase 8)**: 구현하기로 한 모든 user story 완료 후

### Parallel Opportunities

- Phase 1의 T001·T002는 병렬 가능
- Phase 2의 T004·T006은 각각 T003·T005와 다른 파일이라 병렬 가능
- 각 user story의 테스트 task는 서로 다른 파일이면 병렬 가능(예: T007·T008·T009)
- Backend `TripRepository` 관련 task(T013, T020, T027, T036, T043)는 서로 다른 메서드지만 같은 파일(`TripRepository.kt`)이므로 동시에 여러 사람이 작업하지 않는다(AGENTS.md 9절: 같은 파일은 병렬 배정 금지)

## Implementation Strategy

### MVP 우선 (US1 + US2)

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료(모든 story의 필수 선행)
3. Phase 3 US1(여행 생성) 완료 및 독립 검증
4. Phase 4 US2(목록 조회) 완료 및 독립 검증 → 생성·조회가 되는 MVP
5. 이후 Phase 5~7(상세·수정·삭제)을 우선순위(P2, P2, P3) 순으로 증분 진행

### 팀 분담

- Backend 성격 task(T001~T005, T007~T008, T010~T012, T015~T016, T018~T019, T023, T025~T026, T030~T032, T034~T035, T039~T042)는 `ts`가 맡는다.
- Frontend Android 성격 task(T006, T009, T013~T014, T017, T020~T022, T024, T027~T029, T033, T036~T038, T043~T044)는 `hs`가 맡는다.
- Phase 8(T045~T047)은 통합 검증으로 `ts`·`hs`가 함께 확인한다.
