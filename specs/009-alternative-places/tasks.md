---

description: "F009 대체 장소 추천 구현 task 목록"
---

# Tasks: 대체 장소 추천

**Input**: `specs/009-alternative-places/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/alternatives.openapi.yaml`, `quickstart.md`

**Organization**: Feature Owner는 `jy`(명세 산출물)다. Backend 구현 담당은 `ts`, Frontend(Android) 구현 담당은 `jy`다(2026-09-09 합의). F008 파일(`api/app/api/v1/detections.py`, `api/app/schemas/detection.py`, `api/app/services/detection/operating_hours_source.py`) 수정은 F008 구현 담당 `jh` review를, F003 `api/app/services/place.py` 수정은 F003 담당(`ts` 본인)이 맡는다. Android의 `progress/*`(F007 최근 수정자 `hs`)·`route/RouteMap.kt`·`place/PlaceSearchScreen.kt` 수정은 `hs` review를 받는다. 테스트는 명세의 독립 검증·성공 기준과 constitution 품질 게이트를 만족하도록 구현보다 먼저 배치한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 미완료 선행 작업이 없고 수정 파일이 다른 작업
- **[Story]**: 해당 User Story 추적 표기
- 보조 메타데이터의 선행·검증 조건까지 완료해야 task 완료로 본다.

## Path Conventions

- Backend: `api/app/`, `api/tests/`
- Android: `android/app/src/main/java/com/gilpick/`, `android/app/src/test/`, `android/app/src/androidTest/`
- 문서: `docs/design/`, `docs/planning/`, `specs/009-alternative-places/`

---

## Phase 1: Setup

**Purpose**: 계약을 공용 문서와 대조해 고정하고, 조정 가능한 정책값과 Figma 기준을 확인한다.

- [x] T001 F009 ALT·DETECT-004 계약·ERD·API 명세 대조와 교차 review in specs/009-alternative-places/contracts/alternatives.openapi.yaml, docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: 통합
  - 담당: ts
  - 교차 확인: jy
  - 선행: 없음
  - 검증: `alternatives.openapi.yaml`의 ALT-001·ALT-002 경로가 `api-spec.md` §7.2와 일치하고 응답 구조 차이(`place` 중첩, `candidateId`, `operatingStatus`, `closesAt`, `categoryMatchLevel`, `reasons`, `inSchedule`)와 DETECT-004 신설, DETECT-001 확장(`status` 필터, `eta`·`reason`)을 목록화. `er-schema.md` §8.1(`resolved_at` 거절 시각 재사용, 테이블 추가 없음)과 §13 매핑 추가 대상을 대조해 PR에 기록. 실제 문서 반영은 T035
- [x] T002 [P] 추천 정책 모듈 in api/app/services/alternatives/policy.py, api/app/services/alternatives/__init__.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: 반경 사다리 `(500, 1000, 2000)`, 가중치 `distance 0.35 / rating 0.30 / congestion 0.20 / weather 0.15`, 혼잡 점수표 `RELAXED 1.0 / NORMAL 0.75 / SLIGHTLY_CROWDED 0.5 / crowded 0.0`, 베이지안 `m` 기본 20·후보 5개 미만 조건, `MAX_CANDIDATES=10`, `OPERATING_CHECK_LIMIT=20`, `CANDIDATE_TTL_MINUTES=15`, TourAPI `numOfRows=100`를 이 모듈에서만 읽는다. `api/tests/unit/test_alternative_scoring.py`(T012)에서 값 접근 확인. 근거는 research.md R3·R4·R5
- [x] T003 [P] Figma 기준 확인과 부족 요소 기록 in specs/009-alternative-places/plan.md
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: Figma Make `AlternativePlacesScreen`·`alternativesEmpty`·`ActiveTravelScreen` 배너를 재조회해 저장소 사본과 차이가 없는지 확인하고, spec UI-003의 "이동 시간 비교 문구 미표시"와 `운영시간 확인 불가`·`이미 일정에 있음`·`방문 불가` 표시처럼 Figma에 없는 상태 표현을 `plan.md` UI 절에 표로 기록. Figma 변경이 필요하면 Owner가 Figma Make에서 반영 후 사본을 갱신

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 User Story가 공유하는 client 메서드, 재사용 helper, 스키마, 토큰, Android API 계층을 만든다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 User Story 구현을 시작하지 않는다.

