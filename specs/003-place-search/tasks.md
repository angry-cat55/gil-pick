---

description: "F003 장소 검색 구현 task 목록"
---

# Tasks: 장소 검색

**Input**: `/specs/003-place-search/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**담당 합의**: Feature Owner `ts`, Backend `ts`, Frontend Android `jy`

**Tests**: 외부 API 실패와 사용자 화면 상태가 핵심 요구사항이므로 contract·unit·integration·Compose UI test를 포함한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일을 수정하고 미해결 선행 관계가 없어 병렬 수행 가능
- **[Story]**: 해당 user story 식별자
- 각 task의 보조 메타데이터에 영역, 담당, 선행 관계와 검증 방법을 기록한다.

## Phase 1: Setup

**Purpose**: TourAPI·Google Places와 Android 화면 구현에 필요한 최소 환경을 준비한다.

- [x] T001 [P] TourAPI·Google Places base URL·service key·timeout 설정 추가 in api/app/core/config.py, api/.env.example
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: 설정 unit test에서 기본 URL·5초 timeout을 확인하고 service key가 `SecretStr`로만 노출되는지 검토
- [x] T002 [P] Navigation Compose와 Coil 의존성 추가 in android/gradle/libs.versions.toml, android/app/build.gradle.kts
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: `android\gradlew.bat -p android :app:dependencies`와 `:app:compileDebugKotlin` 성공
- [x] T003 TourAPI·Google Places 최신 공식 계약과 fixture로 endpoint·분류·field mask·attribution 확정 in api/tests/fixtures/tour_api/, api/tests/fixtures/google_places/
  - 영역: BE
  - 담당: ts
  - 선행: T001
  - 검증: TourAPI 검색·상세 endpoint와 Google Text Search·Details 최소 field mask·attribution·빈 필드 fixture를 공식 문서 및 공유 개발계정에서 대조하고 credential을 산출물에 남기지 않음

---

## Phase 2: Foundational

**Purpose**: 모든 user story가 공유하는 API 계약, 외부 client와 Android navigation 기반을 마련한다.

**⚠️ CRITICAL**: 이 phase가 완료되어야 user story 구현을 시작할 수 있다.

- [x] T035 Backend·Android 구현 전 장소 계약 교차 review in specs/003-place-search/contracts/places.openapi.yaml, specs/003-place-search/data-model.md, specs/003-place-search/plan.md
  - 영역: 통합
  - 담당: ts
  - 선행: T003
  - 검증: place DTO, category enum, nullable field, cursor, provider별 오류 code, Navigation Compose 2.10.0, route 등록 범위와 F004 진입점 이관을 BE `ts`와 FE `jy`가 확인한 기록을 남기고 불일치를 구현 전에 문서에 반영
- [x] T004 Backend 다중 provider 장소 DTO·6개 category·공통 envelope 모델 구현 in api/app/schemas/place.py
  - 영역: BE
  - 담당: ts
  - 선행: T003, T035
  - 검증: `places.openapi.yaml`의 nullable field, enum, `tourapi:`·`google:` ID, Google 보강 필드, pagination 구조와 오류 원인별 `retryable` 값이 일치하는 schema test 통과
- [x] T005 TourAPI·Google Places HTTP client와 응답·오류 parsing 구현 in api/app/clients/tour_api.py, api/app/clients/google_places.py
  - 영역: BE
  - 담당: ts
  - 선행: T001, T003
  - 검증: MockTransport로 JSON 성공, 빈 응답, application error, timeout, 4xx, 5xx, quota 응답 parsing test 통과
- [x] T006 TourAPI·Google Places credential·URL query·원문 응답 redaction 적용 in api/app/core/logging.py
  - 영역: BE
  - 담당: ts
  - 선행: T005
  - 검증: caplog 기반 보안 test에서 service key와 provider 원문 body가 출력되지 않고 request ID와 내부 오류 code만 기록됨
- [x] T007 Backend 장소 router 등록과 인증 경계 연결 in api/app/api/v1/places.py, api/app/main.py
  - 영역: BE
  - 담당: ts
  - 선행: T004, F001 인증 API·`401 INVALID_ACCESS_TOKEN` 계약 확정
  - 검증: 인증 없음·만료 token 요청이 F001 공통 `401 INVALID_ACCESS_TOKEN` envelope를 반환하는 integration test 통과
- [x] T008 Android 장소 API DTO와 Retrofit interface 구현 in android/app/src/main/java/com/gilpick/place/PlaceApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T004, T035
  - 검증: MockWebServer serialization test에서 Backend enum, nullable field, pagination과 오류 envelope parsing 성공
- [x] T009 Android type-safe 장소 route를 app navigation graph에 등록 in android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T002, T035
  - 검증: navigation test에서 검색 → 상세 → 뒤로가기 route가 동작하고, 임시 진입 UI 없이 실제 일정 진입 연결을 F004로 이관했으며 F002 PR #154 반영 후 rebase하는 파일 소유권·merge 순서를 조율한 기록 확인

**Checkpoint**: Backend와 Android가 같은 계약을 사용하고 story별 구현을 시작할 수 있다.

---

## Phase 3: User Story 1 - 장소 검색 (Priority: P1) 🎯 MVP

**Goal**: 인증된 사용자가 키워드·category·지역 조건으로 명시적으로 검색하고 중복 없는 다음 결과를 조회한다.

**Independent Test**: 일정 저장이나 상세 조회 없이 검색 조건을 실행해 loading·empty·error·content와 cursor 추가 조회를 검증한다.

### Tests for User Story 1

- [x] T010 [P] [US1] PLACE-001 요청·응답 contract test 작성 in api/tests/contract/test_place_contract.py
  - 영역: BE
  - 담당: ts
  - 선행: T004, T007
  - 검증: query/category 단독·조합, trim 후 2글자, areaCode, limit, cursor, nullable field와 `400` 계약을 구현 전 실패 test로 확인
- [x] T011 [P] [US1] TourAPI 우선 routing·Google 부족분·매칭·cursor·중복 정책 unit test 작성 in api/tests/unit/test_place_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T004, T005
  - 검증: 6개 category·체류시간, 상업 category의 `limit` 부족분만 Google 호출, 50m·이름·주소 확정 매칭, 모호 후보 제외, 복합 cursor·변조 거부 test를 구현 전 실패로 확인
- [x] T012 [P] [US1] Android 검색 repository와 ViewModel unit test 작성 in android/app/src/test/java/com/gilpick/place/PlaceRepositoryTest.kt, android/app/src/test/java/com/gilpick/place/PlaceSearchViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008
  - 검증: 명시적 검색만 호출, draft/committed 조건 분리, 새 검색 교체, append 유지·dedupe·재시도 test를 구현 전 실패로 확인
- [x] T013 [P] [US1] 검색 화면 Compose UI test 작성 in android/app/src/androidTest/java/com/gilpick/place/PlaceSearchScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T002
  - 검증: loading·empty·error·content, skeleton, IME Search, 48dp target, semantics와 긴 text 시나리오를 구현 전 실패로 확인

### Implementation for User Story 1

- [x] T014 [US1] TourAPI 우선 검색·category mapping·조건부 Google 보완·확정 매칭·서명 cursor 구현 in api/app/services/place.py
  - 영역: BE
  - 담당: ts
  - 선행: T005, T010, T011, T035
  - 검증: TourAPI를 먼저 호출하고 상업 category의 정상 결과가 `limit` 미만일 때만 Google 부족분을 요청하며 mapping·매칭·cursor unit test 통과
- [x] T015 [US1] PLACE-001 validation과 검색 endpoint 구현 in api/app/api/v1/places.py
  - 영역: BE
  - 담당: ts
  - 선행: T007, T014
  - 검증: `test_place_contract.py`와 검색 정상·empty·다음 cursor integration test 통과, invalid 조건에서 TourAPI 미호출 확인
- [x] T016 [US1] Android 검색 repository와 UI state ViewModel 구현 in android/app/src/main/java/com/gilpick/place/PlaceRepository.kt, android/app/src/main/java/com/gilpick/place/PlaceSearchViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008, T012, T015
  - 검증: repository·ViewModel unit test에서 결과 교체, `placeId` dedupe, append 실패 시 기존 결과 유지와 재시도 통과
- [x] T017 [US1] Android 검색 화면과 결과 목록 구현 in android/app/src/main/java/com/gilpick/place/PlaceSearchScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T013, T016, T035
  - 검증: 기존 theme token만 사용하고 loading·empty·error·content, 1초 초과 skeleton, 이미지 fallback, live region, 48dp target을 UI test로 확인
- [x] T018 [US1] 검색 화면 실제 기기·접근성 검증 수행 against android/app/src/main/java/com/gilpick/place/PlaceSearchScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T017
  - 검증: 실제 기기 또는 AVD screenshot으로 네 상태·긴 장소명·이미지 누락·360dp·최대 font scale을 기록하고 TalkBack focus가 조건 → 요약 → 목록 순서인지 확인
  - 기록(2026-09-04, #142): AVD gilpick_api36_play에서 `PlaceSearchScreenTest` 14건·`PlaceNavigationTest` 3건 통과. `PlaceSearchScreenshotTest`가 idle/loading/content/empty/error/invalid, 긴 장소명·이미지 누락, 360dp, fontScale 2.0, 추가 조회 실패를 PNG로 남김(`adb pull /sdcard/Android/data/com.gilpick/files/screenshots`). focus 순서는 composition 순서(조건 → 요약 → 목록)로 두었고 TalkBack 실기기 수동 확인은 미수행
  - 기록(2026-09-05, #144): TalkBack(AVD gilpick_api36_play, TAB 이동·발화 log) 공지 순서: `뒤로 가기` → 검색어 입력(`장소 이름 검색`) → `검색어 지우기` → 카테고리 Tab 6개(selected 상태 공지) → 결과 행(`이름, 카테고리, 평점, 운영 상태`) → `일정에 추가`. 요약 `검색 결과 N곳`은 화면 진입 시 live region으로 공지. 360dp(`wm density 480`)·font scale 2.0(시스템 설정) live screenshot 기록

**Checkpoint**: 검색 화면만으로 F003의 P1 MVP가 독립 동작한다.

---

## Phase 4: User Story 2 - 장소 상세 조회 (Priority: P2)

**Goal**: 사용자가 검색 결과의 장소를 선택해 제공 가능한 상세를 확인하고 누락·not found를 구분한다.

**Independent Test**: 알려진 `placeId`와 존재하지 않는 ID를 직접 조회해 기본 정보와 nullable 상세가 정확히 표시되는지 검증한다.

### Tests for User Story 2

- [x] T019 [P] [US2] PLACE-002 TourAPI 기준 병합·Google 전용 상세 contract test 작성 in api/tests/contract/test_place_contract.py, api/tests/integration/test_place_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T004, T005
  - 검증: `tourapi:` 기준 상세·Google 허용 필드 병합, `google:` 전용 상세, nullable field·invalid ID·not found·사진·리뷰 제외를 구현 전 실패 test로 확인
- [x] T020 [P] [US2] Android 상세·Google 필드·attribution·provider 배지 미표시 test 작성 in android/app/src/test/java/com/gilpick/place/PlaceDetailViewModelTest.kt, android/app/src/androidTest/java/com/gilpick/place/PlaceDetailScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T008, T009
  - 검증: loading/content/notFound/error, 누락 안내, 검색 복귀와 뒤로가기 상태 보존을 구현 전 실패 test로 확인

### Implementation for User Story 2

- [x] T021 [US2] TourAPI 기준 상세·선택적 Google 보강·Google 전용 상세 정규화 구현 in api/app/services/place.py
  - 영역: BE
  - 담당: ts
  - 선행: T014, T019, T035
  - 검증: `detailCommon2`·`detailIntro2` fixture 조합, HTML plain text 처리, `openNow` 미생성, not found test 통과
- [x] T022 [US2] PLACE-002 상세 endpoint 구현 in api/app/api/v1/places.py
  - 영역: BE
  - 담당: ts
  - 선행: T021
  - 검증: 상세 contract·integration test와 `400/401/404/429/502/504` envelope 검증 통과
- [x] T023 [US2] Android 상세 repository와 ViewModel 구현 in android/app/src/main/java/com/gilpick/place/PlaceRepository.kt, android/app/src/main/java/com/gilpick/place/PlaceDetailViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T016, T020, T022
  - 검증: nullable field, not found, retryable 오류 mapping과 destination-scoped 상태 test 통과
- [x] T024 [US2] Android 상세 화면과 검색 결과 진입 연결 구현 in android/app/src/main/java/com/gilpick/place/PlaceDetailScreen.kt, android/app/src/main/java/com/gilpick/place/PlaceSearchScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T017, T023
  - 검증: 검색 행 전체 선택, 상세 네 상태, 정보 없음 안내, 이미지 접근성, 뒤로가기 후 조건·결과·scroll 유지 UI test 통과
- [x] T025 [US2] 상세 화면 실제 기기·접근성 검증 수행 against android/app/src/main/java/com/gilpick/place/PlaceDetailScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T024
  - 검증: 실제 기기 또는 AVD screenshot으로 네 상태·긴 설명·필드 누락·360dp·최대 font scale·TalkBack·뒤로가기 상태를 기록
  - 기록(2026-09-04, #139): AVD gilpick_api36에서 `PlaceDetailScreenTest` 10건·`PlaceNavigationTest` 3건 통과. `PlaceDetailScreenshotTest`가 loading/content/notFound/error, 긴 설명, 필드·이미지 누락, fontScale 2.0을 PNG로 남김(`adb pull /sdcard/Android/data/com.gilpick/files/screenshots`). 360dp 실기기·TalkBack 수동 확인은 미수행
  - 기록(2026-09-05, #144): TalkBack(AVD gilpick_api36_play) content 진입 시 `대표 사진 없음`(이미지 fallback) 공지 후 `뒤로 가기` → `찜` → `지도에서 보기` → `일정에 추가` 순서. notFound는 `검색 결과로 돌아가기` 버튼 공지. 360dp·font scale 2.0 live screenshot 기록. font scale 2.0에서 운영시간 stat `09:00~18:00`이 `~` 뒤가 아닌 숫자 중간에서 줄바꿈됨(잘림 없음, 확인 필요)

**Checkpoint**: 검색과 상세가 각각 독립 검증 가능하며 같은 장소 계약을 사용한다.

---

## Phase 5: User Story 3 - 외부 서비스 장애에서 복구 (Priority: P3)

**Goal**: TourAPI 장애·시간 초과·호출 제한을 정상 empty와 구분하고 안전하게 재시도한다.

**Independent Test**: mock provider로 각 오류를 재현해 자동 재시도 횟수, API 오류 code와 화면 복구 행동을 검증한다.

### Tests for User Story 3

- [x] T026 [P] [US3] TourAPI·Google Places retry·오류 분류·redaction unit test 작성 in api/tests/unit/test_tour_api_client.py, api/tests/unit/test_google_places_client.py
  - 영역: BE
  - 담당: ts
  - 선행: T005, T006
  - 검증: 두 provider의 timeout·일시적 5xx만 1회 재시도하고 application·request·auth·quota/rate limit은 재시도하지 않으며 Google 실패 시 TourAPI 결과가 유지되는 실패 test 확인
- [x] T027 [P] [US3] Android 검색·상세 장애 복구 UI test 보강 in android/app/src/test/java/com/gilpick/place/PlaceSearchViewModelTest.kt, android/app/src/test/java/com/gilpick/place/PlaceDetailViewModelTest.kt, android/app/src/androidTest/java/com/gilpick/place/PlaceSearchScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T016, T023
  - 검증: timeout·provider 장애·rate limit·인증 만료의 오류 code별·원인별 `retryable` 안내, initial retry와 append retry 분리, 기존 결과 유지 test를 구현 전 실패로 확인

### Implementation for User Story 3

- [x] T028 [US3] provider별 5초 timeout·최대 1회 retry와 Google 부분 실패 격리 적용 in api/app/clients/tour_api.py, api/app/clients/google_places.py, api/app/services/place.py
  - 영역: BE
  - 담당: ts
  - 선행: T021, T026
  - 검증: client·service·integration test에서 provider별 timeout·failed·rate limited와 Google 부분 실패 격리 및 실제 호출 횟수 통과
- [x] T029 [US3] Android 검색·상세 오류별 복구 행동과 인증 만료 연결 구현 in android/app/src/main/java/com/gilpick/place/PlaceRepository.kt, android/app/src/main/java/com/gilpick/place/PlaceSearchViewModel.kt, android/app/src/main/java/com/gilpick/place/PlaceDetailViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T027, T028
  - 검증: 장애를 empty로 표시하지 않고 initial·append·detail retry 및 F001 재인증 흐름 test 통과
- [x] T030 [US3] 장애 상태 접근성·실기기 검증 수행 against android/app/src/main/java/com/gilpick/place/PlaceSearchScreen.kt, android/app/src/main/java/com/gilpick/place/PlaceDetailScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T029
  - 검증: timeout·rate limit·append 실패 메시지와 재시도 control이 TalkBack에 공지되고 기존 결과가 유지되는 screenshot·수동 검증 기록
  - 기록(2026-09-05, #144): TalkBack(AVD gilpick_api36_play) 화면 진입 시 live region 공지 확인 — timeout: `장소 정보 제공이 지연되고 있어요… 다시 시도, Button` / rate limit: 메시지만 공지(재시도 버튼 없음, 설계대로) / 인증 만료: `로그인 상태가 만료되었어요… 다시 로그인, Button` / 추가 조회 실패: `다음 결과를 불러오지 못했어요… 다음 결과 다시 시도, Button` 뒤에 `검색 결과 4곳`과 기존 결과 행이 그대로 공지됨. 상세 error·인증 만료도 같은 메시지·버튼 공지

**Checkpoint**: 정상·empty·외부 장애·인증·호출 제한이 서로 구분되고 복구 가능하다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 전체 계약, 보안, 문서와 실제 검증 결과를 최종 정합화한다.

- [x] T031 Backend F003 전체 자동 test와 정적 검증 실행 against api/tests/contract/test_place_contract.py, api/tests/unit/test_tour_api_client.py, api/tests/unit/test_google_places_client.py, api/tests/unit/test_place_service.py, api/tests/integration/test_place_flow.py
  - 영역: BE
  - 담당: ts
  - 선행: T015, T022, T028
  - 검증: `quickstart.md`의 Backend pytest와 저장소 lint·type check를 실행하고 필수 Google-style docstring을 확인한 뒤 성공·실패·미실행 사유 기록
- [x] T032 Android F003 전체 자동 test와 build 검증 실행 against android/app/src/test/java/com/gilpick/place/, android/app/src/androidTest/java/com/gilpick/place/
  - 영역: FE
  - 담당: jy
  - 선행: T018, T025, T030
  - 검증: `testDebugUnitTest`, `connectedDebugAndroidTest`, `assembleDebug`, 필수 KDoc 확인 결과와 실제 기기·screenshot 증빙 기록
  - 기록(2026-09-05, #144): origin/main 774b89a에서 `testDebugUnitTest` 203건(place 45건) 통과, `assembleDebug` 성공, `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.place` 50건 통과(AVD gilpick_api36_play: PlaceSearchScreenTest 16·PlaceDetailScreenTest 13·PlaceNavigationTest 3·screenshot 18장). `place/` 공개 선언 KDoc 누락 없음. TalkBack·360dp·font scale 기록은 T018·T025·T030 참조. 실서버 연동은 TourAPI·Google 자격이 없어 미수행(T034)
- [ ] T033 Backend·Android 다중 provider 계약과 문서 최종 동기화 in specs/003-place-search/contracts/places.openapi.yaml, docs/design/api-spec.md, specs/003-place-search/quickstart.md
  - 영역: 통합
  - 담당: ts
  - 선행: T031, T032
  - 검증: enum·nullable field·cursor·provider별 오류 code·Google 보완 필드·attribution이 양쪽 구현과 일치하고 DB 저장·Google 사진·리뷰가 포함되지 않았음을 BE `ts`와 FE `jy`가 교차 확인
- [ ] T034 TourAPI·Google Places 공유 환경 smoke test와 quota·billing·attribution 확인 against specs/003-place-search/quickstart.md
  - 영역: 통합
  - 담당: ts
  - 선행: T031, TourAPI·Google Places credential·billing 환경 준비 Issue
  - 검증: TourAPI 정상·empty·상세·cursor와 Google 조건부 보완·확정 매칭·부분 실패·attribution을 확인하고 5초·10초·provider별 11초 경계를 기록하며, 환경 미준비 시 통과로 표시하지 않음

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup**: 즉시 시작 가능하며 T001과 T002는 병렬 수행 가능하다.
- **Foundational**: Setup에 의존하며 Backend 계약 T004가 Android 계약 T008보다 먼저다. story 구현은 T035 교차 review 완료 후 시작한다.
- **US1**: Foundational 완료 후 시작하는 P1 MVP다.
- **US2**: 공통 계약 이후 test 작성은 가능하지만, 검색 결과 진입과 repository를 재사용하므로 US1 구현에 의존한다.
- **US3**: 외부 client 기반 test는 Foundational 이후 가능하고 최종 적용은 US1·US2 서비스와 ViewModel에 의존한다.
- **Polish**: 선택한 모든 story와 검증이 완료된 뒤 수행한다.

### User Story Dependencies

```text
Setup → Foundational → US1 검색 → US2 상세 → US3 장애 복구 → Polish
```

- **US1**: F003 MVP이며 F001 인증 API와 오류 계약 확정 후 mock 기반 구현을 시작할 수 있다. 실제 인증 통합 검증은 해당 계약에 의존한다.
- **US2**: API 자체는 독립 조회 가능하지만 Android 진입·상태 보존은 US1 화면과 navigation을 사용한다.
- **US3**: US1과 US2의 외부 호출·화면 상태에 오류 처리를 적용한다.
- **F004**: F003의 안정적인 `placeId`와 장소 DTO가 완료된 뒤 일정 추가 기능에서 소비한다.

### Parallel Opportunities

- T001과 T002는 Backend·Android 파일 소유권이 분리되어 병렬 가능하다.
- T010~T013은 기반 계약 완료 후 BE test와 FE test를 병렬 작성할 수 있다.
- T019와 T020은 상세 API·Android test 파일이 달라 병렬 가능하다.
- T026과 T027은 Backend retry test와 Android 복구 test가 분리되어 병렬 가능하다.
- 같은 `places.py`, `place.py`, `PlaceRepository.kt`, `MainActivity.kt`를 수정하는 task는 병렬 처리하지 않는다.

## Parallel Examples

### User Story 1

```text
BE ts: T010 PLACE-001 contract test + T011 service unit test
FE jy: T012 repository/ViewModel test + T013 Compose UI test
```

### User Story 2

```text
BE ts: T019 PLACE-002 contract·integration test
FE jy: T020 상세 ViewModel·화면 test
```

### User Story 3

```text
BE ts: T026 TourAPI retry·오류 분류 test
FE jy: T027 Android 오류 복구 test
```

## Implementation Strategy

### MVP First

1. T001~T009 Setup·Foundational과 T035 구현 전 계약 교차 review 완료
2. T010~T018 US1 장소 검색·조건부 Google 보완 구현·검증
3. 검색 결과, empty, pagination과 접근성을 독립 시연
4. US2 상세와 US3 장애 복구는 후속 증분으로 추가

### Issue Grouping Guidance

- BE setup·공통 client와 계약 기반은 `ts`의 하나의 기반 Issue로 묶을 수 있다.
- US1·US2·US3 Backend 구현은 외부 계약과 파일 소유권이 겹치므로 순차 Issue 또는 명확한 선행 관계로 분리한다.
- Android setup·navigation, US1 검색, US2 상세, US3 장애 복구는 `jy`가 각각 독립 review 가능한 Issue로 나눈다.
- T033은 양쪽 구현 완료 후 통합 계약 검증 Issue로 두고 `ts`가 수행하며 `jy`의 교차 확인을 완료 조건에 둔다.
- T034는 TourAPI·Google Places credential·billing 환경 준비 Issue에 `blocked by`를 기록하며, mock 기반 구현 전체를 차단하지 않는다.

## Notes

- `[P]`는 다른 파일과 계약을 독립적으로 다룰 때만 표시했다.
- 구현 test는 먼저 실패를 확인한 뒤 해당 구현 task를 진행한다.
- 화면 완료에는 loading·empty·error·content, 접근성, 360dp·최대 font scale과 실제 기기 또는 screenshot 검증이 포함된다.
- F003은 검색 결과를 DB·Redis에 저장하지 않고 Google Places는 음식·카페·쇼핑 부족분과 평점·영업정보에만 사용한다. 사진·리뷰와 장소별 provider 배지는 제외하고 필수 attribution은 유지한다.
- 구현 중 API나 UI 계약이 바뀌면 관련 문서를 동기화하고 `speckit-analyze`를 다시 수행한다.
