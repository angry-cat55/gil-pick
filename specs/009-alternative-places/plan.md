# Implementation Plan: 대체 장소 추천

**Branch**: `docs/jy-f009-alternative-places` (Spec Kit 논리 식별자 `009-alternative-places`) | **Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-alternative-places/spec.md`

## Summary

F008 `ACTIVE` 감지 결과 하나를 기준으로, 기존 장소 좌표에서 TourAPI 위치 기반 목록을 **2km 한 번** 받아 0.5km→1km→2km 사다리와 소→중→대분류 비교를 메모리에서 적용하고, 거리·혼잡·날씨로 예비 점수를 매긴 뒤 상위부터 Google Text Search 1회씩으로 평점 병합과 운영 상태 확인을 하여 운영 중 후보 10개(확인 상한 20)를 채운다. 최종 점수(거리 35·베이지안 평점 30·혼잡 20·날씨 15, 결손 가중치 재분배)로 정렬해 서명된 `candidateId`와 함께 반환한다(ALT-001). 직접 검색(ALT-002)은 F003 키워드 검색을 유지하면서 기존 장소 기준 2km 밖과 좌표 없는 결과를 서버에서 제외하고 거리·운영 상태·방문 가능·일정 포함 여부를 덧붙인다. `기존 일정 그대로 진행`은 새 계약 **DETECT-004 감지 거절**로 `ACTIVE → DISMISSED`를 멱등하게 처리한다. 후보는 저장하지 않고 새 테이블·migration이 없다.

Android는 새 패키지 `com.gilpick.alternative`에 대체 장소 화면(Figma `AlternativePlacesScreen`/`alternativesEmpty`)·직접 검색 화면·지도를 만들고, 진행 화면(`ActiveTravelScreen`)에 변수 경고 배너 진입점을 더한다. 후보·직접 검색 선택은 `onSelectPlace(SelectedAlternative)` 콜백으로 F010에 넘기며 일정을 바꾸지 않는다. 결정 근거는 [research.md](research.md), 계약은 [contracts/alternatives.openapi.yaml](contracts/alternatives.openapi.yaml), 상태 모델은 [data-model.md](data-model.md), 검증 절차는 [quickstart.md](quickstart.md)다.

## Technical Context

**Language/Version**: Backend Python 3.13 (FastAPI). Android Kotlin, Jetpack Compose, minSdk 기존 유지

**Primary Dependencies**: Backend는 FastAPI, SQLAlchemy 2 async, `httpx`, pydantic — **새 의존성 없음**. TourAPI·Google Places·기상청·서울시 client(`app/clients/*`)와 F008 평가기(`app/services/detection/*`), F003 `PlaceService` 재사용. Android는 Retrofit + kotlinx.serialization, Navigation Compose, Naver map-sdk 3.23.3(이미 포함) — **새 의존성 없음**

**Storage**: PostgreSQL/PostGIS. **신규 테이블·migration 없음**. `detections.status`·`resolved_at` 갱신(DETECT-004)만 쓰기. `itinerary_items`·`places`·`trip_days`·`trips`는 읽기

**Testing**: pytest unit/contract(`httpx` mock transport, 고정 시각). Android JUnit unit(fake repository) + androidTest(Compose UI·navigation·screenshot, ATD `gilpick_api36`) + 실서버 수동 절차(quickstart AND 5)

**Target Platform**: Linux API 서버 단일 인스턴스 + Android 앱

**Project Type**: Mobile + API

**Performance Goals**: ALT-001 응답 p95 6초 이내(TourAPI 1회 ≤5s와 Google ≤20회 병렬 ≤5s가 상한, 정상 시 2~3초). ALT-002는 필터 결과가 있는 일반 요청에서 PLACE-001과 동급이며, 필터 결과가 비면 provider 페이지를 최대 `DIRECT_SEARCH_MAX_PROVIDER_PAGES`(3)까지 순차 조회한다. ALT-002 요청당 외부 호출 상한은 TourAPI 3회·Google 1회다. DETECT-004는 DETECT-003과 동급이다. ALT-001 요청당 외부 호출 상한: TourAPI 1, Google ≤`OPERATING_CHECK_LIMIT`(20), 기상청 1, 서울시 ≤구역 수

**Constraints**: 후보 조회·검색·선택은 일정·경로·감지 상태를 바꾸지 않음(FR-023). DETECT-004는 상태 기반 멱등(constitution III). 선택적 provider 실패는 변수 단위 격리, TourAPI 실패만 추천 실패(constitution IV, FR-021·FR-022). 모든 endpoint는 `detection → trip_day → trip → user` 소유권 검증(constitution V). 조정값(반경·가중치·상한·TTL)은 `policy.py` 한곳. 화면 값은 `com.gilpick.ui.theme` 토큰만 사용

**Scale/Scope**: 신규 endpoint 3개(ALT-001·ALT-002·DETECT-004) + DETECT-001 확장(필터·필드 2개), 신규 서비스 패키지 1개(`services/alternatives/`), client 메서드 1개(`search_by_location`), F003·F008 파일의 동작 불변 승격/분리 refactor 2건. Android 신규 파일 9개 + `progress` 3파일·`route/RouteMap.kt` 수정(`place/PlaceSearchScreen.kt` 수정은 초기 목록형 구현 T031 이력이며, 지도형 전환(#450) 후 직접 검색은 이 파일을 쓰지 않는다). F010·F011 제외

## UI Implementation & Validation

> **변경 기록 (2026-09-13, 팀 결정, #450)**: 직접 검색 화면을 **Figma `MapSearchScreen` 기준 지도형 검색으로 변경**한다(전체 화면 지도 + 결과 번호 마커 + 파란 원형 카테고리 칩 + 떠 있는 검색창 + 하단 결과 시트 + 행별 `선택` 버튼). 아래 문서에서 "F003 `PlaceSearchScreen`의 목록형 검색(`PlaceRow`·`EmptyState`)을 재사용한다"고 정했던 결정은 **폐기**한다. 기존 목록형 구현(T031·T032, `AlternativeSearchScreen.kt`)은 #450에서 지도형으로 교체한다. ALT-002 계약(`place.latitude`·`longitude` 포함)은 마커 표시에 그대로 쓸 수 있어 변경하지 않는다.
>
> **지도형 전환의 세부 결정 (2026-09-14, #450)** — 직접 검색 전환 결정의 근거는 이 절 한곳에 둔다. spec·research는 이 절을 참조한다.
> - **반경 2km (spec FR-019, 2026-09-16 #586 확정)**: 직접 검색 결과를 기존 장소(감지 결과의 일정 장소) 좌표 기준 2km 이내로 한정한다.
>   - **기준점 근거**: Figma `MapSearchScreen` 시트 부제 "경복궁 기준 2km 이내"의 `경복궁`은 예시 값이다. 같은 Figma 흐름에서 직접 검색은 `AlternativePlacesScreen`("경복궁 · 방문 어려움 감지")의 `직접 검색`으로 들어오므로 `경복궁`은 감지된 기존 장소를 가리킨다. 서버 ALT-002도 이미 감지 결과의 일정 항목 장소 좌표를 거리 기준점으로 쓴다(`api/app/services/alternatives/__init__.py` `search`, `docs/design/api-spec.md` ALT-002 `distanceMeters`). 2km는 FR-002 후보 추천의 최대 반경과 같다.
>   - **반경 필터는 서버에서 적용해야 한다(화면 필터로는 불가).** ALT-002는 `query`·`cursor`·`limit`(최대 20)만 받고, 서버는 TourAPI 키워드 검색(`searchKeyword2`, 전국, 반경 미지원)을 페이지 단위로 그대로 넘긴다. 화면에서 2km 밖을 걸러내면 한 페이지가 비어도 `hasNext=true`가 되고, `검색 결과 N곳`이 실제 개수와 달라지며, 무한 스크롤이 빈 페이지를 계속 부른다. TourAPI 키워드 검색은 반경을 지원하지 않고 위치 기반 목록(`locationBasedList2`)은 키워드를 지원하지 않는다(research R1).
>   - 백엔드 반영 전에는 앱이 "2km 이내" 문구를 표시하지 않는다(사실과 다른 안내 방지). 결과 행의 `기존 장소에서 N m` 거리 표시는 유지한다.
> - **카테고리 칩**: 칩을 고르지 않으면 제한 없음, 고르면 해당 카테고리로 한정한다(spec FR-019). 검색 API에 카테고리 필터 파라미터 추가를 백엔드와 조율한다(서버 `PlaceService.search_places`는 `category` 인자가 있으나 ALT-002가 `None`으로 호출).
> - **`혼잡`·`마감` 배지**: 검색 API 응답에 혼잡도·마감 여부 값 추가를 백엔드와 조율한다. 값이 없으면 배지를 그리지 않는다(ui-guidelines 12절, 값을 지어내지 않음).
>
> **반경 적용 방식 조사 (결정: A, 2026-09-16 #586)**
>
> | 방식 | 동작 | 외부 호출량(검색 1페이지 기준) | 성능·정확도 영향 |
> |---|---|---|---|
> | A. 키워드 검색 후처리 + 페이지 재구성 | `searchKeyword2` 결과를 받아 기존 장소 좌표와 haversine 거리로 2km 밖을 버리고, 20건이 찰 때까지 TourAPI 다음 페이지를 이어 받아 서버 cursor를 새로 만든다 | 전국 결과 중 2km 안 비율에 반비례. 흔한 키워드("카페")는 TourAPI 페이지 여러 번(상한 필요), 드문 키워드는 끝까지 넘겨도 0건일 수 있음 | 응답 시간이 키워드마다 크게 흔들림(ALT-002 "PLACE-001과 동급" 목표 초과 가능). 호출 상한에 걸리면 2km 안 결과를 놓칠 수 있음. Google 보완(FR-020) 결과도 같은 후처리 필요 |
> | B. 위치 기반 목록 + 이름 필터 | `locationBasedList2`(mapX·mapY·radius=2000, `numOfRows` 크게)를 1회 받아 서버 메모리에서 검색어로 장소명을 거르고 페이지를 만든다(ALT-001 R1과 같은 호출) | TourAPI 1회(한 요청에 최대 `numOfRows`, 초과분은 추가 페이지) | 응답 시간이 안정적이고 반경이 정확함. 대신 검색어가 **장소명 부분 일치**로만 동작해 `searchKeyword2`의 키워드 매칭과 결과가 달라질 수 있음(FR-018·FR-020 "F003 검색과 동일" 재정의 필요). Google 보완 규칙과의 결합 방식 재설계 필요 |
> | C. 반경 파라미터(`radiusMeters`) 추가 | ALT-002에 선택 파라미터를 두고, 서버 내부는 A 또는 B로 구현한다. 앱은 2000을 보낸다 | 내부 구현(A 또는 B)을 따른다 | 계약이 명시적이라 향후 반경 조정이 쉬움. 파라미터만으로 성능 문제가 풀리지는 않으므로 A/B 선택이 여전히 필요. 계약·api-spec·계약 test 변경 범위가 가장 큼 |

**채택한 방식 A**: F003 키워드 검색 의미와 기존 ALT-002 query 계약을 유지한다. 한 provider 페이지가 모두 반경 밖이면 다음 cursor를 이어 조회하고, 요청당 최대 3페이지만 확인한다. 3페이지 안에 2km 이내 결과가 없으면 빈 목록과 `hasNext=false`를 반환해 빈 페이지에 다음 cursor를 노출하지 않는다. 직접 검색 선택의 F010 미리보기는 검색 결과를 고르는 후속 흐름이지 반경 자격을 재판정하는 계약이 아니므로 이번 Issue에서 별도 2km 검증을 추가하지 않는다.

**Design Sources**: `docs/design/ui-guidelines.md`(3·5·9·10절), Figma Make 저장소 사본 `docs/design/figma-make/src/screens/AlternativePlacesScreen.tsx`(`hasResults` true/false), `ActiveTravelScreen.tsx`(변수 경고 영역), `MapSearchScreen.tsx`(직접 검색 — 지도형, 2026-09-13 변경). ~~F003 `PlaceSearchScreen.kt`(직접 검색 재사용)~~ 폐기. Figma의 `이동 시간 N분 증가` 근거 문구는 F010 범위라 표시하지 않는다(spec UI-003).

**Tokens & Components**: 색은 `GilpickTheme` 토큰만 사용 — 경고 아이콘 박스·변수 칩·배너 `warningContainer`/`warning`, 1위 강조 배경 `primaryContainer`, 보조 글자 `muted`·`faint`, 폐점 임박 `warning`. 새 토큰 추가 없음. 재사용 component: `RouteMap`의 `circleMarker`·`pillMarker`·NaverMap 초기화(→ `internal`), ~~`PlaceSearchScreen`의 `PlaceRow`·`EmptyState`(→ `internal` + 행 하단 slot)~~(직접 검색 목록형 재사용 폐기, 2026-09-13), ui-guidelines 9절 `ErrorScreen` 형식은 `route`/`progress`가 쓰는 오류 composable 재사용. 신규: `AlternativePlacesScreen`, `AlternativeMap`, `AlternativeSearchScreen`(지도형: 지도·번호 마커·칩·결과 시트, #450), `VariableWarningBanner`(progress 패키지, 배너)

**State & Interaction**: `AlternativeUiState` = Loading(1초 지연 표시) / Error(재시도·돌아가기, 기존 일정 유지) / Closed(409, 진행 화면으로) / Content(후보 목록, `items` 비면 empty 표현, `refreshing`은 기존 목록 유지, `dismissPending`·`dismissError`). 상세(DETECT-002)+후보(ALT-001) 병렬 조회, `LifecycleResumeEffect`로 재조회. 후보 선택·직접 검색 선택 → `onSelectPlace(SelectedAlternative)`; `기존 일정 그대로 진행` → DETECT-004 → `onDismissed`. 배너: `ProgressUiState.Content.bannerDetection`(오늘·당일 미완료·ETA 최소 1건, Figma 단일 배너), 탭 → `onOpenAlternatives(detectionId)`. 배너 없음은 빈 상태를 만들지 않는다(spec UI-001)

**State & Interaction — 직접 검색(지도형, #450)**: `AlternativeSearchUiState.phase`는 기존 `Idle`/`Loading`/`Content`/`Empty`/`TooShort`/`Failed`를 유지하고, 결과 시트 행과 지도 마커가 같은 선택 상태(`selectedPlaceId`)를 공유한다. 행 탭·마커 탭 모두 선택을 바꾸고, `선택` 버튼만 `onSelectPlace(SelectedAlternative(candidateId=null))`를 호출한다. **지도형 UI의 조건부 표시는 검색 API 응답에 해당 필드가 있는지로 판단한다.** 카테고리 칩은 응답(또는 계약)에 카테고리 필터 지원 필드가 있을 때만, `혼잡`·`마감` 배지는 결과 항목에 혼잡도·마감 여부 필드 값이 있을 때만 그린다. 반경 부제("{기존 장소명} 기준 2km 이내")는 서버 반경 필터 적용이 계약에 반영된 뒤에만 표시한다. 앱 버전·원격 설정으로 켜지 않는다.

**Accessibility & Adaptive Layout**: 터치 대상 `sizeIn(minHeight = 48.dp)`·간격 8dp, 아이콘 버튼 `contentDescription`, 후보 행 `contentDescription = "N위 이름, 카테고리, 거리, 운영 상태"`, TOP·폐점 임박·방문 불가는 배지·문구·테두리 병기(색 단독 금지), 360dp·fontScale 2.0에서 `weight(1f)`+줄바꿈으로 잘림 없음(F006 `오늘로 돌아가기` 교훈), 지도 정보는 목록으로 중복 제공(UI-004), `statusBarsPadding`/`navigationBarsPadding`

**Attribution (UI-011, 2026-09-16)**: 대체 장소 후보 목록 하단과 직접 검색 결과 시트 하단에 TourAPI 결과(`placeId`가 `tourapi:`)가 하나라도 있으면 `출처: ⓒ한국관광공사`를 목록 단위 한 줄 텍스트로 표시한다(필수). 문구·표시 조건은 F003 `place/PlaceLabels.kt`의 `tourApiAttributionText()`를 재사용하고, 후보·결과별 배지·로고 이미지는 쓰지 않는다.

**Visual Validation**: `AlternativeScreenshotTest` 4상태 × 2배율 8장 + `ActiveTravelScreenshotTest` 배너 2장 + 직접 검색 지도형 4상태(검색 전·결과 있음·결과 없음·검색 실패) × 2배율 8장(UI-009·UI-010·SC-009, tasks T046)(ATD `captureToImage`; 다이얼로그·시트는 inline content로 캡처). 실기기/`gilpick_api36_play` 실서버 절차는 quickstart AND 5. 적용하지 않는 상태: ~~직접 검색 화면의 `empty`는 F003 `EmptyState` 재사용(새 표현 없음)~~ → 지도형 전환 후 직접 검색 `empty`는 결과 시트 안 빈 상태로 표시(#450, ui-guidelines 5절 "목록·검색 영역 안 빈 상태"), 배너의 `loading`·`error`는 없음(실패 = 숨김)

### Figma 대조 결과 (T003, 2026-09-09)

Figma Make `AlternativePlacesScreen.tsx`(`hasResults` true = `alternativePlaces`, false = `alternativesEmpty`)와 `ActiveTravelScreen.tsx`(변수 경고 배너)를 2026-09-09 재조회했다. **저장소 사본 `docs/design/figma-make/src/screens/*.tsx`와 차이 없음.** 아래는 spec이 요구하지만 Figma에 없는 상태 표현과, Figma에 있지만 F009가 쓰지 않는 요소다. Figma 변경 요청은 없다(모두 코드에서 spec 기준으로 처리, Figma 시각 형식은 그대로 따른다).

| 요소 | Figma | F009 구현 | 근거 |
|---|---|---|---|
| 후보 근거 `이동 시간 N분 증가`·`현재보다 8분 가까워요` | 있음(`benefit` 고정 문구) | 표시하지 않음. `reasons` code(`INDOOR`·`NOT_CROWDED`·`NO_RAIN_RISK`·`CLOSER`·`OPEN_AT_ETA`)만 `AlternativeLabels`로 문구화, 모르는 code는 생략 | UI-003, F010 범위 |
| `운영시간 확인 불가`(`operatingStatus=UNKNOWN`) | 없음(`18:00 마감`·`종일 개방`만) | 마감 자리에 `운영시간 확인 불가` 문구(`muted`) | UI-003 |
| 평점 없음(`adjustedRating=null`) | 없음(항상 `★4.x`) | 평점 항목 생략(`카테고리 · 거리`만) | UI-003 |
| 폐점 임박(`CLOSING_SOON`) | 색(`#F97316`)만 | `warning` 색 + `마감 임박` 문구 병기(색 단독 금지) | UI-003·UI-008 |
| 1위 후보 | 배경 `#F0F6FF` + `TOP` 배지 | 그대로 + contentDescription `1위 …` | UI-003·UI-008 |
| 후보 점수 `displayScore` | 없음 | 표시하지 않음(정렬 순서로만 드러남). F010이 필요하면 `SelectedAlternative.displayScore`로 전달 | UI-003 목록에 없음 |
| 선택 버튼 `경로 비교`·`비교` | 있음 | 라벨 그대로, 동작은 `onSelectPlace(SelectedAlternative)`로 F010에 전달(일정 미변경) | UI-005, FR-023 |
| 직접 검색 `방문 불가`·`이미 일정에 있음` | 없음(`MapSearchScreen`은 지도·결과 시트형) | ~~F003 `PlaceRow` 하단 slot~~ → 2026-09-13부터 `MapSearchScreen` 결과 시트 행에 `기존 장소에서 820m` + 상태 문구, 행·`선택` 버튼 비활성(문구+흐림 병기), `visitable=false`면 선택 불가(#450) | UI-007·UI-008 |
| 직접 검색 좌표 없음 | 없음 | 서버가 결과에서 제외 | FR-019, UI-007 |
| 추천 실패 `error`(`다시 시도하기`·돌아가기) | 없음 | ui-guidelines 9절 `ErrorScreen` 형식, `route`/`progress` 오류 composable 재사용, 기존 일정 유지 | UI-006 |
| 처리된 감지(`409 DETECTION_NOT_ACTIVE`) | 없음 | 안내 문구 + `진행 화면으로` 버튼 | UI-006 |
| `loading` | 없음 | 1초 초과 시에만 대기 표시 | UI-006 |
| 거절 요청 중·실패 | 없음 | `기존 일정 그대로 진행` 비활성(`dismissPending`) + 실패 시 오류 표시 후 화면 유지(`dismissError`) | UI-005 |
| 배너 `N분 전 감지` | 있음(`5분 전 감지` 고정) | `createdAt` 기준 경과 시간, F006 매분 `now` tick 재사용 | UI-001 |
| 배너 감지 여럿 | 단일 배너 | `eta` 가장 이른 `ACTIVE` 1건만, 나머지는 F011 감지 목록 | UI-001 |
| 감지 요약 부제·변수 칩(`🌧 오후 강수`·`👥 매우 혼잡`·`⏰ 마감 임박`) | 고정 문구 3개 | 부제는 DETECT-002 `reason` 그대로. 칩은 F008 `variables` 중 위험 판정(`crowded`·`atRisk`·`closingSoon`)된 변수만 Figma 이모지·문구로 표시(0~3개) | UI-002 |
| 배너·화면 상단 아이콘 | 경고 삼각형 고정 | `primaryType`과 무관하게 Figma 아이콘 유지 | UI-001·UI-002 |

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
│   │   ├── AlternativeSearchScreen.kt   # 직접 검색 — 2026-09-13 지도형(MapSearchScreen)으로 변경(#450). 기존 PlaceRow/EmptyState 재사용 결정 폐기
│   │   ├── AlternativeLabels.kt         # 거리·운영 상태·근거 코드 → 문구, 시각 KST
│   │   └── AlternativeNavigation.kt     # AlternativePlacesRoute(detectionId, tripId), AlternativeSearchRoute, alternativeGraph(repository, map, onSelectPlace, onDismissed, onSessionExpired)
│   ├── progress/
│   │   ├── ProgressUiState.kt           # Content.activeDetections/bannerDetection
│   │   ├── ProgressViewModel.kt         # AlternativeRepository? 주입, DETECT-001 병렬 조회(실패 격리)
│   │   └── ActiveTravelScreen.kt        # VariableWarningBanner + onOpenAlternatives
│   ├── route/RouteMap.kt                # circleMarker/pillMarker/초기화 internal 공개 (F005)
│   ├── place/PlaceSearchScreen.kt       # PlaceRow/EmptyState internal + trailing slot (F003) — 직접 검색 지도형 전환(#450) 후 F009에서 더는 쓰지 않음
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