- [x] T004 [P] TourAPI 위치 기반 목록 client 메서드 in api/app/clients/tour_api.py, api/tests/unit/test_tour_api_client.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `search_by_location(**params)`가 `locationBasedList2`를 호출하고 기존 `_get`의 5초·1회 재시도·오류 변환을 그대로 쓴다. mock transport로 `mapX`·`mapY`·`radius`·`lclsSystm1~3`·`numOfRows` 전달과 `dist` 필드 보존을 확인
- [x] T005 [P] F003 PlaceService helper 공개 승격 in api/app/services/place.py, api/tests/unit/test_place_service.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `_tour_place`·`_google_place`·`_find_match`·`_merge_google`·`_distance`·`_category`를 모듈 수준 공개 함수(`tour_place`, `google_place`, `find_match`, `merge_google`, `distance_meters`, `category_of`)로 옮기고 `PlaceService`는 그 함수를 호출한다. 기존 `test_place_service.py`·`test_place_router.py`가 변경 없이 통과(외부 동작 불변)
- [x] T006 [P] F008 OperatingHoursSource parse 분리 in api/app/services/detection/operating_hours_source.py, api/tests/unit/test_operating_hours_source.py
  - 영역: BE
  - 담당: ts
  - 교차 확인: jh
  - 선행: 없음
  - 검증: `parse(payload, eta) -> OperatingHours`를 분리하고 `get`은 `parse(await client.get_place(place_id), eta)`로 유지. 기존 `test_operating_hours_source.py`가 변경 없이 통과하고, Text Search 항목 payload(`regularOpeningHours`·`businessStatus`·`utcOffsetMinutes`)에도 같은 결과를 내는 test 1건 추가
- [x] T007 [P] ALT 공개 스키마·오류 코드 in api/app/schemas/alternatives.py, api/tests/unit/test_alternatives_schema.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `AlternativeCandidate`·`AlternativeListData`·`AlternativeSearchItem`·`ScoreBreakdown`·`OperatingStatus`·`DetectionDismissData`와 봉투, 오류 코드 `DETECTION_NOT_ACTIVE`·`INVALID_CANDIDATE`가 `contracts/alternatives.openapi.yaml`과 필드·enum이 일치(camelCase alias, `place`는 F003 `PlaceSummary` 재사용)
- [x] T008 [P] 후보 식별자 토큰 in api/app/services/alternatives/candidate_token.py, api/tests/unit/test_candidate_token.py
  - 영역: BE
  - 담당: ts
  - 선행: T002
  - 검증: `issue_candidate_token(detection_id, place_id, evaluated_at, secret)`·`verify_candidate_token(token, secret, now) -> CandidateClaims | None`. 서명 변조·만료(15분 초과)·형식 오류는 `None`, 정상은 claims 일치(quickstart BE 8)
- [x] T009 DETECT-001 확장과 소유권 helper 공용화 in api/app/api/v1/detections.py, api/app/schemas/detection.py, api/tests/contract/test_detections_contract.py
  - 영역: BE
  - 담당: ts
  - 교차 확인: jh
  - 선행: T001
  - 검증: `DetectionListItem`에 `eta`·`reason` 추가, `status` query 필터(선택), `_owned_detection`을 `owned_detection`으로 공개해 ALT·DETECT-004가 재사용. 기존 계약 test가 통과하고 필터·필드 test 추가(quickstart BE 9)
- [x] T010 [P] Android ALT·DETECT DTO와 Retrofit service in android/app/src/main/java/com/gilpick/alternative/AlternativeApi.kt, android/app/src/test/java/com/gilpick/alternative/AlternativeApiTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T001
  - 검증: `DetectionListItemDto`(`eta`·`reason` 포함)·`DetectionDetailDto`·`AlternativeCandidateDto`·`AlternativeListDto`·`AlternativeSearchItemDto`·`DismissResultDto`·`SelectedAlternative`와 `AlternativeService`(DETECT-001 `status` 파라미터, DETECT-002, DETECT-004, ALT-001, ALT-002), `createAlternativeRetrofit`(`ProgressApi` 패턴, 인증 인터셉터 재사용). 계약 예시 JSON 역직렬화 test, `place`는 F003 `PlaceDto` 재사용
