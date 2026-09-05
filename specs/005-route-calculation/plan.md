# Implementation Plan: 경로 계산

**Branch**: `docs/jh-f005-route-calculation` | **Date**: 2026-09-06 | **Spec**: `specs/005-route-calculation/spec.md`

## Summary

날짜별 일정 저장이 확정되면 장소 사이 구간을 저장된 이동수단에 따라 TMAP(도보·자동차) 또는 ODsay(대중교통)로 계산하고, 현재 `schedule_version`에 대응하는 경로 하나를 저장·조회한다. 일정 transaction은 외부 호출 전에 커밋해 경로 실패와 분리한다. 여러 구간은 동시에 계산하되 전체 10초 deadline을 적용하고, 일시적 실패만 남은 시간 안에서 1회 재시도한다. 실패 시 일정은 보존하고 `FAILED` 상태와 동일 입력 재시도만 제공한다.

## Technical Context

**Language/Version**: Backend Python 3.13, Android Kotlin/JVM 17

**Primary Dependencies**: FastAPI 0.141.1, SQLAlchemy 2.0.52 async, asyncpg 0.30.0, GeoAlchemy2 0.18.x, httpx2 2.10.0; Jetpack Compose BOM 2026.08.00, Navigation Compose 2.10.0, Retrofit 3.0.0, OkHttp 5.5.0, Naver Maps Android SDK 3.23.x 추가

**Storage**: PostgreSQL/PostGIS의 기존 일정·장소와 신규 `routes` table(JSONB 정규화 payload 포함)

**Testing**: pytest/pytest-asyncio/MockTransport/PostgreSQL contract·integration test; JUnit4/coroutines-test/MockWebServer/Compose UI·screenshot test

**Target Platform**: Linux API 서버, Android minSdk 26/targetSdk 36

**Project Type**: Android 모바일 앱 + REST API

**Performance Goals**: 일정 DB 저장을 완료한 뒤 경로 계산 단계의 최초 외부 호출부터 최종 성공·실패 응답까지 10초 이내; 1초를 넘는 조회·계산에는 loading 표시

**Constraints**: 제공자 호출 시도당 최대 5초, 일시적 오류만 최대 1회 재시도, 전체 10초 deadline 우선; 최대 10개 장소·9개 구간; 정상 경로 임의 재계산과 후보 선택 제외; 외부 호출 중 DB transaction 유지 금지

**Scale/Scope**: 계획 경로 계산·저장·조회와 Android 표시만 포함. 현재 위치, 실제 출발, 도착예정시각, 진행 중 남은 경로 재계산은 F006 이후 범위

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`, Figma Make 사본의 `DayRouteScreen.tsx`, `TripDetailScreen.tsx`, `ScheduleEditScreen.tsx`, 공통 상태의 `ErrorScreen.tsx`. `RouteRecalculatingScreen.tsx`는 F006 참고 자료이며 F005 화면으로 구현하지 않는다.

**Tokens & Components**: 기존 `com.gilpick.ui.theme` token과 `TripDetailScreen` 구조를 재사용한다. `DayRouteScreen`, `RouteMap`, `RouteSummary`, `RouteSegmentList`, `RouteStatePanel`을 책임 단위로 둔다. Naver `MapView`는 lifecycle을 전달하는 얇은 `AndroidView` adapter로 격리하고 marker/path 데이터만 받는다.

**State & Interaction**: ViewModel은 `StateFlow<RouteUiState>`의 `Loading`, `Empty`, `Error`, `Content`를 노출한다. 0개 장소는 일정 추가 행동이 있는 `Empty`, 1개는 합계 0의 `Content`다. `Error`와 일정 저장 후 `FAILED` 영역만 같은 일정 version의 `다시 시도`를 제공한다. 정상 경로에는 재계산·후보 선택을 제공하지 않는다. 저장 완료 후 경로 성공·실패 모두 여행 상세로 이동한다.

**Accessibility & Adaptive Layout**: 지도 정보는 같은 순서의 구간 목록으로도 제공한다. 동작 영역 48×48dp 이상, 인접 영역 8dp 이상, 아이콘 semantics를 적용한다. 이동수단·상태는 색상뿐 아니라 아이콘·문구를 함께 쓴다. 360dp·최대 글자 배율에서 핵심 문구와 재시도가 가로 스크롤 없이 보여야 하며 지도는 system inset을 침범하지 않는다.

**Visual Validation**: 360dp phone, 일반 phone, 최대 font scale에서 loading/empty/error/content, 1개 장소, 혼합 이동수단을 screenshot으로 Figma와 비교한다. 실제 지도에서 이동·확대·축소, marker 순서, polyline, attribution, 목록 순서 일치를 확인한다.

## Constitution Check

*GATE: Phase 0 전 및 Phase 1 후 재검토 완료.*

| 원칙 | 반영 | 결과 |
|---|---|---|
| I. 사용자 통제와 fallback | 경로 실패가 일정 저장·조회·편집을 막지 않고 실패 원인과 재시도를 제공 | PASS |
| II. 계약 우선 SDD | F004 응답 확장과 F005 조회·재시도 계약을 OpenAPI로 정의하고 API 명세·ERD 동기화 대상 명시 | PASS |
| III. 일관성·멱등성·추적성 | `schedule_version`, 버전별 경로 unique 제약과 upsert, request ID log | PASS |
| IV. 외부 실패 격리 | transaction 분리, timeout·제한 재시도·FAILED 상태 보존 | PASS |
| V. 보안·최소 데이터 | 인증·소유권·기간 검증, provider key 비노출, 정밀 좌표 log 금지 | PASS |

Phase 1 후에도 위반이나 예외는 없다. 신규 migration과 외부 계약 변경에는 contract/integration test와 관련 문서 동기화가 필요하다.

## Project Structure

```text
specs/005-route-calculation/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/route.openapi.yaml
└── tasks.md                         # speckit-tasks 단계에서 생성

