# Implementation Plan: 대체 장소 추천

**Branch**: `docs/jy-f009-alternative-places` (Spec Kit 논리 식별자 `009-alternative-places`) | **Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-alternative-places/spec.md`

## Summary

F008 `ACTIVE` 감지 결과 하나를 기준으로, 기존 장소 좌표에서 TourAPI 위치 기반 목록을 **2km 한 번** 받아 0.5km→1km→2km 사다리와 소→중→대분류 비교를 메모리에서 적용하고, 거리·혼잡·날씨로 예비 점수를 매긴 뒤 상위부터 Google Text Search 1회씩으로 평점 병합과 운영 상태 확인을 하여 운영 중 후보 10개(확인 상한 20)를 채운다. 최종 점수(거리 35·베이지안 평점 30·혼잡 20·날씨 15, 결손 가중치 재분배)로 정렬해 서명된 `candidateId`와 함께 반환한다(ALT-001). 직접 검색(ALT-002)은 F003 검색을 그대로 실행하고 거리·운영 상태·방문 가능·일정 포함 여부를 덧붙인다. `기존 일정 그대로 진행`은 새 계약 **DETECT-004 감지 거절**로 `ACTIVE → DISMISSED`를 멱등하게 처리한다. 후보는 저장하지 않고 새 테이블·migration이 없다.

Android는 새 패키지 `com.gilpick.alternative`에 대체 장소 화면(Figma `AlternativePlacesScreen`/`alternativesEmpty`)·직접 검색 화면·지도를 만들고, 진행 화면(`ActiveTravelScreen`)에 변수 경고 배너 진입점을 더한다. 후보·직접 검색 선택은 `onSelectPlace(SelectedAlternative)` 콜백으로 F010에 넘기며 일정을 바꾸지 않는다. 결정 근거는 [research.md](research.md), 계약은 [contracts/alternatives.openapi.yaml](contracts/alternatives.openapi.yaml), 상태 모델은 [data-model.md](data-model.md), 검증 절차는 [quickstart.md](quickstart.md)다.

## Technical Context

**Language/Version**: Backend Python 3.13 (FastAPI). Android Kotlin, Jetpack Compose, minSdk 기존 유지

**Primary Dependencies**: Backend는 FastAPI, SQLAlchemy 2 async, `httpx`, pydantic — **새 의존성 없음**. TourAPI·Google Places·기상청·서울시 client(`app/clients/*`)와 F008 평가기(`app/services/detection/*`), F003 `PlaceService` 재사용. Android는 Retrofit + kotlinx.serialization, Navigation Compose, Naver map-sdk 3.23.3(이미 포함) — **새 의존성 없음**

**Storage**: PostgreSQL/PostGIS. **신규 테이블·migration 없음**. `detections.status`·`resolved_at` 갱신(DETECT-004)만 쓰기. `itinerary_items`·`places`·`trip_days`·`trips`는 읽기

**Testing**: pytest unit/contract(`httpx` mock transport, 고정 시각). Android JUnit unit(fake repository) + androidTest(Compose UI·navigation·screenshot, ATD `gilpick_api36`) + 실서버 수동 절차(quickstart AND 5)

**Target Platform**: Linux API 서버 단일 인스턴스 + Android 앱

**Project Type**: Mobile + API

**Performance Goals**: ALT-001 응답 p95 6초 이내(TourAPI 1회 ≤5s와 Google ≤20회 병렬 ≤5s가 상한, 정상 시 2~3초). ALT-002·DETECT-004는 PLACE-001·DETECT-003과 동급. 요청당 외부 호출 상한: TourAPI 1, Google ≤`OPERATING_CHECK_LIMIT`(20), 기상청 1, 서울시 ≤구역 수

**Constraints**: 후보 조회·검색·선택은 일정·경로·감지 상태를 바꾸지 않음(FR-023). DETECT-004는 상태 기반 멱등(constitution III). 선택적 provider 실패는 변수 단위 격리, TourAPI 실패만 추천 실패(constitution IV, FR-021·FR-022). 모든 endpoint는 `detection → trip_day → trip → user` 소유권 검증(constitution V). 조정값(반경·가중치·상한·TTL)은 `policy.py` 한곳. 화면 값은 `com.gilpick.ui.theme` 토큰만 사용

**Scale/Scope**: 신규 endpoint 3개(ALT-001·ALT-002·DETECT-004) + DETECT-001 확장(필터·필드 2개), 신규 서비스 패키지 1개(`services/alternatives/`), client 메서드 1개(`search_by_location`), F003·F008 파일의 동작 불변 승격/분리 refactor 2건. Android 신규 파일 9개 + `progress` 3파일·`route/RouteMap.kt`·`place/PlaceSearchScreen.kt` 수정. F010·F011 제외

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`(3·5·9·10절), Figma Make 저장소 사본 `docs/design/figma-make/src/screens/AlternativePlacesScreen.tsx`(`hasResults` true/false), `ActiveTravelScreen.tsx`(변수 경고 영역), `MapSearchScreen.tsx`·F003 `PlaceSearchScreen.kt`(직접 검색 재사용). Figma의 `이동 시간 N분 증가` 근거 문구는 F010 범위라 표시하지 않는다(spec UI-003).

**Tokens & Components**: 색은 `GilpickTheme` 토큰만 사용 — 경고 아이콘 박스·변수 칩·배너 `warningContainer`/`warning`, 1위 강조 배경 `primaryContainer`, 보조 글자 `muted`·`faint`, 폐점 임박 `warning`. 새 토큰 추가 없음. 재사용 component: `RouteMap`의 `circleMarker`·`pillMarker`·NaverMap 초기화(→ `internal`), `PlaceSearchScreen`의 `PlaceRow`·`EmptyState`(→ `internal` + 행 하단 slot), ui-guidelines 9절 `ErrorScreen` 형식은 `route`/`progress`가 쓰는 오류 composable 재사용. 신규: `AlternativePlacesScreen`, `AlternativeMap`, `AlternativeSearchScreen`, `VariableWarningBanner`(progress 패키지, 배너)

**State & Interaction**: `AlternativeUiState` = Loading(1초 지연 표시) / Error(재시도·돌아가기, 기존 일정 유지) / Closed(409, 진행 화면으로) / Content(후보 목록, `items` 비면 empty 표현, `refreshing`은 기존 목록 유지, `dismissPending`·`dismissError`). 상세(DETECT-002)+후보(ALT-001) 병렬 조회, `LifecycleResumeEffect`로 재조회. 후보 선택·직접 검색 선택 → `onSelectPlace(SelectedAlternative)`; `기존 일정 그대로 진행` → DETECT-004 → `onDismissed`. 배너: `ProgressUiState.Content.bannerDetection`(오늘·ETA 최소)·`extraDetectionCount`, 탭 → `onOpenAlternatives(detectionId)`. 배너 없음은 빈 상태를 만들지 않는다(spec UI-001)

**Accessibility & Adaptive Layout**: 터치 대상 `sizeIn(minHeight = 48.dp)`·간격 8dp, 아이콘 버튼 `contentDescription`, 후보 행 `contentDescription = "N위 이름, 카테고리, 거리, 운영 상태"`, TOP·폐점 임박·방문 불가는 배지·문구·테두리 병기(색 단독 금지), 360dp·fontScale 2.0에서 `weight(1f)`+줄바꿈으로 잘림 없음(F006 `오늘로 돌아가기` 교훈), 지도 정보는 목록으로 중복 제공(UI-004), `statusBarsPadding`/`navigationBarsPadding`

**Visual Validation**: `AlternativeScreenshotTest` 4상태 × 2배율 8장 + `ActiveTravelScreenshotTest` 배너 2장(ATD `captureToImage`; 다이얼로그·시트는 inline content로 캡처). 실기기/`gilpick_api36_play` 실서버 절차는 quickstart AND 5. 적용하지 않는 상태: 직접 검색 화면의 `empty`는 F003 `EmptyState` 재사용(새 표현 없음), 배너의 `loading`·`error`는 없음(실패 = 숨김)

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 추천은 조언이며 후보 조회·검색·선택은 일정·경로·감지 상태를 바꾸지 않는다(FR-023). 사용자가 상태를 바꾸는 유일한 동작(감지 거절)은 명시적 버튼이고, 후보 없음·추천 실패에도 `기존 일정 그대로 진행`·`직접 검색`·`다시 시도`로 다음 행동을 제공한다. 일정 변경·되돌리기는 F010이 확인·되돌리기 수단과 함께 제공한다. | PASS |
| II. 계약 우선 SDD와 문서 동기화 | ALT-001·ALT-002는 `api-spec.md` 7.2 경로를 유지하되 응답을 `place` 중첩·`candidateId`·`operatingStatus`·`closesAt`·`categoryMatchLevel`·`reasons`·`inSchedule`로 확장하고, DETECT-004와 DETECT-001 확장(`status` 필터·`eta`·`reason`)을 신설한다. **같은 PR에서** `api-spec.md` 2·7절, `er-schema.md` 8.1·13절, `requirements.md`·`functional-spec.md` 5절을 동기화한다(data-model.md 4절). F003 `PlaceService` 함수 승격과 F008 `OperatingHoursSource.parse` 분리는 외부 동작이 바뀌지 않으므로 상위 문서를 고치지 않는다. | PASS WITH DOC-SYNC CONDITION |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | DETECT-004는 `ACTIVE`일 때만 `DISMISSED`·`resolved_at`을 한 transaction으로 쓰고, 비-`ACTIVE`는 무변경·현재 상태 반환(상태 기반 멱등, 동시 요청도 `UPDATE … WHERE status='ACTIVE'`로 1회만 반영). 후보 조회는 쓰기 없음. `candidateId`는 감지·장소·평가 시각을 서명해 위조·만료를 검증한다. 후보 조회마다 감지 id·반경·분류 단계·후보 수·provider 가용성을 log(FR-025). 시각은 서버·Asia/Seoul. | PASS |
| IV. 외부 의존성 실패 격리 | TourAPI 5초·1회 재시도, 최종 실패는 `502/504`로 드러낸다(빈 목록 위장 금지, FR-021). Google·기상청·서울시 실패는 후보·변수 단위로 격리하고 가중치를 재분배한다(FR-020·FR-022). Google 확인 상한(20)으로 지연 상한을 고정한다. 앱은 배너 조회 실패를 배너 숨김으로 격리해 진행 화면을 막지 않는다. | PASS |
| V. 보안·소유권·최소 데이터 | ALT-001·ALT-002·DETECT-004 모두 `_owned_detection`(공용 helper)로 `detection → trip_day → trip → user` 검증, 타인 요청 `403/404`(FR-024). 외부 호출에는 장소 좌표만 보내고 사용자 위치를 보내지 않는다. `candidateId` HMAC 키는 기존 `jwt_signing_secret` 재사용, 토큰에는 감지·장소 id와 시각만 담는다. log에 정밀 위치·토큰 원문을 남기지 않는다. | PASS |
| 교차 계약 review | BE: `api/app/api/v1/detections.py`·`schemas/detection.py`(F008, DETECT-001 확장·helper 승격)와 `services/detection/operating_hours_source.py`(F008, `parse` 분리)는 F008 BE 담당 review, `services/place.py`(F003) 승격은 F003 담당 review. FE: `route/RouteMap.kt`(F005)·`place/PlaceSearchScreen.kt`(F003 Android)·`progress/*`(F006/F007) 수정은 해당 화면 원 담당자 review. ALT·DETECT-004 DTO는 BE·FE 담당이 함께 확인한다. | PASS WITH REVIEW CONDITION |

**위반 없음.** Complexity Tracking 비움.

### Post-Design Re-check

Phase 1 산출물(data-model.md·contracts/alternatives.openapi.yaml·quickstart.md) 작성 후 재평가함. 신규 위반 없음. 새 테이블 없이 `detections.resolved_at`을 거절 시각으로 재사용하는 결정은 `er-schema.md` 8.1 설명 갱신으로 동기화한다(doc-sync 조건). 예비 점수 → 상위 확인 파이프라인의 순위 오차와 확인 상한 밖 후보 미반환은 research R3의 ceiling으로 기록했고 `policy.py`에서 조정 가능하다.

## Project Structure

### Documentation (this feature)

```text
specs/009-alternative-places/
├── spec.md
├── plan.md                              # 이 문서
├── research.md                          # Phase 0: R1~R10 결정
├── data-model.md                        # Phase 1: 영속 전이·일시 DTO·Android 상태 모델
├── quickstart.md                        # Phase 1: BE 1~9, AND 1~5 검증 절차
├── contracts/
│   └── alternatives.openapi.yaml        # ALT-001·ALT-002·DETECT-004·DETECT-001 확장
├── checklists/requirements.md
└── tasks.md                             # Phase 2 ($speckit-tasks)
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── clients/
│   │   └── tour_api.py                  # + search_by_location (locationBasedList2)
│   ├── services/
│   │   ├── place.py                     # _tour_place/_google_place/_find_match/_merge_google/_distance/_category → 공개 함수 승격 (F003)
│   │   ├── detection/operating_hours_source.py  # parse(payload, eta) 분리 (F008)
│   │   └── alternatives/
│   │       ├── __init__.py              # AlternativeService: list_candidates / search / dismiss
│   │       ├── policy.py                # 반경 사다리, 가중치, 정규화표, OPERATING_CHECK_LIMIT, CANDIDATE_TTL_MINUTES
│   │       ├── candidates.py            # TourAPI 조회 → 사다리·분류·제외 → 변수 평가 → Google 확인 파이프라인
│   │       ├── scoring.py               # 정규화, 베이지안, 재분배, 동점, displayScore, reasons
│   │       └── candidate_token.py       # issue_candidate_token / verify_candidate_token
│   ├── schemas/
│   │   ├── alternatives.py              # AlternativeCandidate/ListData/SearchItem/Dismiss DTO, 오류 코드
│   │   └── detection.py                 # DetectionListItem + eta·reason (F008)
│   └── api/v1/
│       ├── detections.py                # DETECT-001 status 필터, _owned_detection 공용화, DETECT-004 (F008 파일)
│       └── alternatives.py              # ALT-001, ALT-002 router (+ main.py 등록)
└── tests/
    ├── unit/test_alternative_scoring.py
    ├── unit/test_alternative_candidates.py
    ├── unit/test_candidate_token.py
    ├── unit/test_place_service.py       # 승격 함수 회귀 (F003)
    ├── unit/test_operating_hours_source.py  # parse 회귀 (F008)
    ├── contract/test_alternatives_contract.py
    └── contract/test_detections_contract.py # DETECT-001 확장·DETECT-004

android/app/src/
├── main/java/com/gilpick/
│   ├── alternative/
│   │   ├── AlternativeApi.kt            # DTO + AlternativeService(DETECT-001/002/004, ALT-001/002) + createAlternativeRetrofit
│   │   ├── AlternativeRepository.kt     # Result/AlternativeError, 네트워크·401·409 매핑
│   │   ├── AlternativeUiState.kt        # Loading/Error/Closed/Content, SelectedAlternative
│   │   ├── AlternativeViewModel.kt      # 병렬 조회, 재조회, dismiss, 선택 전달
│   │   ├── AlternativePlacesScreen.kt   # Figma AlternativePlacesScreen + empty/error/closed
│   │   ├── AlternativeMap.kt            # NaverMap: 기존 장소 pill + 후보 순위 원형 (RouteMap helper 재사용)
│   │   ├── AlternativeSearchScreen.kt   # 직접 검색 (PlaceRow/EmptyState 재사용 + 거리·방문 가능 slot)
│   │   ├── AlternativeLabels.kt         # 거리·운영 상태·근거 코드 → 문구, 시각 KST
│   │   └── AlternativeNavigation.kt     # AlternativePlacesRoute(detectionId, tripId), AlternativeSearchRoute, alternativeGraph(repository, map, onSelectPlace, onDismissed, onSessionExpired)
│   ├── progress/
│   │   ├── ProgressUiState.kt           # Content.activeDetections/bannerDetection/extraDetectionCount
│   │   ├── ProgressViewModel.kt         # AlternativeRepository? 주입, DETECT-001 병렬 조회(실패 격리)
│   │   └── ActiveTravelScreen.kt        # VariableWarningBanner + onOpenAlternatives
│   ├── route/RouteMap.kt                # circleMarker/pillMarker/초기화 internal 공개 (F005)
│   ├── place/PlaceSearchScreen.kt       # PlaceRow/EmptyState internal + trailing slot (F003)
│   └── MainActivity.kt                  # alternativeGraph 배선, 배너 → AlternativePlacesRoute, onSelectPlace no-op(F010이 교체)
├── test/java/com/gilpick/alternative/
│   ├── AlternativeApiTest.kt            # DTO 역직렬화
│   └── AlternativeViewModelTest.kt
├── test/java/com/gilpick/progress/ProgressViewModelTest.kt  # 배너 조회·격리 추가
└── androidTest/java/com/gilpick/
    ├── alternative/AlternativePlacesScreenTest.kt
    ├── alternative/AlternativeSearchScreenTest.kt
    ├── alternative/AlternativeNavigationTest.kt
    ├── alternative/AlternativeScreenshotTest.kt
    └── progress/ActiveTravelScreenTest.kt·ActiveTravelScreenshotTest.kt  # 배너 케이스 추가
```

**Structure Decision**: 기존 `api/`(FastAPI 단일 레이아웃)와 `android/app` 구조를 그대로 쓴다. Backend는 F008 `services/detection/`처럼 `services/alternatives/` 하위 패키지에 파이프라인·점수·토큰을 나누고 조정값은 `policy.py`에 모은다. Android는 `progress` 패키지가 이미 F006·F007로 커서 F009 화면을 `alternative` 패키지로 분리하고, 진입 배너만 `progress`에 둔다. F010이 `SelectedAlternative` 콜백과 `candidateId` 검증 함수를 그대로 이어받는다.

## Complexity Tracking

해당 없음(Constitution Check 위반 없음).