- [x] T011 [P] Android AlternativeRepository in android/app/src/main/java/com/gilpick/alternative/AlternativeRepository.kt, android/app/src/test/java/com/gilpick/alternative/AlternativeRepositoryTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T010
  - 검증: `AlternativeError`(`SessionExpired`·`NotActive(status)`·`NotFound`·`ProviderFailed(retryable)`·`Network`)로 401/404/409/502/504/IO 매핑, `Result` 반환. mock service로 각 매핑 test

---

## Phase 3: User Story 1 - 방문이 어려워진 장소의 대체 후보를 추천받는다 (Priority: P1) 🎯 MVP

**Goal**: `ACTIVE` 감지 결과에 대해 ALT-001이 반경 사다리·분류 비교·운영 종료 제외·점수 정렬로 최대 10개 후보를 반환한다.

**Independent Test**: quickstart BE 1·2·3·5·8. 후보 12곳 fixture로 상위 10개·정렬·반경·분류 단계·후보 없음·409·403을 확인한다.

### Tests for User Story 1

- [x] T012 [P] [US1] 점수 산출 unit test in api/tests/unit/test_alternative_scoring.py
  - 영역: BE
  - 담당: ts
  - 선행: T002
  - 검증: 거리·혼잡·날씨·평점 정규화 값, 결손 가중치 비례 재분배(예: rating·weather 제외 시 `(0.35d+0.20c)/0.55`), 베이지안 `m` 중앙값·`m=20` 조건, 동점 규칙(거리→보정 평점→리뷰 수), `displayScore=round(score)`이며 정렬은 원점수, `reasons` 코드 생성 규칙을 검증. 구현 전 실패 확인
- [x] T013 [P] [US1] 후보 파이프라인 unit test in api/tests/unit/test_alternative_candidates.py
  - 영역: BE
  - 담당: ts
  - 선행: T002, T004, T005, T006
  - 검증: TourAPI·Google mock transport로 (a) 500m 소분류 일치 → `SMALL`·500, (b) 800m 중분류만 → `MIDDLE`·1000, (c) 2km 없음 → `NONE`·2000·`items=[]`, (d) Google 전용 기존 장소 → `LARGE`, (e) 기존 장소·같은 날짜 장소 제외, (f) 폐점·임시휴업 제외 후 10개 채움, (g) 운영시간 없음 → `UNKNOWN` 유지, (h) 상위 20곳 모두 폐점 → Google 호출 20회에서 중단·빈 목록·반경 유지, (i) 기상청은 1회만 호출·서울시는 구역별 메모이즈. 구현 전 실패 확인
- [x] T014 [P] [US1] ALT-001 계약 test in api/tests/contract/test_alternatives_contract.py
  - 영역: BE
  - 담당: ts
  - 선행: T007, T009
  - 검증: 200 응답이 `AlternativeListData` 스키마와 일치, `ACTIVE` 아님 → 409 `DETECTION_NOT_ACTIVE`+`details.status`, 타인 → 403/404, TourAPI timeout → 504·5xx → 502(빈 목록 아님), 호출 전후 `itinerary_items`·`routes`·`detections` 불변. 구현 전 실패 확인

### Implementation for User Story 1

- [x] T015 [US1] 점수 산출 모듈 in api/app/services/alternatives/scoring.py
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: T012 통과. 정규화·재분배·베이지안·동점·표시 점수·`reasons`(INDOOR·NOT_CROWDED·NO_RAIN_RISK·CLOSER·OPEN_AT_ETA)를 `policy.py` 값으로만 계산
- [x] T016 [US1] 후보 파이프라인 in api/app/services/alternatives/candidates.py
  - 영역: BE
  - 담당: ts
  - 선행: T013, T015, T008
  - 검증: T013 통과. research R1~R3 순서(TourAPI 2km 1회 → 사다리·소→중→대 → 제외 → 예비 점수 → 상위부터 Text Search 매칭·`parse` 운영 판정·상한 → 최종 점수 → 10개 → `candidateId` 발급). `evaluate_weather` 1회·`evaluate_congestion` 구역 메모이즈. Google·기상청·서울시 예외는 변수 제외로 격리(US4에서 test 보강)
- [x] T017 [US1] AlternativeService.list_candidates와 ALT-001 endpoint in api/app/services/alternatives/__init__.py, api/app/api/v1/alternatives.py, api/app/main.py
  - 영역: BE
  - 담당: ts
  - 선행: T014, T016
  - 검증: T014 통과. `owned_detection` 재사용, `ACTIVE` 검사 → 409, TourAPI 최종 실패 → 502/504, 감지 id·반경·분류 단계·후보 수·provider 가용성 log(정밀 위치·토큰 원문 미기록). router를 `main.py`에 등록

