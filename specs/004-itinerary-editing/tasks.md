---

description: "F004 일정 구성 구현 task 목록"
---

# Tasks: 일정 구성

**Input**: `/specs/004-itinerary-editing/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**담당 합의**(2026-09-05, jy 확인): Feature Owner `jy`, Backend `ts`(review `jh`), Frontend Android 일정 편집 화면·navigation `jy`, Frontend Android 여행 상세·여행 수정 화면 수정 `hs`, 통합 검증 `ts`+`jy`

**Tests**: 저장 멱등·버전 충돌·잠금·소유권과 화면 상태가 핵심 요구사항이므로 contract·unit·integration·Compose UI test를 포함한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일을 수정하고 미해결 선행 관계가 없어 병렬 수행 가능
- **[Story]**: 해당 user story 식별자
- 각 task의 보조 메타데이터에 영역, 담당, 선행 관계와 검증 방법을 기록한다.

## Phase 1: Setup

**Purpose**: 저장 구조와 계약을 양쪽이 같은 기준으로 시작할 수 있게 준비한다.

- [x] T001 [P] GeoAlchemy2 의존성 추가와 PostGIS `geography` 컬럼 ORM 매핑 확인 in api/pyproject.toml, api/uv.lock
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `uv sync` 후 `geoalchemy2` import와 compose PostGIS 이미지에서 `geography(Point,4326)` 컬럼 생성 확인
- [ ] T002 [P] Figma 반영 확인 기록 — 이동 수단 시트의 체류 시간 제거, 순서 변경 손잡이·위아래 버튼, 여행 상세 날짜 헤더 `장소 추가`, 여행 수정 삭제 확인 대화상자 in specs/004-itinerary-editing/plan.md
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: `docs/design/figma-make/src/screens/ScheduleEditScreen.tsx`·`TripDetailScreen.tsx`·`EditTripScreen.tsx` 사본을 갱신하고 plan.md UI 절에 반영 일자와 차이를 기록
- [x] T003 Backend·Android 구현 전 일정 계약 교차 review in specs/004-itinerary-editing/contracts/itinerary.openapi.yaml, specs/004-itinerary-editing/data-model.md, specs/004-itinerary-editing/research.md
  - 영역: 통합
  - 담당: ts
  - 교차 확인: jy
  - 선행: 없음
  - 검증: `place` 스냅샷 필드, `staySource`, `routeStatus NOT_CALCULATED`, `422 INVALID_ITINERARY` violations 형식, `409 ITINERARY_ITEM_LOCKED`, uuid5 항목 ID·no-op 규칙, F002 PATCH `deletedItemCount` 동작 변경을 BE `ts`·FE `jy`가 확인한 기록을 남기고 불일치를 구현 전에 문서에 반영
  - 기록(2026-09-05, #185): BE `ts`가 F003 계약과 교차 검토해 신규 항목 snapshot 필수, F003에 없는 TourAPI→Google 매칭 ID 제거, typed error details, UUID Idempotency-Key, 처리된 항목 순서 변경 시 이동 수단 enum 유지, F004 `NOT_CALCULATED` 전역 계약 동기화를 반영했다. FE `jy` 교차 review는 PR에서 확인한다.

---

## Phase 2: Foundational

**Purpose**: 모든 story가 의존하는 저장 구조, 공통 DTO, Android API 계층을 만든다.

- [x] T004 migration `003_create_itinerary_tables`로 `trip_days`·`places`·`itinerary_items` 생성 in api/migrations/versions/003_create_itinerary_tables.py
  - 영역: BE
  - 담당: ts
  - 선행: T001, T003
  - 검증: `alembic upgrade head`·`downgrade -1` 왕복, ERD 5.2~5.4의 UNIQUE·CHECK·partial unique·GIST 인덱스가 생성되는 integration test
- [x] T005 [P] `TripDay`·`Place`·`ItineraryItem` ORM 모델 in api/app/models/itinerary.py
  - 영역: BE
  - 담당: ts
  - 선행: T004
  - 검증: 모델 컬럼·관계가 migration과 일치하는 unit test, `Trip`과의 relationship 동작
- [x] T006 [P] 일정 DTO·enum·envelope schema in api/app/schemas/itinerary.py
  - 영역: BE
  - 담당: ts
  - 선행: T003
  - 검증: `contracts/itinerary.openapi.yaml`의 `SaveItem`·`PlaceSnapshot`·`DayItinerary`·overview envelope·오류 code enum과 일치하는 schema test(30분 단위·범위·placeId pattern)
- [ ] T007 [P] Android 일정 DTO·`TransportMode`·`ItineraryService`(Retrofit) in android/app/src/main/java/com/gilpick/itinerary/ItineraryApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T003
  - 검증: MockWebServer로 ITIN-001·002·003 요청 경로·`Idempotency-Key` 헤더·직렬화 round-trip unit test
- [ ] T008 Android `ItineraryRepository`(`AuthRepository.withAuthorizedCall`, 오류 분류) in android/app/src/main/java/com/gilpick/itinerary/ItineraryRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T007
  - 검증: 401 refresh·replay, `VERSION_CONFLICT`·`INVALID_ITINERARY`·`ITINERARY_ITEM_LOCKED`·`TRIP_FORBIDDEN`·네트워크 오류가 각각 구분되는 unit test
- [x] T009 itinerary router 등록과 소유권 dependency 재사용 in api/app/api/v1/itinerary.py, api/app/main.py
  - 영역: BE
  - 담당: ts
  - 선행: T005, T006
  - 검증: 인증 없는 요청 `401`, 다른 사용자 `403`, 삭제된 여행 `404`, 기간 밖 날짜 `404` contract test(endpoint 본문은 US1~3에서 채움)

**Checkpoint**: 저장 구조와 계약이 준비되어 story별 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 날짜별 일정에 장소 추가 (Priority: P1) 🎯 MVP

**Goal**: 여행 상세 → 일정 편집 → 장소 검색 → `일정에 추가` → 저장이 실서버로 동작한다.

**Independent Test**: 날짜 하나에 장소를 추가·저장한 뒤 ITIN-001로 재조회해 같은 장소와 추천 체류 시간이 남는다(spec US1).

### Tests for User Story 1

- [ ] T010 [P] [US1] ITIN-001·002 contract test in api/tests/contract/test_itinerary_contract.py
  - 영역: BE
  - 담당: ts
  - 선행: T009
  - 검증: quickstart BE 1·2·3·4·5 시나리오(빈 날짜 version 0, `201` 생성, 같은 key 재전송 무해, `422` violations, `404`/`403`, `409 VERSION_CONFLICT`)
- [ ] T011 [P] [US1] 일정 service unit test in api/tests/unit/test_itinerary_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T006
  - 검증: 검증 순서 6단계, `places` upsert(같은 provider ID 재사용), uuid5 항목 ID, 결과 동일 시 no-op, transaction 경계
- [ ] T012 [P] [US1] `ItineraryEditViewModel` unit test in android/app/src/test/java/com/gilpick/itinerary/ItineraryEditViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008
  - 검증: 검색 결과 추가 시 끝에 붙고 직전 항목 이동 수단 설정(첫 항목이면 무시), 좌표 없는 장소는 추가 거부·안내, 추천 체류 시간 자동 입력·`RECOMMENDED`, `sequence` 1..N 부여, `Idempotency-Key` 1회 생성, 409 시 최신 version으로 최대 2회 재저장, 연속 실패 시 `Failed`와 초안 유지, `SavedStateHandle` 복원
- [ ] T013 [P] [US1] 편집 화면 Compose UI test in android/app/src/androidTest/java/com/gilpick/itinerary/ItineraryEditScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008
  - 검증: `loading`(1초 규칙)·`empty`(`장소 추가` 안내)·`error`(원인+`다시 시도`)·`content`, 저장 중 `저장` 비활성, 취소 확인 대화상자(변경 없으면 바로 닫힘), 48dp 터치 영역·아이콘 설명

### Implementation for User Story 1

- [ ] T014 [US1] 일정 저장·조회 service(검증, `places` upsert, diff 저장, version, no-op) in api/app/services/itinerary.py
  - 영역: BE
  - 담당: ts
  - 선행: T005, T006, T011
  - 검증: T011 통과, 저장 시각·version 전후·항목 수를 request ID와 함께 log(장소 좌표 원문 제외)
- [ ] T015 [US1] ITIN-001 조회·ITIN-002 저장 endpoint in api/app/api/v1/itinerary.py
  - 영역: BE
  - 담당: ts
  - 선행: T009, T014
  - 검증: T010 통과, `Idempotency-Key` 필수, `200`/`201` 구분, 오류 envelope 형식, 모든 응답이 `routeStatus NOT_CALCULATED`·`route null`(FR-018)
- [ ] T016 [US1] `ItineraryEditViewModel`(초안/저장본 분리, `SavedStateHandle`, 자동 재저장) in android/app/src/main/java/com/gilpick/itinerary/ItineraryEditViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008, T012
  - 검증: T012 통과, `dirty` 계산, 10곳에서 추가 비활성
- [ ] T017 [US1] 일정 편집 화면(날짜 탭, 장소 카드, `장소 추가`, `저장`, 취소 확인) in android/app/src/main/java/com/gilpick/itinerary/ItineraryEditScreen.kt, android/app/src/main/java/com/gilpick/itinerary/ItineraryLabels.kt
  - 영역: FE
  - 담당: jy
  - 선행: T013, T016, T002
  - 검증: Figma `ScheduleEditScreen` 대조, theme token만 사용, 네 상태, 48dp, 360dp·font scale 2.0 잘림 없음, `routeStatus NOT_CALCULATED`에서 도착 시각·구간 소요 시간 미표시
- [ ] T018 [US1] `ItineraryEditRoute(tripId, date, openSearch)` 등록과 F003 `placeGraph` `onAddToSchedule` 결과 반환 연결 in android/app/src/main/java/com/gilpick/MainActivity.kt, android/app/src/main/java/com/gilpick/place/PlaceNavigation.kt
  - 영역: FE
  - 담당: jy
  - 선행: T016, T017
  - 검증: Navigation test — 편집 → 검색 → 상세 → `일정에 추가` → 편집(항목 추가됨), 시트 취소 → 편집(변화 없음), `openSearch=true` 진입 시 검색으로 즉시 이동. `PlaceNavigation.kt` 수정은 F003 파일이므로 diff 최소화

**Checkpoint**: 편집 화면 단독으로 장소 추가·저장·재조회가 실서버에서 동작한다(F004 MVP).

---

## Phase 4: User Story 2 - 순서·체류 시간·이동 수단 편집 (Priority: P2)

**Goal**: 순서 이동(버튼·끌기), 체류 시간 대화상자, 이동 수단 시트, 삭제와 처리된 장소 잠금이 동작한다.

**Independent Test**: 장소 2개 이상인 날짜에서 순서·체류 시간·이동 수단을 바꿔 저장하고 재조회해 세 값이 유지된다(spec US2).

### Tests for User Story 2

- [ ] T019 [P] [US2] 처리된 항목 잠금·순서 재부여 test in api/tests/unit/test_itinerary_service.py, api/tests/integration/test_itinerary_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T014
  - 검증: quickstart BE 6·7 시나리오(같은 장소 두 날짜 → `places` 1행, 같은 날짜 두 번 → 항목 2개, `COMPLETED` fixture의 장소·이동 수단 변경·삭제 `409 ITINERARY_ITEM_LOCKED`, 체류·순서 변경 `200`)
- [ ] T020 [P] [US2] 편집 조작 ViewModel·UI test 보강 in android/app/src/test/java/com/gilpick/itinerary/ItineraryEditViewModelTest.kt, android/app/src/androidTest/java/com/gilpick/itinerary/ItineraryEditScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T016, T017
  - 검증: 위·아래 이동과 끌기 후 순서, 삭제 후 순서 재부여와 마지막 항목 이동 수단 null, 체류 시간 30분 단위·30~360 경계·60/90/120 빠른 선택·`USER_ADJUSTED`, 이동 수단 시트(체류 시간 조절 없음), 처리된 항목의 삭제·`변경` 숨김과 상태 표시(색+아이콘+문구)

### Implementation for User Story 2

- [ ] T021 [US2] 처리된 항목 잠금 검증과 `ITINERARY_ITEM_LOCKED` 오류 in api/app/services/itinerary.py, api/app/api/v1/itinerary.py
  - 영역: BE
  - 담당: ts
  - 선행: T015, T019
  - 검증: T019 통과, 요청 `status` 무시하고 저장값 유지
- [ ] T022 [US2] 체류 시간 대화상자·이동 수단 시트·삭제·순서 이동 버튼 in android/app/src/main/java/com/gilpick/itinerary/ItineraryEditScreen.kt, android/app/src/main/java/com/gilpick/itinerary/ItineraryEditViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T017, T020
  - 검증: Figma 대화상자·시트 대조, `−`·`+` 40/44dp 원과 48dp 터치, 처리된 항목 표시
- [ ] T023 [US2] 손잡이 끌기 순서 변경(foundation gesture, 라이브러리 없음) in android/app/src/main/java/com/gilpick/itinerary/ItineraryEditScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T022
  - 검증: 끌기 후 `draft` 순서가 버튼 이동과 같은 결과, 48dp·360dp·font scale 2.0에서 조작 가능. 기준 미달이면 버튼만 남기고 PR에 차이 기록

**Checkpoint**: 편집 조작 전부와 잠금 규칙이 검증되고 저장 결과가 재조회와 일치한다.

---

## Phase 5: User Story 3 - 여행 상세에서 날짜별 일정 확인 (Priority: P3)

**Goal**: 여행 상세가 모든 날짜의 일정을 보여 주고 `일정 편집`·날짜별 `장소 추가`로 진입한다.

**Independent Test**: 일정이 있는 여행과 없는 여행의 상세에서 날짜별 목록과 빈 날짜 표시가 맞다(spec US3).

### Tests for User Story 3

- [ ] T024 [P] [US3] ITIN-003 개요 contract·integration test in api/tests/contract/test_itinerary_contract.py, api/tests/integration/test_itinerary_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T015
  - 검증: quickstart BE 8(기간의 모든 날짜 순서대로, 빈 날짜 version 0 포함), 소유권 `403`
- [ ] T025 [P] [US3] 여행 상세 일정 영역 ViewModel·UI test in android/app/src/test/java/com/gilpick/trip/TripDetailViewModelTest.kt, android/app/src/androidTest/java/com/gilpick/trip/TripDetailScreenTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: T008
  - 검증: 날짜별 목록·장소 수·순서·체류 시간·구간 이동 수단, 빈 날짜 표시, 일정 조회 실패가 여행 정보와 독립(`다시 시도`), 날짜 헤더 `장소 추가`와 `일정 편집` 콜백 호출, 장소 행 선택 시 F003 상세 콜백

### Implementation for User Story 3

- [ ] T026 [US3] ITIN-003 개요 endpoint·service in api/app/services/itinerary.py, api/app/api/v1/itinerary.py
  - 영역: BE
  - 담당: ts
  - 선행: T015, T024
  - 검증: T024 통과, 7일×10곳 응답 3초 이내(SC-002)
- [ ] T027 [US3] 여행 상세 일정 목록·`일정 편집`·날짜별 `장소 추가` in android/app/src/main/java/com/gilpick/trip/TripDetailScreen.kt, android/app/src/main/java/com/gilpick/trip/TripDetailViewModel.kt
  - 영역: FE
  - 담당: hs
  - 선행: T008, T025, T002
  - 검증: Figma `TripDetailScreen` 일정 영역 대조, `오늘 여행 시작`·총 이동 시간은 값 없이 비활성/`정보 없음`, 네 상태, 48dp, 360dp·font scale 2.0. F002 화면 소유자(hs) 작업이며 `ItineraryEditRoute`(T018) 시그니처를 사용
- [ ] T028 [US3] 상세 → 편집·검색 진입과 장소 행 → F003 상세 navigation 연결 in android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T018, T027
  - 검증: Navigation test — `일정 편집` → 첫 날짜 편집, 날짜 헤더 `장소 추가` → 그 날짜 `openSearch`, 장소 행 → `PlaceDetailRoute` → 뒤로 → 상세 유지

**Checkpoint**: 여행 상세만으로 일정 조회와 편집·검색 진입이 가능하다.

---

## Phase 6: User Story 4 - 여행 기간 축소 시 일정 정리 (Priority: P4)

**Goal**: 기간 축소 시 실제 삭제 대상 수를 안내하고 동의한 경우에만 삭제한다.

**Independent Test**: 3일차에 항목이 있는 여행을 2일로 줄이면 `deletedItemCount 1`, 확인 없는 요청은 거부, 확인한 요청만 3일차를 지운다(spec US4).

### Tests for User Story 4

- [ ] T029 [P] [US4] F002 PATCH 기간 축소 contract·integration test 수정 in api/tests/contract/test_trip_contract.py, api/tests/integration/test_itinerary_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T014
  - 검증: quickstart BE 9(`deletedItemCount` 실제 값, 0건이면 확인 생략 `200`, 확인 시 범위 밖 `trip_days`·항목 삭제와 나머지 유지)
- [ ] T030 [P] [US4] 여행 수정 화면 삭제 확인 대화상자 test in android/app/src/test/java/com/gilpick/trip/TripFormValidationTest.kt, android/app/src/androidTest/java/com/gilpick/trip/TripEditFlowTest.kt
  - 영역: FE
  - 담당: hs
  - 선행: 없음
  - 검증: `409 CONFIRMATION_REQUIRED` 수신 시 `삭제될 장소 N곳` 대화상자, 동의 시 `confirmDeleteOutOfRangeItems=true` 재요청, 취소 시 미저장·기간 유지

### Implementation for User Story 4

- [ ] T031 [US4] `update_trip` 기간 축소 삭제 대상 계산·삭제 in api/app/services/trip.py, api/app/api/v1/trips.py
  - 영역: BE
  - 담당: ts
  - 선행: T014, T029
  - 검증: T029 통과, 삭제된 날짜·항목 수와 여행 version 전후 log, 같은 transaction. F002 코드 수정이므로 `jh` review
- [ ] T032 [US4] 여행 수정 화면 `삭제될 장소 N곳` 대화상자와 동의 재요청 in android/app/src/main/java/com/gilpick/trip/TripFormViewModel.kt, android/app/src/main/java/com/gilpick/trip/TripFormScreen.kt
  - 영역: FE
  - 담당: hs
  - 선행: T030, T002
  - 검증: T030 통과, Figma `EditTripScreen` 대화상자 대조, 48dp

**Checkpoint**: 기간 축소가 실제 일정과 맞물려 동작하고 #178에서 발견한 확인 UI 부재가 닫힌다.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T033 Backend F004 전체 자동 test와 문서 동기화 in docs/design/api-spec.md, api/tests/
  - 영역: BE
  - 담당: ts
  - 선행: T021, T026, T031
  - 검증: quickstart Backend 명령 전체 통과, `api-spec.md` 5.1·ITIN-001·002·003·TRIP-004 갱신(`place` 스냅샷, `staySource`, `NOT_CALCULATED`, 오류 code), `compileall`, `git diff --check`, 공개 함수 docstring
- [ ] T034 Android F004 전체 자동 test·build와 AVD 검증 in android/app/src/test/java/com/gilpick/itinerary/, android/app/src/androidTest/java/com/gilpick/itinerary/
  - 영역: FE
  - 담당: jy
  - 선행: T023, T028, T032
  - 검증: `testDebugUnitTest`, `connectedDebugAndroidTest`(itinerary·trip), `assembleDebug`, 필수 KDoc, quickstart Android 수동 6항목 스크린샷(4상태, 10곳·긴 장소명, 처리된 항목 fixture, 취소 확인, 기간 축소 대화상자, 360dp·font scale 2.0)
- [ ] T035 실서버 종단간 검증과 계약 최종 동기화 in specs/004-itinerary-editing/quickstart.md, specs/004-itinerary-editing/contracts/itinerary.openapi.yaml
  - 영역: 통합
  - 담당: ts
  - 교차 확인: jy
  - 선행: T033, T034
  - 검증: 로컬 API + AVD로 quickstart 수동 1~5(추가·편집·저장, 두 세션 충돌 자동 재저장, 10곳 상한, 기간 축소), 수동 1번의 상세 진입 → 저장 소요 시간을 기록해 SC-001(5분) 확인, enum·nullable·오류 code·`place` 스냅샷이 양쪽 구현과 일치하는지 BE `ts`와 FE `jy`가 교차 확인 기록

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup(T001~T003) → Foundational(T004~T009) → US1(T010~T018) → US2(T019~T023) → US3(T024~T028) → US4(T029~T032) → Polish(T033~T035)
- US4의 T029·T030·T032는 T014 이후 US2·US3와 병렬 진행할 수 있다. T031만 T014에 의존한다.

### User Story Dependencies

```text
Setup → Foundational → US1 장소 추가·저장 → US2 편집 조작 → US3 상세 조회·진입 → US4 기간 축소 → Polish
```

- **US1**: F004 MVP. F002 소유권 dependency와 F003 `PlaceDto`·`AddToScheduleRequest`를 그대로 사용한다.
- **US2**: US1의 저장 service와 편집 화면 위에 조작과 잠금을 얹는다.
- **US3**: hs의 T025·T027은 T008(Repository) 이후 시작할 수 있고, T028 연결은 jy의 T018이 필요하다.
- **US4**: 서버 계산은 T014의 항목 조회를 쓰고, 앱 대화상자(T030·T032)는 독립적이다.
- **F005**: F004의 `NOT_CALCULATED`를 `READY`·`FAILED`로 확장하고 저장 후 경로 계산을 붙인다.

### Parallel Opportunities

- T001·T002·T003은 파일이 분리되어 병렬 가능하다.
- T005·T006·T007은 BE 모델·schema와 FE API 파일이 달라 병렬 가능하다.
- T010~T013은 계약 확정 후 BE test와 FE test를 병렬 작성한다.
- T025·T027(hs)은 T008 이후 jy의 T016·T017과 병렬 진행한다.
- 같은 `itinerary.py`(service·router), `ItineraryEditScreen.kt`, `MainActivity.kt`, `services/trip.py`를 수정하는 task는 병렬 처리하지 않는다.

## Parallel Examples

### User Story 1

```text
BE ts: T010 contract test + T011 service unit test
FE jy: T012 ViewModel test + T013 Compose UI test
```

### User Story 3 / 4

```text
FE hs: T025 상세 test → T027 상세 화면 / T030 대화상자 test → T032 대화상자
FE jy: T016~T018 편집 화면·route (동시)
BE ts: T024 ITIN-003 test → T026 / T029 PATCH test → T031
```

## Implementation Strategy

### MVP First

1. Setup·Foundational로 migration·계약·Repository를 고정한다.
2. US1을 끝내면 편집 화면 단독으로 장소 추가·저장이 되어 실서버 검증이 가능하다.
3. US2 → US3 → US4 순으로 얹고 Polish에서 전체 검증과 문서 동기화를 마친다.

### Issue Grouping Guidance

- BE(ts): (a) T001·T004~T006·T009 기반, (b) T010·T011·T014·T015 US1, (c) T019·T021·T024·T026 US2·US3, (d) T029·T031 US4, (e) T033 검증
- FE jy: (a) T002·T007·T008 기반, (b) T012·T013·T016~T018 편집 화면·navigation, (c) T020·T022·T023 편집 조작, (d) T028 상세 연결, (e) T034 검증
- FE hs: (a) T025·T027 여행 상세 일정 영역, (b) T030·T032 여행 수정 삭제 확인
- 통합(ts+jy): T003 사전 교차 review, T035 최종 검증

## Notes

- T018·T028의 `MainActivity.kt`와 T031의 `services/trip.py`는 다른 Feature 파일을 건드리므로 원 담당자 review를 받는다.
- 자동 재저장(T016)은 사용자 결정(spec Clarifications 2026-09-05)이며 안내 없이 덮어쓴다. 문제가 확인되면 안내 후 재저장으로 바꾼다.
- 스크린리더 대응은 범위 밖이되 기존 semantics·`contentDescription`·48dp 최저선은 유지한다.
