# Research: 대체 장소 추천

**Date**: 2026-09-09 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

spec의 Assumptions에서 plan으로 미룬 항목과 Technical Context의 미확정 사항을 결정한다. 각 항목은 결정·근거·검토한 대안을 적는다.

## R1. 후보 장소 데이터 조회 방식

- **Decision**: TourAPI `locationBasedList2`(위치 기반 목록)를 `TourApiClient.search_by_location`으로 새로 감싸고, 기존 장소 좌표 기준 **반경 2000m 한 번**만 호출한다(`numOfRows=100`, `lclsSystm1`=기존 장소 내부 카테고리의 TourAPI 대분류, 카페면 `lclsSystm2=FD05`). 응답의 `dist`(m)로 0.5km→1km→2km 사다리와 소→중→대분류 비교를 **메모리에서** 적용한다.
- **Rationale**: 반경·분류 조합마다 호출하면 최대 9회지만 2km 한 번이면 1회로 같은 결과를 얻는다(spec FR-002·FR-003, Clarifications 2026-09-09). `PLACE-001`(areaBasedList2/searchKeyword2)은 반경 검색을 지원하지 않아 재사용할 수 없다.
- **Alternatives**: 반경별 3회 호출(호출 수만 늘고 이점 없음), PLACE-001에 반경 파라미터 추가(F003 계약 변경, 화면 검색과 무관한 필드가 섞임).
- 기존 장소가 Google 전용(`tour_content_id` 없음)이면 `places.category`로 대분류를 만들고 소·중분류 비교는 건너뛴다. 내부 카테고리 `OTHER`는 TourAPI 대분류 매핑이 없으므로 카테고리 필터 없이 조회한 뒤 `PlaceService._category`로 변환한 값이 `OTHER`인 것만 남긴다.

## R2. Google 매칭·평점·운영시간을 한 번에 얻는 방법

- **Decision**: 후보마다 Google Places Text Search 1회(`search_text(name, locationBias=circle 50m@후보 좌표, maxResultCount=3)`)를 호출한다. 응답에 이미 `rating`·`userRatingCount`·`businessStatus`·`regularOpeningHours`가 포함되므로(`SEARCH_FIELD_MASK`) 같은 응답으로 **평점 병합과 운영시간 판정**을 모두 처리한다. 매칭은 F003 `PlaceService._find_match`(이름 정규화 일치 + 주소 일치 + 50m)를 그대로 쓰고, 모호하면 Google 데이터를 쓰지 않는다(spec FR-007).
- **Rationale**: F008 `OperatingHoursSource.get`은 Google `place_id`가 있어야 상세를 조회하는데 TourAPI 후보에는 `place_id`가 없다. Text Search 한 번이면 식별·평점·운영시간을 같이 얻어 후보당 Google 호출을 1회로 묶는다.
- **Refactor**: `OperatingHoursSource`에 응답 payload를 받아 `OperatingHours`로 바꾸는 `parse(payload, eta)`를 분리하고 `get`은 `parse(await client.get_place(id), eta)`로 유지한다(F008 파일 수정, 동작 불변, 원 담당자 review). `PlaceService`의 `_tour_place`·`_google_place`·`_find_match`·`_merge_google`·`_distance`·`_category`는 모듈 수준 공개 함수로 승격해(`app/services/place.py` 안에서 이름만 바꿈) F009가 호출한다(F003 파일 수정, 동작 불변).
- **Alternatives**: `places` 테이블에 캐시(MVP 제외), Nearby Search(이름 매칭이 어려워 모호 판정이 늘어남).

## R3. 후보 파이프라인과 호출 상한

- **Decision**: (1) TourAPI 1회 → 사다리·분류·제외(FR-005) 적용 → (2) 거리·혼잡·날씨만으로 **예비 점수**(평점 제외 재분배) → (3) 예비 점수 상위부터 Google Text Search로 평점·운영 상태 확인, 운영 종료(폐점 이후·임시휴업·영업 종료) 제외, **운영 중 후보 10개가 채워지거나 확인 횟수가 `OPERATING_CHECK_LIMIT=20`에 이르면 중단** → (4) 확인된 후보만으로 베이지안 보정(`C`·`m`은 이 집합 기준)과 최종 점수 → 정렬 → 상위 10개.
- **Rationale**: Clarifications 2026-09-09(점수 상위부터 10개 채울 때까지). 후보당 Google 1회이므로 요청당 Google 최대 20회, TourAPI 1회, 기상청 1회, 서울시 ≤(구역 수)회로 상한이 고정된다.
- **Ceiling(ponytail)**: 예비 점수에 평점이 없어 최종 순위가 예비 순위와 다를 수 있고, 확인 상한 밖의 후보는 반환되지 않는다. 상한값은 `policy.py`에서 조정한다.
- **혼잡·날씨 재사용**: `evaluate_weather`는 기존 장소 좌표로 **1회**만 호출하고 예보 슬롯을 모든 후보에 공유한다(기상청 격자 5km, 후보는 2km 안). 실내 카테고리 후보는 F008 규칙대로 날씨 변수를 제외한다. `evaluate_congestion`은 후보마다 `find_nearest_congestion_area`(DB)로 구역을 찾고 같은 `area_code`의 `get_population`은 요청 안에서 메모이즈한다.