**Checkpoint**: US1 완료 시 ALT-001로 후보를 조회할 수 있다(quickstart BE 1·2·3·5·8). `docs/planning/mvp-features.md` F009 `IN_PROGRESS`는 이 story의 첫 PR에서 반영.

---

## Phase 4: User Story 2 - 후보를 한눈에 비교하고 하나를 고른다 (Priority: P2)

**Goal**: 진행 화면 배너 → 대체 장소 화면에서 후보를 비교·선택(F010 전달)하거나 감지를 거절한다.

**Independent Test**: quickstart BE 6, AND 1·2·3. fake repository로 4상태와 선택 전달값, 거절 멱등을 확인한다.

### Tests for User Story 2

- [x] T018 [P] [US2] DETECT-004 계약 test in api/tests/contract/test_detections_contract.py
  - 영역: BE
  - 담당: ts
  - 선행: T009
  - 검증: `ACTIVE` → 200 `DISMISSED`·`decidedAt`·DB `resolved_at`; 재요청 → 동일 응답·불변; `INVALIDATED` → 200 현재 상태·`decidedAt=null`; 타인 → 403/404; 거절 후 `status=ACTIVE` 목록에서 제외(quickstart BE 6). 구현 전 실패 확인
- [x] T019 [P] [US2] AlternativeViewModel unit test in android/app/src/test/java/com/gilpick/alternative/AlternativeViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T011
  - 검증: 상세+후보 병렬 조회 → `Content`, 후보 실패 → `Error(retryable)`, 409 → `Closed`, 재조회 시 기존 `Content` 유지+`refreshing`, `dismiss()` 성공 → `onDismissed` 1회·중복 호출 무시, 실패 → `dismissError`·화면 유지, `select(candidate)`/`select(searchItem)` → `SelectedAlternative` 값(candidateId 유무). 구현 전 실패 확인
- [x] T020 [P] [US2] ProgressViewModel 배너 unit test in android/app/src/test/java/com/gilpick/progress/ProgressViewModelTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T011
  - 검증: `ACTIVE` 2건 → `bannerDetection`은 ETA 이른 1건, 당일 완료·다른 날짜 보기 → null, DETECT-001 실패 → `activeDetections=[]`이고 나머지 `Content` 정상, `AlternativeRepository=null`이면 조회 생략. 구현 전 실패 확인

### Implementation for User Story 2

- [x] T021 [US2] DETECT-004 감지 거절 endpoint in api/app/api/v1/detections.py, api/app/services/alternatives/__init__.py
  - 영역: BE
  - 담당: ts
  - 교차 확인: jh
  - 선행: T018
  - 검증: T018 통과. `UPDATE detections SET status='DISMISSED', resolved_at=now() WHERE detection_id=? AND status='ACTIVE'`로 1회만 반영, 비-`ACTIVE`는 현재 상태 반환, 일정·경로 무변경
- [x] T022 [P] [US2] Android 상태 모델·ViewModel·라벨 in android/app/src/main/java/com/gilpick/alternative/AlternativeUiState.kt, android/app/src/main/java/com/gilpick/alternative/AlternativeViewModel.kt, android/app/src/main/java/com/gilpick/alternative/AlternativeLabels.kt
  - 영역: FE
  - 담당: jy
  - 선행: T019
  - 검증: T019 통과. data-model.md §3.1·§3.2 그대로. `AlternativeLabels`: 거리(`320m`/`1.2km`), 운영 상태(`18:00 마감`·`곧 마감 18:00`·`운영시간 확인 불가`), `reasons` 코드 → 문구, 시각 KST(`ProgressLabels.timeLabel` 재사용)
- [x] T023 [P] [US2] RouteMap helper 공개와 AlternativeMap in android/app/src/main/java/com/gilpick/route/RouteMap.kt, android/app/src/main/java/com/gilpick/alternative/AlternativeMap.kt
  - 영역: FE
  - 담당: jy
  - 교차 확인: hs
  - 선행: T010
  - 검증: `circleMarker`·`pillMarker`·NaverMap 초기화/인증 실패 처리를 `internal`로 열고(기존 route 테스트 변경 없이 통과) `AlternativeMap(origin, candidates, modifier)`이 기존 장소를 `warning` pill(느낌표), 후보를 순위 원형 마커로 그리고 전체가 보이도록 카메라를 맞춘다. 실제 지도 확인은 T037
