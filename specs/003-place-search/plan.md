# Implementation Plan: 장소 검색

**Branch**: `003-place-search` (Spec Kit 논리 식별자; 현재 Git branch `docs/ts-f003-place-search-spec`) | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-place-search/spec.md`

## Summary

인증된 사용자가 키워드·내부 카테고리·선택 지역을 조합해 TourAPI 관광지를 검색하고 상세 정보를 조회한다. Backend는 TourAPI `KorService2`를 유일한 외부 원천으로 사용해 검색 결과와 상세를 길픽의 안정적인 DTO로 정규화하고, 5초 timeout과 일시적 실패 1회 재시도를 적용한다. 검색·상세만으로 DB에 장소를 저장하거나 cache를 두지 않으며, 일정 저장은 F004로 남긴다. Android는 명시적 검색 실행, cursor 추가 조회, 상세 진입과 복귀 상태 보존을 구현하고 기존 theme token·상태 패턴을 재사용한다.

## Technical Context

**Language/Version**: Backend Python 3.13; Android Kotlin 2.4.10

**Primary Dependencies**: Backend는 기존 FastAPI 0.141.1, Pydantic Settings 2.10.1, httpx2 2.10.0, PyJWT 2.10.1을 재사용한다. Android는 기존 Compose BOM 2026.08.00, Lifecycle 2.11.0, Retrofit 3.0.0, OkHttp 5.5.0, kotlinx.serialization 1.11.0에 Navigation Compose 2.9.8, Coil Compose·OkHttp 3.6.0을 추가한다.

**Storage**: N/A. F003 검색·상세 결과는 영구 저장하거나 server cache하지 않는다. F004가 일정 추가 시 `places` 최소 참조정보를 저장한다.

**Testing**: Backend pytest, pytest-asyncio, FastAPI TestClient, httpx2 MockTransport를 사용한 contract·client·service test와 credential이 있을 때의 수동 실제 연동 검증; Android JUnit, kotlinx-coroutines-test, MockWebServer, Compose UI test, Navigation test, 실제 기기 또는 AVD screenshot 검증

**Target Platform**: Docker 기반 AWS Linux Backend; Android 8.0 이상(`minSdk 26`, `targetSdk 36`, `compileSdk 37`)

**Project Type**: Android mobile app + REST API web service

**Performance Goals**: 정상 외부 환경에서 검색 결과·결과 없음과 상세 결과를 각각 5초 이내 표시(SC-001, SC-002); 자동 재시도 시 최종 결과 또는 실패 이유를 11초 이내 표시(SC-008); 100%의 pagination 검증에서 중복 노출 0건(SC-003)

**Constraints**: 인증 필수; keyword는 trim 후 2글자 이상이고 category와 단독·조합 가능; 둘 다 없으면 `400`; 명시적 검색 실행만 허용; TourAPI 호출당 5초 timeout; timeout·일시적 5xx만 최대 1회 재시도; TourAPI application error·4xx·인증·quota/rate limit은 재시도 금지; provider credential·원문 오류·응답 body log 금지; 공통 envelope와 opaque cursor 사용; Google Places·일정 추가·지도·DB·Redis·Paging 3는 범위 밖

**Scale/Scope**: F003 endpoint 2개(PLACE-001~002), Android 검색·상세 화면 2개; TourAPI 개발계정 일 1,000회 제한을 전제로 명시적 검색과 20건 단위 조회를 사용하며 운영 전 활용사례 등록과 quota 증설 여부를 환경 Issue에서 확인

## UI Implementation & Validation

**Design Sources**: [ui-guidelines.md](../../docs/design/ui-guidelines.md), [gilpick-design-reference.pen](../../docs/design/gilpick-design-reference.pen), [spec.md](spec.md)의 UI-001~UI-011. `.pen`에는 F003 전용 검색·상세 화면이 없으므로 여행명 검색창과 일정 장소 행은 시각 힌트로만 사용하고 승인 화면으로 간주하지 않는다.

**Tokens & Components**: `GilpickTheme`, `MaterialTheme.colorScheme`, `LocalGilpickColors`, `LocalGilpickSpacing`, `LocalGilpickRadius`를 그대로 사용하며 새 색상·타이포·간격 토큰은 추가하지 않는다. 검색 입력과 버튼은 기존 입력·버튼 규칙을 따르고, 장소 결과 행과 이미지 fallback이 검색·상세에서 실제 재사용될 때만 `com.gilpick.ui.component`로 추출한다. 네트워크 이미지는 Coil `AsyncImage`를 사용하고 고정 썸네일 영역으로 layout shift를 방지한다.

**State & Interaction**: `PlaceSearchUiState`는 편집 중인 `draftCriteria`, 마지막으로 실행한 `committedCriteria`, `items`, `initialLoad`, `appendLoad`, `nextCursor`, `hasNext`, validation error를 분리한다. 입력·filter 변경은 호출하지 않고 검색 버튼 또는 IME Search가 같은 event를 보낸다. 새 검색은 이전 결과와 섞지 않으며 append 중에는 기존 결과를 유지한다. `placeId`를 stable key로 사용해 append 결과를 dedupe한다. `PlaceDetailUiState`는 loading/content/notFound/error를 분리하고 nullable field를 그대로 표현한다. type-safe `PlaceSearchRoute`, `PlaceDetailRoute(placeId)`와 destination-scoped ViewModel로 상세 복귀 시 검색 조건·결과를 유지하며 `LazyListState`로 scroll 위치를 복원한다.

**Accessibility & Adaptive Layout**: 검색 입력에는 항상 보이는 label과 IME Search action을 제공하고, 결과 요약·오류는 적절한 live region으로 알린다. 결과 행 전체는 button semantics와 최소 48×48dp hit area를 가지며 이미지가 정보성이 있으면 장소명을 설명으로 사용하고 중복이면 decorative 처리한다. 읽기 순서는 검색 조건 → 결과 요약 → 결과 목록이며 색만으로 상태를 전달하지 않는다. 360dp와 최대 font scale에서 text wrapping을 우선하고 가로 scroll·잘림을 허용하지 않는다. 태블릿은 동일 단일 열의 읽기 가능한 최대 폭을 사용하며 F003에서 별도 list-detail pane을 추가하지 않는다.

**Visual Validation**: 실제 기기 또는 AVD에서 검색·상세 각각 loading/empty/error/content를 screenshot으로 확인한다. 추가로 1글자 validation, keyword+category+region, append loading·실패 후 기존 결과 유지, 이미지·설명·연락처·운영 안내 누락, 긴 장소명·주소, 360dp, 최대 font scale, TalkBack focus order, 상세 복귀 후 조건·결과·scroll 유지와 라이트 theme 적용을 검증한다. 다크 theme는 저장소에서 미확정이므로 검증 대상이 아니다.

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가 완료.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 외부 장애·빈 결과를 구분하고 재시도 행동을 제공한다. append 실패 시 기존 결과를 보존한다. F003은 상태를 자동 변경하지 않는다. | PASS |
| II. 계약 우선 SDD와 문서 동기화 | clarification 결과를 feature OpenAPI와 `docs/design/api-spec.md` PLACE-001~002에 반영하고, Google Places·구조화된 영업 상태를 F009 범위로 분리한다. | PASS |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | F003 endpoint는 read-only GET이며 DB 상태를 변경하지 않는다. request ID와 provider 오류 분류·latency만 추적한다. | PASS |
| IV. 외부 의존성 실패 격리 | 5초 timeout, 일시적 오류만 1회 재시도, quota 오류 무재시도, 안정적인 오류 code 변환을 적용하고 provider 실패를 empty로 숨기지 않는다. | PASS |
| V. 보안·소유권·최소 데이터 | Access Token을 검증하고 TourAPI key는 Backend secret으로만 주입한다. provider URL query·원문 body·credential을 log 또는 Android에 노출하지 않으며 검색 결과를 저장하지 않는다. | PASS |
| 교차 계약 review | Backend·Android가 공유하는 place DTO, cursor, 오류 code와 navigation 진입점을 구현 PR 전에 양 영역 담당자가 확인해야 한다. | PASS WITH REVIEW CONDITION |

Constitution 위반과 예외는 없다. 교차 계약 review는 설계 위반이 아니라 구현 전 필수 gate다.

### Post-Design Re-check

- [data-model.md](data-model.md)는 transient 검색·상세·cursor·UI state만 정의하며 DB entity나 migration을 추가하지 않는다.
- [contracts/places.openapi.yaml](contracts/places.openapi.yaml)은 인증, query/category 조합, 2글자 검증, opaque cursor, nullable provider field와 `400/401/404/429/502/504` 오류를 명시한다.
- [quickstart.md](quickstart.md)는 mock 정상·누락·empty·timeout·5xx retry·quota 무재시도와 Android 네 상태·복귀 상태를 검증한다.
- TourAPI key와 provider 원문은 어떤 산출물에도 실제 값으로 기록하지 않았다.
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
│   ├── schemas/place.py          # 안정적인 place DTO와 pagination envelope
│   ├── services/place.py         # category mapping, retry, 상세 조합, cursor
│   ├── core/config.py            # TourAPI SecretStr 설정
│   ├── core/logging.py           # TourAPI credential redaction 보강
│   └── main.py                   # places router 등록
├── .env.example
└── tests/
    ├── contract/test_place_contract.py
    ├── integration/test_place_flow.py
    └── unit/
        ├── test_tour_api_client.py
        └── test_place_service.py

android/app/
├── build.gradle.kts              # Navigation Compose, Coil 의존성
├── src/main/java/com/gilpick/
│   ├── MainActivity.kt           # 인증 후 app navigation에 place route 연결
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

**Structure Decision**: 기존 `api/`, `android/` 단일 repository 구조와 router → service → client, Retrofit → repository → ViewModel → stateless screen 흐름을 유지한다. F003은 read-only proxy이므로 ORM model, migration, DB repository, Redis, provider abstraction을 만들지 않는다. Android는 앱에 처음 필요한 type-safe navigation을 `MainActivity` 인근 app graph에 도입하고 `place` package가 검색·상세 상태만 소유한다. F002 navigation 변경과 같은 파일을 건드리면 구현 Issue 간 소유권과 merge 순서를 먼저 조율한다.

## Phase 0 Research Decisions

결정과 근거, 확인하지 못한 외부 계약 검증 항목은 [research.md](research.md)에 기록했다. 구현을 막는 미해결 기술 항목은 없다. TourAPI `arrange` 코드, provider 최대 `numOfRows`, 콘텐츠 유형별 `detailIntro2` 필드명은 실제 credential을 사용한 contract fixture 확정 task에서 공식 매뉴얼과 함께 검증한다.

## Phase 1 Design Outputs

- transient 데이터와 UI 상태: [data-model.md](data-model.md)
- REST 계약: [contracts/places.openapi.yaml](contracts/places.openapi.yaml)
- 종단간 검증 절차: [quickstart.md](quickstart.md)

## Complexity Tracking

Constitution 위반은 없다. Navigation Compose와 Coil은 각각 UI-009의 back stack 상태 보존과 UI-005의 HTTPS 이미지·fallback을 충족하기 위한 직접 의존성이다. Paging 3, DB cache, Redis, 범용 외부 제공자 abstraction은 현재 요구에 필요하지 않아 도입하지 않는다.

SC-001~SC-002의 정상 흐름 5초 목표와 SC-008의 재시도 포함 11초 목표는 CI의 실제 TourAPI 반복 호출로 보장하지 않는다. unit/contract test에서는 호출당 5초 timeout, 최대 1회 재시도와 전체 11초 경계를 검증하고, credential이 준비된 공유 dev/staging quickstart에서 사용자 체감 시간을 기록한다. 개발계정 일 1,000회 제한 때문에 CI에서 실제 TourAPI를 반복 호출하지 않는다.
