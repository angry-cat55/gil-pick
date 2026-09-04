# Implementation Plan: 장소 검색

**Branch**: `003-place-search` (Spec Kit 논리 식별자; 현재 Git branch `docs/ts-f003-place-search-spec`) | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-place-search/spec.md`

## Summary

인증된 사용자가 장소를 검색하고 상세 정보를 조회한다. Backend는 TourAPI `KorService2`를 기준 원천으로 사용하고, 음식·카페·쇼핑의 각 페이지 결과가 `limit`보다 적을 때만 Google Places로 부족분을 채운다. 확정 매칭된 장소는 TourAPI 정보에 Google 평점·평점 수·영업정보만 병합하며 Google 사진·리뷰는 제외한다. 각 외부 호출에는 5초 timeout과 일시적 실패 1회 재시도를 적용하고 Google 실패는 TourAPI 결과에 격리한다. 검색·상세 결과는 저장하거나 cache하지 않는다.

## Technical Context

**Language/Version**: Backend Python 3.13; Android Kotlin 2.4.10

**Primary Dependencies**: Backend는 기존 FastAPI 0.141.1, Pydantic Settings 2.10.1, httpx2 2.10.0, PyJWT 2.10.1을 재사용한다. Android는 기존 Compose BOM 2026.08.00, Lifecycle 2.11.0, Retrofit 3.0.0, OkHttp 5.5.0, kotlinx.serialization 1.11.0에 Navigation Compose 2.10.0, Coil Compose·OkHttp 3.6.0을 추가한다.

**Storage**: N/A. F003 검색·상세 결과는 영구 저장하거나 server cache하지 않는다. F004가 일정 추가 시 `places` 최소 참조정보를 저장한다.

**Testing**: Backend pytest, pytest-asyncio, FastAPI TestClient, httpx2 MockTransport를 사용한 contract·client·service test와 credential이 있을 때의 수동 실제 연동 검증; Android JUnit, kotlinx-coroutines-test, MockWebServer, Compose UI test, Navigation test, 실제 기기 또는 AVD screenshot 검증

**Target Platform**: Docker 기반 AWS Linux Backend; Android 8.0 이상(`minSdk 26`, `targetSdk 36`, `compileSdk 37`)

**Project Type**: Android mobile app + REST API web service

**Performance Goals**: Google 보완 없는 검색과 상세는 각각 5초 이내(SC-001~002), 순차 Google 보완 검색은 10초 이내(SC-013), 단일 제공자 자동 재시도는 해당 호출 시작 후 11초 이내(SC-008); 확정 매칭 fixture의 중복 노출 0건(SC-003)

**Constraints**: 인증 필수; keyword는 trim 후 2글자 이상이고 category와 단독·조합 가능; 둘 다 없으면 `400`; 명시적 검색만 허용; 각 provider 호출당 5초 timeout; timeout·일시적 5xx만 최대 1회 재시도; application error·4xx·인증·quota/rate limit은 재시도 금지; 음식·카페·쇼핑에서 TourAPI 정상 결과가 `limit` 미만일 때만 Google 보완; 확정 매칭만 병합하고 모호한 Google 후보는 제외; Google 사진·리뷰·장소별 provider 배지·일정 추가·지도·DB·Redis·Paging 3는 범위 밖; Google 필수 attribution은 준수

**Scale/Scope**: F003 endpoint 2개, 외부 client 2개, Android 검색·상세 화면 2개; TourAPI quota와 Google field-mask별 과금을 고려해 명시적 검색·최대 20건·조건부 Google 호출을 사용하고 두 provider의 quota·billing을 환경 Issue에서 확인

## UI Implementation & Validation

**Design Sources**: Figma Make `Design UI from Reference`(https://www.figma.com/make/H7SpIPF8iNYyxb5jPlo7xM)의 `AddPlaceScreen`·`PlaceDetailScreen`이 정본이다(2026-09-04 팀 결정, [spec.md](spec.md) UI-010). 색·글자·간격은 Figma 값을 그대로 쓰며 `.pen`과 테마 토큰보다 우선한다. [ui-guidelines.md](../../docs/design/ui-guidelines.md)는 48dp 터치 영역·화면 상태·접근성 최저선에만 적용한다.

**Tokens & Components**: `GilpickTheme`, `MaterialTheme.colorScheme`, `LocalGilpickColors`, `LocalGilpickSpacing`, `LocalGilpickRadius`를 그대로 사용하며 새 색상·타이포·간격 토큰은 추가하지 않는다. 검색 입력과 버튼은 기존 입력·버튼 규칙을 따르고, 장소 결과 행과 이미지 fallback이 검색·상세에서 실제 재사용될 때만 `com.gilpick.ui.component`로 추출한다. 네트워크 이미지는 Coil `AsyncImage`를 사용하고 고정 썸네일 영역으로 layout shift를 방지한다.

**State & Interaction**: `PlaceSearchUiState`는 편집 중인 `draftCriteria`, 마지막으로 실행한 `committedCriteria`, `items`, `initialLoad`, `appendLoad`, `nextCursor`, `hasNext`, validation error를 분리한다. 입력·filter 변경은 호출하지 않고 검색 버튼 또는 IME Search가 같은 event를 보낸다. 새 검색은 이전 결과와 섞지 않으며 append 중에는 기존 결과를 유지한다. `placeId`를 stable key로 사용해 append 결과를 dedupe한다. `PlaceDetailUiState`는 loading/content/notFound/error를 분리하고 nullable field를 그대로 표현한다. type-safe `PlaceSearchRoute`, `PlaceDetailRoute(placeId)`와 destination-scoped ViewModel로 상세 복귀 시 검색 조건·결과를 유지하며 `LazyListState`로 scroll 위치를 복원한다.

**T035 Contract Decisions**: Navigation Compose는 F002 PR #154가 채택한 2.10.0으로 통일한다. `MainActivity.kt`는 #154 반영 후 F003 Android 브랜치를 최신 `main`에 rebase하여 수정한다. F003은 `PlaceSearchRoute`와 `PlaceDetailRoute(placeId)` 등록 및 검색→상세→뒤로가기만 구현하며, pen과 명세에 없는 임시 진입 UI는 추가하지 않는다. 실제 일정 화면의 장소 검색 진입점은 F004에서 연결한다. 검색과 `tourapi:` 상세의 Google 실패는 격리하고, `google:` 상세 실패만 provider별 429/502/504 오류로 노출한다. `tourApiCategory`, 오류 `retryable`, 검색 `meta.pagination`은 응답 필수 필드로 확정하고 nullable 여부는 OpenAPI를 따른다. `areaCode`는 TourAPI `areaCode2`의 17개 광역 code만 허용하며 `businessStatus`는 Google 원문 enum을 전달하고 Android에서 현지화한다.

**Accessibility & Adaptive Layout**: 검색 입력에는 항상 보이는 label과 IME Search action을 제공하고, 결과 요약·오류는 적절한 live region으로 알린다. 결과 행 전체는 button semantics와 최소 48×48dp hit area를 가지며 이미지가 정보성이 있으면 장소명을 설명으로 사용하고 중복이면 decorative 처리한다. 읽기 순서는 검색 조건 → 결과 요약 → 결과 목록이며 색만으로 상태를 전달하지 않는다. 360dp와 최대 font scale에서 text wrapping을 우선하고 가로 scroll·잘림을 허용하지 않는다. 태블릿은 동일 단일 열의 읽기 가능한 최대 폭을 사용하며 F003에서 별도 list-detail pane을 추가하지 않는다.

**Visual Validation**: 실제 기기 또는 AVD에서 검색·상세 각각 loading/empty/error/content를 screenshot으로 확인한다. 추가로 Google 보완·병합·부분 실패, 장소별 provider 배지 미표시, Google 데이터 영역의 필수 attribution, 긴 장소명·주소, 360dp, 최대 font scale, TalkBack 순서와 상세 복귀 상태를 검증한다. 다크 theme는 저장소에서 미확정이므로 제외한다.

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가 완료.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 외부 장애·빈 결과를 구분하고 재시도 행동을 제공한다. append 실패 시 기존 결과를 보존한다. F003은 상태를 자동 변경하지 않는다. | PASS |
| II. 계약 우선 SDD와 문서 동기화 | TourAPI 우선·조건부 Google 보완·병합·부분 실패 계약을 feature OpenAPI와 상위 기획·API·ERD 문서에 함께 반영한다. F009는 이 계약의 소비자로 재정의한다. | PASS |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | F003 endpoint는 read-only GET이며 DB 상태를 변경하지 않는다. request ID와 provider 오류 분류·latency만 추적한다. | PASS |
| IV. 외부 의존성 실패 격리 | provider별 5초 timeout·제한된 retry를 적용한다. Google 실패는 Google 필드에만 격리하고 TourAPI 실패를 Google 성공으로 숨기지 않는다. | PASS |
| V. 보안·소유권·최소 데이터 | 두 provider key를 Backend secret으로만 주입하고 최소 Google field mask만 요청한다. credential·원문 body를 노출하거나 검색 결과를 저장하지 않는다. | PASS |
| 교차 계약 review | Backend·Android가 공유하는 place DTO, cursor, 오류 code와 navigation route 등록 범위를 구현 PR 전에 양 영역 담당자가 확인해야 한다. | PASS WITH REVIEW CONDITION |

Constitution 위반과 예외는 없다. 교차 계약 review는 설계 위반이 아니라 구현 전 필수 gate다.

### Post-Design Re-check

- [data-model.md](data-model.md)는 transient 검색·상세·cursor·UI state만 정의하며 DB entity나 migration을 추가하지 않는다.
- [contracts/places.openapi.yaml](contracts/places.openapi.yaml)은 다중 provider ID, Google nullable 보강 필드, attribution과 provider별 오류 code를 명시한다.
- [quickstart.md](quickstart.md)는 TourAPI 우선·Google 부족분·확정 매칭·부분 실패와 Android attribution·네 상태·복귀 상태를 검증한다.
- TourAPI·Google Places key와 provider 원문은 어떤 산출물에도 실제 값으로 기록하지 않았다.
- Phase 1 이후에도 모든 constitution gate는 PASS이며 새로운 예외는 없다.

## Project Structure

### Documentation (this feature)

```text
specs/003-place-search/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── places.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md              # $speckit-tasks에서 생성
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── api/v1/places.py          # PLACE-001~002 endpoint와 query validation
│   ├── clients/tour_api.py       # KorService2 요청·응답 경계와 오류 분류
│   ├── clients/google_places.py  # Places API (New) 최소 field mask와 오류 분류
│   ├── schemas/place.py          # 안정적인 place DTO와 pagination envelope
│   ├── services/place.py         # TourAPI 우선 routing, Google 보완·매칭·상세 조합, cursor
│   ├── core/config.py            # TourAPI·Google Places SecretStr 설정
│   ├── core/logging.py           # provider credential redaction 보강
│   └── main.py                   # places router 등록
├── .env.example
└── tests/
    ├── contract/test_place_contract.py
    ├── integration/test_place_flow.py
    └── unit/
        ├── test_tour_api_client.py
        ├── test_google_places_client.py
        └── test_place_service.py