- [x] T024 [US2] 대체 장소 화면 in android/app/src/main/java/com/gilpick/alternative/AlternativePlacesScreen.kt
  - 영역: FE
  - 담당: jy
  - 선행: T022, T023
  - 검증: Figma `AlternativePlacesScreen`/`alternativesEmpty` 기준. `map` slot 파라미터(테스트 교체용). 상태: `loading` 1초 지연 표시, `empty`(2km 안내 + `기존 일정 그대로 진행` gradient + `직접 검색해서 고르기`), `error`(ui-guidelines 9절 형식, `다시 시도하기`+돌아가기), `closed`(처리된 감지 안내+진행 화면으로), `content`(감지 요약·변수 칩·`추천 후보 N곳`·`직접 검색`·후보 행·TOP·`기존 일정 그대로 진행`). 평점 없으면 `★` 생략, `UNKNOWN`은 `운영시간 확인 불가`, `CLOSING_SOON` 경고색+문구, 이동 시간 문구 없음. 거절 요청 중 버튼 비활성. 접근성: 터치 48dp·간격 8dp·아이콘 버튼 `contentDescription`·후보 행 `contentDescription`·색 단독 금지·360dp/2.0 잘림 없음. 색·간격은 theme 토큰만
- [x] T025 [US2] 진행 화면 배너 in android/app/src/main/java/com/gilpick/progress/ProgressUiState.kt, android/app/src/main/java/com/gilpick/progress/ProgressViewModel.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt
  - 영역: FE
  - 담당: jy
  - 교차 확인: hs
  - 선행: T020
  - 검증: T020 통과. `ProgressViewModel(alternativeRepository: AlternativeRepository? = null)` 주입, `load()`에서 DETECT-001(`status=ACTIVE`, limit 50) 병렬 조회·실패 격리, `Content.activeDetections`·`bannerDetection`. `VariableWarningBanner`(Figma ActiveTravelScreen 배너: `warningContainer` gradient·Figma 경계색에 해당하는 theme 토큰·36dp 경고 박스·`{장소명} {이유}` / `{오후 4:00} 도착 예정 · {N분 전} 감지` · `›`)를 오늘·당일 미완료일 때만 표시, 탭 → `onOpenAlternatives(detectionId)`. 기존 progress 테스트 변경 없이 통과
- [x] T026 [US2] 대체 장소 navigation과 MainActivity 배선 in android/app/src/main/java/com/gilpick/alternative/AlternativeNavigation.kt, android/app/src/main/java/com/gilpick/MainActivity.kt, android/app/src/androidTest/java/com/gilpick/alternative/AlternativeNavigationTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T024, T025
  - 검증: `AlternativePlacesRoute(detectionId, tripId)`, `alternativeGraph(repository, map, onSelectPlace, onDismissed, onSessionExpired)` 테스트 seam, `LifecycleResumeEffect` 재조회. MainActivity: 배너 → `AlternativePlacesRoute`, `onDismissed` → popBackStack, `onSelectPlace`는 no-op 자리(F010 교체, KDoc에 F010 연결 지점 명시, `TODO` 주석 없음). `AlternativeNavigationTest`: 배너 탭 → 화면 진입, 후보 선택 → `onSelectPlace` 값 검증, 거절 → 진행 화면 복귀
- [x] T027 [US2] 대체 장소 화면 UI test in android/app/src/androidTest/java/com/gilpick/alternative/AlternativePlacesScreenTest.kt, android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T024, T025
  - 검증: quickstart AND 1·3 항목 전부(4상태, TOP, 평점 생략, 운영 상태 문구, 재조회 중 목록 유지, 거절 버튼 비활성, 배너 표시/숨김 조건, 배너 탭 콜백). 터치 대상 48dp `assertTouchHeightIsEqualTo`/`sizeIn` 확인

**Checkpoint**: US2 완료 시 배너 → 후보 비교 → 선택 전달/거절이 앱에서 동작한다(quickstart AND 1·2(거절)·3).

---