api/app/
├── api/v1/{itinerary,route}.py
├── clients/{tmap,odsay}.py
├── models/{itinerary,route}.py
├── schemas/{itinerary,route}.py
└── services/{itinerary,route}.py

android/app/src/main/java/com/gilpick/
├── trip/{TripApi,TripRepository,TripDetailViewModel,TripDetailScreen}.kt
└── route/{RouteApi,RouteRepository,RouteViewModel,DayRouteScreen,RouteMap}.kt
```

**Structure Decision**: 기존 모바일+API 구조를 유지한다. provider adapter와 orchestration은 Backend `route`, 표시 상태와 지도 adapter는 Android `route`에 둔다. 일정 저장 응답 확장은 기존 `itinerary` 경계를 유지한다.

## Implementation Design

1. 일정 PUT은 인증·소유권·날짜·version·입력을 검증한다.
2. 짧은 transaction에서 일정을 저장하고 `schedule_version`을 확정한다. 경로 입력이 바뀌면 이전 현재 경로를 `HISTORICAL`로 바꾸고 커밋한다.
3. 0개는 `NOT_CALCULATED`, 1개는 외부 호출 없이 합계 0의 `READY`를 저장한다.
4. 2개 이상이면 immutable snapshot의 구간을 구조적 동시성으로 계산한다. 전체 deadline은 10초, 시도 timeout은 `min(5초, 남은 시간)`이다. timeout·429·5xx만 남은 시간 안에서 1회 재시도한다.
5. ODsay는 검색 결과의 기본 추천 후보만 채택하고 지도 형상 상세 호출도 같은 deadline에 포함한다.
6. 모든 구간 성공 시 별도 transaction에서 현재 version을 재확인하고 `READY`를 활성화한다. 하나라도 실패하면 `FAILED`를 기록한다. version이 달라졌다면 결과를 현재 경로로 저장하지 않는다.
7. 일정 저장 응답은 경로 실패와 관계없이 성공이다. 재시도 endpoint는 현재 `FAILED`·같은 version만 허용한다. 별도 멱등성 저장소 없이 `(trip_day_id, schedule_version)` 경로를 upsert하여 중복 요청에도 같은 경로 하나만 유지한다.

외부 오류는 `ROUTE_PROVIDER_TIMEOUT`, `ROUTE_PROVIDER_RATE_LIMITED`, `ROUTE_PROVIDER_UNAVAILABLE`, `ROUTE_NOT_FOUND`, `ROUTE_INVALID_RESULT`로 정규화한다. log에는 request ID, trip/day, version, provider, attempt, latency, 결과 code를 기록하되 좌표·key는 제외한다.

## Complexity Tracking

Constitution 위반이 없어 별도 정당화 항목은 없다.