## R4. 점수 정규화

- **Decision** (`app/services/alternatives/policy.py`·`scoring.py`):
  - 거리: `1 - min(d, R) / R`, `R` = 최종 탐색 반경(m).
  - 평점: `adjusted / 5`. 베이지안 `m`은 확인된 후보 리뷰 수 중앙값, 후보 5개 미만이면 20. 평점 또는 리뷰 수가 없는 후보는 변수 제외.
  - 혼잡: F008 `CongestionVerdict` 재사용. `crowded=True → 0.0`, 아니면 level별 `RELAXED 1.0 / NORMAL 0.75 / SLIGHTLY_CROWDED 0.5`; `available=False` → 제외.
  - 날씨: `at_risk → 0.0`, 아니면 `1.0`; `available=False`(실내·예보 없음) → 제외.
  - 가중치 35/30/20/15, 제외 변수 가중치는 사용 가능한 변수에 비례 재분배. `score` = 0~100 실수, `displayScore` = 반올림 정수. 동점: 거리↑ → 보정 평점↓ → 리뷰 수↓.
- **Rationale**: spec FR-008~FR-012, `api-spec.md` 7.2. 값은 전부 `policy.py` 한곳.
- **Alternatives**: 혼잡 민감도별 다른 점수표(F008 `crowded` 판정이 이미 민감도를 반영하므로 중복).

## R5. 후보 식별자(candidateId)

- **Decision**: `base64url(json{d: detectionId, p: placeId, t: evaluatedAt epoch}) + "." + base64url(HMAC-SHA256(payload, jwt_signing_secret))`. 유효 시간 `CANDIDATE_TTL_MINUTES=15`. 검증 함수 `verify_candidate_token(token) -> CandidateClaims | None`을 F010이 미리보기 생성에서 호출한다.
- **Rationale**: `er-schema.md` 8.1(짧은 수명의 서명 토큰, 테이블 없음). F003 cursor가 이미 `jwt_signing_secret`을 HMAC 키로 쓰므로 새 secret이 없다.
- **Alternatives**: DB 저장(MVP 제외), JWT 라이브러리(같은 결과에 의존성만 늘어남).

## R6. 감지 거절 계약

- **Decision**: `POST /api/v1/detections/{detectionId}/dismiss` → **DETECT-004 감지 거절**. `ACTIVE`면 `status=DISMISSED`, `resolved_at=now`로 갱신하고 `200 {detectionId, status: "DISMISSED", decidedAt}`. 이미 `DISMISSED`·`RESOLVED`·`INVALIDATED`면 상태를 바꾸지 않고 현재 값을 `200`으로 반환한다(상태 기반 멱등, `Idempotency-Key` 불필요). 소유권은 DETECT-002와 같은 `_owned_detection`을 공용 helper로 승격해 재사용한다.
- **Rationale**: spec FR-016·FR-017, Clarifications 2026-09-09. `er-schema.md` 8.1 `resolved_at`("처리 완료 시각")을 거절 시각으로도 쓰고 컬럼을 추가하지 않는다.
- **Alternatives**: F010 REPL-003(미리보기 단위라 후보 없이 거절할 수 없음), 새 컬럼 `dismissed_at`(정보 중복).

## R7. 진행 화면 배너 데이터

- **Decision**: DETECT-001 목록에 `status` query 필터(선택, 예: `status=ACTIVE`)를 추가하고 목록 항목에 `eta`·`reason`을 더한다. 앱은 `GET /trips/{tripId}/detections?status=ACTIVE&limit=50` 한 번으로 배너를 만든다.
- **Rationale**: 배너에는 장소명·이유·도착 예정 시각·감지 경과 시간이 필요한데(spec UI-001) 현재 목록 항목에는 `eta`·`reason`이 없어 감지마다 DETECT-002를 호출해야 한다. 필드 2개 추가가 가장 작은 변경이다. F008 파일(`api/app/api/v1/detections.py`, `schemas/detection.py`) 수정이므로 F008 BE 담당 review.
- **Alternatives**: 클라이언트에서 감지마다 상세 조회(N+1), 목록 전체를 받아 클라이언트 필터(거절 누적 시 낭비).

## R8. 직접 검색(ALT-002)의 방문 가능 여부