## Phase 5: User Story 3 - 추천이 마음에 안 들면 직접 검색한다 (Priority: P3)

**Goal**: ALT-002 직접 검색과 Android 직접 검색 화면으로 후보 밖 장소를 골라 같은 콜백으로 전달한다.

**Independent Test**: quickstart BE 7, AND 2(직접 검색).

### Tests for User Story 3

- [x] T028 [P] [US3] ALT-002 계약 test in api/tests/contract/test_alternatives_contract.py
  - 영역: BE
  - 담당: ts
  - 선행: T014
  - 검증: `place` DTO+`distanceMeters`·`operatingStatus`·`visitable`·`inSchedule`, `meta.pagination`, 1글자 → 400, 기존 장소 `inSchedule=true`·`visitable=false`, `CLOSED_TEMPORARILY` → `CLOSED`·`visitable=false`(결과 유지), 409/403, PLACE-001과 같은 provider 오류 형식. 구현 전 실패 확인
- [x] T029 [P] [US3] 직접 검색 화면 UI test in android/app/src/androidTest/java/com/gilpick/alternative/AlternativeSearchScreenTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T011
  - 검증: 2글자 미만 안내, 결과 행에 `기존 장소에서 {거리}`·`방문 불가`/`이미 일정에 있음` 문구+비활성, 방문 가능 행 선택 → `SelectedAlternative(candidateId=null)`, F003 `EmptyState` 재사용, 페이지 이어 불러오기. 구현 전 실패 확인

### Implementation for User Story 3

- [x] T030 [US3] AlternativeService.search와 ALT-002 endpoint in api/app/services/alternatives/__init__.py, api/app/api/v1/alternatives.py
  - 영역: BE
  - 담당: ts
  - 선행: T028, T017
  - 검증: T028 통과. `PlaceService.search_places(query, category=None, area_code=None, cursor, limit)` 재사용, 거리 haversine, `inSchedule` 집합, `operatingStatus`는 `business_status`만으로(research R8), 추가 Google 호출 없음
- [x] T031 [P] [US3] PlaceSearchScreen 재사용 slot in android/app/src/main/java/com/gilpick/place/PlaceSearchScreen.kt
  - 영역: FE
  - 담당: jy
  - 교차 확인: hs
  - 선행: 없음
  - 검증: `PlaceRow`·`EmptyState`를 `internal`로 열고 `PlaceRow(trailing: @Composable () -> Unit = {}, enabled: Boolean = true)` slot 추가. F003 `PlaceSearchScreenTest`·screenshot 변경 없이 통과
- [x] T032 [US3] 직접 검색 화면과 route in android/app/src/main/java/com/gilpick/alternative/AlternativeSearchScreen.kt, android/app/src/main/java/com/gilpick/alternative/AlternativeNavigation.kt, android/app/src/main/java/com/gilpick/alternative/AlternativeViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T029, T031, T026
  - 검증: T029 통과. `AlternativeSearchRoute(detectionId, tripId)`; 검색어 입력(공백 제거 2글자 이상)·cursor 페이지·`PlaceRow` 재사용+거리·방문 가능 slot·비활성, 선택 → `onSelectPlace`. 대체 장소 화면 `직접 검색`·`직접 검색해서 고르기` → 이 route. 접근성 기준은 T024와 동일

**Checkpoint**: US3 완료 시 직접 검색으로 고른 장소도 같은 콜백으로 전달된다(quickstart BE 7, AND 2).

---

## Phase 6: User Story 4 - 외부 데이터가 빠져도 추천은 계속된다 (Priority: P4)

**Goal**: 평점·혼잡·날씨·운영시간 결손은 변수 단위로 격리하고 TourAPI 실패만 추천 실패로 드러낸다.

**Independent Test**: quickstart BE 4·5.

### Tests for User Story 4

- [x] T033 [P] [US4] 결손·실패 격리 test in api/tests/unit/test_alternative_candidates.py, api/tests/contract/test_alternatives_contract.py
  - 영역: BE
  - 담당: ts
  - 선행: T016, T017
  - 검증: Google 전부 timeout → 200·`rating` 키 없음·`UNKNOWN`; 지원지역 밖 → `congestion` 없음; 실내 → `weather` 없음; 기상청 실패 → 전 후보 `weather` 없음·200; 재분배 수치; TourAPI timeout/5xx → 504/502이며 빈 목록 아님; 한 후보의 Google 예외가 다른 후보에 영향 없음. 구현 전 실패 확인