android/app/
├── build.gradle.kts              # Navigation Compose, Coil 의존성
├── src/main/java/com/gilpick/
│   ├── MainActivity.kt           # place route 등록; 실제 일정 진입 연결은 F004 범위
│   ├── place/
│   │   ├── PlaceApi.kt
│   │   ├── PlaceRepository.kt
│   │   ├── PlaceSearchViewModel.kt
│   │   ├── PlaceDetailViewModel.kt
│   │   ├── PlaceSearchScreen.kt
│   │   └── PlaceDetailScreen.kt
│   └── ui/component/             # 실제 재사용이 확인된 place row/image fallback만 추가
├── src/test/java/com/gilpick/place/
│   ├── PlaceRepositoryTest.kt
│   ├── PlaceSearchViewModelTest.kt
│   └── PlaceDetailViewModelTest.kt
└── src/androidTest/java/com/gilpick/place/
    ├── PlaceSearchScreenTest.kt
    └── PlaceDetailScreenTest.kt
```

**Structure Decision**: 기존 router → service → client와 Retrofit → repository → ViewModel → stateless screen 흐름을 유지한다. `place.py`가 고정된 두 client를 직접 조합하며 구현체 하나짜리 범용 provider abstraction은 만들지 않는다. F003은 read-only이므로 ORM·migration·Redis를 추가하지 않는다. F002 navigation 변경과 같은 파일은 Issue 간 소유권과 merge 순서를 먼저 조율한다.

## Phase 0 Research Decisions

결정과 근거, 확인하지 못한 외부 계약 검증 항목은 [research.md](research.md)에 기록했다. 구현을 막는 미해결 기술 항목은 없다. TourAPI `arrange` 코드, provider 최대 `numOfRows`, 콘텐츠 유형별 `detailIntro2` 필드명은 실제 credential을 사용한 contract fixture 확정 task에서 공식 매뉴얼과 함께 검증한다.

## Phase 1 Design Outputs

- transient 데이터와 UI 상태: [data-model.md](data-model.md)
- REST 계약: [contracts/places.openapi.yaml](contracts/places.openapi.yaml)
- 종단간 검증 절차: [quickstart.md](quickstart.md)

## Complexity Tracking

Constitution 위반은 없다. Navigation Compose와 Coil은 UI-009와 UI-005를 충족하기 위한 직접 의존성이다. Paging 3, DB cache, Redis, 범용 provider abstraction은 도입하지 않는다. Google client 추가는 조건부 보완과 공식 field mask·오류 경계를 격리하기 위한 최소 구조다.

SC-001~002의 단일 제공자 5초, SC-013의 순차 보완 10초와 SC-008의 provider별 재시도 11초 경계는 mock clock으로 검증한다. 실제 credential 검증은 공유 dev/staging에서만 수행해 quota와 Google 비용을 통제한다.