- **Decision**: `PlaceService.search_places(query, category=None, area_code=None, cursor, limit)`를 그대로 호출하고, 각 결과에 기존 장소로부터의 거리(haversine), `inSchedule`(같은 날짜 일정의 `places.place_id` 집합), `operatingStatus`·`visitable`을 붙인다. 운영 상태는 **F003이 이미 병합한 Google 필드**(`business_status`·`regular_opening_hours`)만으로 판정하고 추가 Google 호출을 하지 않는다. 판정 불가면 `operatingStatus=UNKNOWN, visitable=true`.
- **Rationale**: spec FR-004의 "확인 불가는 운영 중으로 본다"와 일치하고 페이지당 외부 호출을 F003 검색과 동일하게 유지한다. F003 `PlaceSummary`의 `regular_opening_hours`는 요일별 설명 문자열이라 폐점 시각을 계산할 수 없으므로 ALT-002는 `business_status`가 `CLOSED_TEMPORARILY`·`CLOSED_PERMANENTLY`면 `CLOSED`·`visitable=false`, `OPERATIONAL`이면 `OPEN`, 없으면 `UNKNOWN`으로만 판정한다. 폐점 시각 기반 판정(`closesAt`·`CLOSING_SOON`)은 R2의 `parse`를 쓰는 ALT-001에만 적용한다.
- **Alternatives**: 결과마다 Google 조회(페이지당 20회 추가), 검색 결과 자체 제외(spec FR-019 위반).

## R9. Android 구조와 재사용

- **Decision**: 새 패키지 `com.gilpick.alternative`에 `AlternativeApi.kt`(DTO + `AlternativeService`: DETECT-001·DETECT-002·DETECT-004·ALT-001·ALT-002 + `createAlternativeRetrofit`, `ProgressApi` 패턴), `AlternativeRepository.kt`(`AlternativeError`), `AlternativeUiState.kt`, `AlternativeViewModel.kt`, `AlternativePlacesScreen.kt`, `AlternativeMap.kt`, `AlternativeSearchScreen.kt`, `AlternativeLabels.kt`, `AlternativeNavigation.kt`를 둔다. F007의 `progress/DetectionApi.kt`는 위치 감지(PROG-003~005)이므로 이름 충돌을 피해 F008 변수 감지 호출은 `AlternativeService` 안에 둔다.
- **재사용**: 지도는 `RouteMap`의 `circleMarker`·`pillMarker`·NaverMap 초기화 코드를 `internal`로 열어 `AlternativeMap`이 쓴다(경로선 없음, 기존 장소 = 경고 pill, 후보 = 순위 원형). 직접 검색 화면은 `PlaceSearchScreen`의 `PlaceRow`·`EmptyState`를 `internal`로 열고 행 아래 거리·방문 가능 표시 slot을 추가해 `AlternativeSearchScreen`이 재사용한다(카테고리 칩·지역 필터 없음, 검색어만).
- **선택 전달**: `alternativeGraph(onSelectPlace: (SelectedAlternative) -> Unit)` 콜백. `SelectedAlternative(detectionId, placeId, candidateId?, name, distanceMeters, displayScore?)`. F010이 미리보기 화면을 붙이기 전까지 `MainActivity`는 콜백을 no-op으로 두고 spec FR-015의 값이 콜백까지 도달하는 것을 androidTest로 검증한다.
- **배너**: `ProgressViewModel`에 `AlternativeRepository`(nullable, 기본 null) 주입 → `load()`에서 DETECT-001(`status=ACTIVE`)을 병렬 조회, 실패는 배너 숨김으로 격리. `Content.activeDetections`와 `bannerDetection`(오늘 ETA 가장 이른 것) + `extraDetectionCount`. `ActiveTravelScreen(onOpenAlternatives: (detectionId) -> Unit)`.
- **Alternatives**: 기존 `progress` 패키지에 화면 추가(패키지가 이미 15개 파일이라 분리), 감지 목록 화면 신설(F011 범위).

## R10. 테스트 전략

- BE: `tests/unit/test_alternative_scoring.py`(정규화·재분배·베이지안·동점·표시 반올림), `test_alternative_candidates.py`(사다리·분류 단계·제외·확인 상한·운영 종료 제외, TourAPI·Google mock transport), `test_candidate_token.py`(서명·만료·위조), `tests/contract/test_alternatives_contract.py`(ALT-001·ALT-002·DETECT-004·DETECT-001 확장, 403/404/409/502/504). 외부 연동은 `httpx` mock transport, 시각은 고정.
- Android: unit(`AlternativeViewModelTest`: 병렬 조회·거절 멱등·오류 격리·선택 전달값), androidTest(`AlternativePlacesScreenTest`·`AlternativeSearchScreenTest`·`AlternativeNavigationTest`·`AlternativeScreenshotTest` 4상태×2배율·`ActiveTravelScreen` 배너 screenshot 추가). 실서버는 quickstart.md 절차.