### Implementation for User Story 4

- [x] T034 [US4] 격리 보장과 추적 log in api/app/services/alternatives/candidates.py, api/app/services/alternatives/__init__.py
  - 영역: BE
  - 담당: ts
  - 선행: T033
  - 검증: T033 통과. 후보별 Google 호출을 `try/except`+timeout으로 감싸 변수 제외, 기상청·서울시 실패는 해당 변수 제외, TourAPI 오류만 `AppError`로 전파. log에 `detection_id`·`request_id`·반경·분류 단계·후보 수·`providers={tour, google, kma, seoul}` 가용성만 기록

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T035 [P] api-spec·er-schema 동기화 in docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: BE
  - 담당: ts
  - 교차 확인: jy
  - 선행: T017, T021, T030
  - 검증: data-model.md §4 목록대로 §2 구현 현황(ALT-001·ALT-002 `[x]`, DETECT-004 행), §7 DETECT-001 확장·DETECT-004 신설·ALT-001/002 예시 갱신, er-schema §8.1 `resolved_at` 설명·§13 매핑. `contracts/alternatives.openapi.yaml`과 필드 대조 결과를 PR에 기록
- [x] T036 [P] 요구사항·기능 명세 5절 동기화 in docs/planning/requirements.md, docs/planning/functional-spec.md
  - 영역: 통합
  - 담당: jy
  - 선행: T001
  - 검증: ALT-01~05에 반경·분류 결합 순서(Clarifications), 운영 상태 확인 순서(점수 상위부터 10개, 상한 20), 감지 거절(`기존 일정 그대로 진행` = DISMISSED)을 반영. 값은 `policy.py`가 정본임을 명시
- [ ] T037 [US2] 대체 장소·배너 screenshot과 실제 지도 확인 in android/app/src/androidTest/java/com/gilpick/alternative/AlternativeScreenshotTest.kt, android/app/src/androidTest/java/com/gilpick/progress/ActiveTravelScreenshotTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T024, T025, T032
  - 검증: 후보 있음·후보 없음·추천 실패·처리된 감지 × (360dp 기본, 360dp fontScale 2.0) 8장 + 배너 2장을 ATD `captureToImage`로 저장, 360dp·2.0 잘림·가로 스크롤 없음, 터치 48dp 확인. `AlternativeMap` 실제 마커는 `gilpick_api36_play`+Naver 키로 확인해 screenshot을 PR에 첨부
- [x] T038 quickstart Backend 검증 실행 in specs/009-alternative-places/quickstart.md
  - 영역: BE
  - 담당: ts
  - 선행: T017, T021, T030, T034, T035
  - 검증: quickstart BE 1~9 전부 실행하고 결과·미실행 항목(실제 TourAPI·Google 연동은 local `.env` 자격으로 가능한 범위)을 quickstart "검증 기록" 절과 PR에 기록. `pytest tests/unit tests/contract` 전체 통과(기존 auth 9건 제외)
- [ ] T039 quickstart Android·실서버 검증과 Feature 상태 갱신 in specs/009-alternative-places/quickstart.md, docs/planning/mvp-features.md
  - 영역: 통합
  - 담당: jy
  - 교차 확인: ts
  - 선행: T026, T027, T032, T037, T038
  - 검증: quickstart AND 1~5 실행(`gilpick_api36_play`, local API + 실제 TourAPI·Google 키, `ACTIVE` 감지 시드), 배너→후보→선택 콜백 도달·거절→배너 소멸·직접 검색 확인, screenshot·requestId 기록. `mvp-features.md` F009 `VERIFY` 반영(모든 PR 병합 후 `DONE`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음. T001 먼저, T002·T003 병렬
- **Foundational (Phase 2)**: T001 완료 후. BE T004·T005·T006·T007 병렬 → T008(T002 후) → T009. FE T010(T001 후) → T011
- **User Stories (Phase 3~6)**: Foundational 완료 후. US1(BE)과 US2(FE 대부분)는 **다른 담당·다른 파일이라 병렬 진행** 가능. US3는 US1 endpoint(T017)·US2 navigation(T026) 후. US4는 US1 파이프라인(T016·T017) 후
- **Polish (Phase 7)**: 관련 story 완료 후. T035·T036 병렬, T037 → T038 → T039 순

### User Story Dependencies

- **US1 (P1)**: Foundational 후, BE 단독 → MVP(ALT-001)
- **US2 (P2)**: BE는 T021(DETECT-004)만, FE는 T019~T027. FE 화면은 US1 완료 전에도 fake repository로 진행 가능하며 실서버 확인만 US1을 기다린다
- **US3 (P3)**: BE T030은 T017 후, FE T032는 T026 후
- **US4 (P4)**: T016·T017 후, `candidates.py` 단일 소유(`ts`)로 순차

### Within Each User Story

- 계약·unit·UI test를 먼저 작성해 실패를 확인한 뒤 구현
- BE: policy → scoring → candidates → service/endpoint. FE: state/ViewModel → 화면 → navigation → UI test
- story 완료(테스트·검증 포함) 후 다음 우선순위로 이동

### Parallel Opportunities

- Phase 1: T002·T003 병렬
- Phase 2: T004·T005·T006·T007·T010 병렬
- Phase 3: T012·T013·T014 병렬
- Phase 4: T018·T019·T020 병렬 / T022·T023 병렬 / BE T021과 FE 전체 병렬
- Phase 5: T028·T029·T031 병렬
- Phase 7: T035·T036 병렬

---

## Parallel Example: Foundational

```bash
# BE(ts): 서로 다른 파일이라 병렬
Task: "TourAPI 위치 기반 목록 client 메서드 in api/app/clients/tour_api.py"
Task: "F003 PlaceService helper 공개 승격 in api/app/services/place.py"
Task: "F008 OperatingHoursSource parse 분리 in api/app/services/detection/operating_hours_source.py"
Task: "ALT 공개 스키마 in api/app/schemas/alternatives.py"

# FE(jy): 같은 시기에 계약 기반으로 진행
Task: "Android ALT·DETECT DTO와 Retrofit service in android/app/src/main/java/com/gilpick/alternative/AlternativeApi.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup, Phase 2 Foundational 완료
2. Phase 3 US1 완료 → ALT-001 후보 조회
3. **중단하고 검증**: quickstart BE 1·2·3·5·8
4. `mvp-features.md` F009 `IN_PROGRESS`(첫 구현 PR)

### Incremental Delivery

1. Setup + Foundational → 기반 완료
2. US1(BE) ∥ US2 FE 화면(fake) → US2 BE 거절 → 실서버로 US1+US2 확인
3. US3(직접 검색) → 독립 검증
4. US4(결손 격리) → 독립 검증
5. Phase 7 문서 동기화·screenshot·quickstart 실행 → `VERIFY` → 병합 후 `DONE`

### 담당 전략

- BE 전부 `ts`: Foundational 4건 병렬 후 US1 → US2(T021) → US3(T030) → US4(T034) 순차(`candidates.py`·`alternatives.py` 단일 소유). F008 파일(T006·T009·T021)은 `jh` review 필수
- FE 전부 `jy`: T010·T011 → US2 화면(T019~T027) → US3(T029·T031·T032) → T037·T039. `progress/*`·`RouteMap.kt`·`PlaceSearchScreen.kt` 수정(T023·T025·T031)은 `hs` review 필수
- 계약(T001)·문서 동기화(T035)는 BE·FE 담당이 교차 확인

---

## Notes

- `[P]`는 다른 파일·미완료 선행 없음을 뜻한다. `candidates.py`·`alternatives/__init__.py`·`AlternativeNavigation.kt`·`AlternativeViewModel.kt`를 수정하는 task는 병렬 금지
- `[Story]` 표기로 task–User Story 추적
- 구현 전 관련 테스트가 실패하는지 확인
- 각 task 완료는 코드 + 명시된 테스트·검증 + 필요한 문서 동기화까지 포함
- 실제 TourAPI·Google·기상청·서울시 연동은 G001 Gate 또는 local `.env` 자격에 의존하며, 그 전에는 mock으로 대체하고 미검증 항목을 PR에 남긴다
- 후보 조회·검색·선택은 일정·경로·감지 상태를 바꾸지 않는다. 상태를 바꾸는 유일한 동작은 DETECT-004 거절이다
- 사용자 위치·`candidateId` 원문을 log에 남기지 않는다(constitution V)
- `onSelectPlace`는 F010이 미리보기 화면으로 연결한다. F009 PR은 콜백 도달까지만 검증한다
