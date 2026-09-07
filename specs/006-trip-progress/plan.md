# Implementation Plan: 여행 진행

**Branch**: `docs/jy-f006-trip-progress` | **Date**: 2026-09-07 | **Spec**: `specs/006-trip-progress/spec.md`

**Input**: Feature specification from `specs/006-trip-progress/spec.md`

## Summary

사용자가 오늘 날짜의 진행을 `오늘 여행 시작`으로 시작하면 서버가 시작 시각을 한 번 고정하고, F005 계획 경로의 구간 이동시간과 체류 시간으로 장소별 ETA를 계산해 저장한다. 도착·출발·건너뛰기·상태 수정은 `PATCH /itinerary-items/{itemId}/status` 하나로 처리하며 서버가 파생 전환·ETA 재계산·당일 완료를 한 transaction으로 적용하고 `progress_transitions`에 기록한다. 계획 경로에 없는 두 구간(시작 위치→첫 장소 도보, 건너뛰기로 생긴 인접 구간)만 F005 provider를 단일 구간으로 호출해 `progress_segments`에 저장한다. Android는 새 `progress` package의 진행 화면과 상태 수정 시트, 여행 상세의 시작 버튼·1회 위치 취득을 구현한다.

## Technical Context

**Language/Version**: Backend Python 3.13, Android Kotlin/JVM 17

**Primary Dependencies**: FastAPI, SQLAlchemy 2 async, GeoAlchemy2, Alembic, httpx(F005 provider client 재사용); Jetpack Compose, Navigation Compose, Retrofit/OkHttp, Naver Maps SDK(F005 `RouteMap` 재사용), **`play-services-location` 추가**

**Storage**: PostgreSQL/PostGIS. 기존 `trip_days`·`itinerary_items` 컬럼 사용, `trip_days.progress_version` 추가, 신규 `progress_transitions`·`progress_segments`(migration 005)

**Testing**: pytest unit/contract/integration(PostgreSQL), MockTransport provider; JUnit4/coroutines-test/MockWebServer, Compose UI·screenshot test on ATD/play AVD

**Target Platform**: Linux API 서버, Android minSdk 26/targetSdk 36

**Project Type**: Android 모바일 앱 + REST API

**Performance Goals**: 시작 응답 2초 이내(위치 없음), 위치 구간 계산 포함 10초 이내(SC-001); 상태 전환 응답 2초 이내(구간 계산 없는 경우); 1초를 넘는 조회에만 loading 표시

**Constraints**: 단일 구간 provider 호출 시도당 5초·일시 오류 1회 재시도·전체 8초 deadline, transaction 밖 호출; 구간 계산 실패는 전환을 막지 않음; `EN_ROUTE`·`ARRIVED` 각 최대 1; 시간 판정은 서버 시각·`Asia/Seoul`; 정밀 위치를 log에 남기지 않음

**Scale/Scope**: 날짜당 최대 10장소, 수동 전환만. 지오펜스·자동 확정·되돌리기(F007), 변수 감지(F008), 알림(F011) 제외

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`, Figma Make 사본 `ActiveTravelScreen.tsx`(진행 화면 정본), `TripDetailScreen.tsx`(시작 버튼), `LocationPermissionScreen.tsx`(권한 안내), `ErrorScreen.tsx`(오류 상태). Figma에 없는 **도착 상태 카드(`다음 장소로 출발`)·당일 완료 상태·상태 수정 시트**는 구현 전 Figma에 추가한 뒤 사본을 갱신하고 그 모양을 따른다(spec UI-002·UI-003·UI-006). Figma의 변수 경고 배너·날씨 안내·알림/변수 버튼·도착/출발 확인 시트·변경 토스트는 F007·F008·F010·F011 범위라 그리지 않는다.

**Figma 추가 요소 확인(T003)**: 2026-09-07 Figma MCP로 `ActiveTravelScreen` 원본을 다시 읽어 저장소 사본과 비교했다. 원본은 사본과 동일하며 아래 4건은 아직 Figma에 없다. Feature Owner(jy)가 Figma Make에서 추가하면 사본을 다시 받아 이 표를 닫는다(T020 선행 조건).

| # | 추가 요소 | 근거 | Figma 반영 |
|---|---|---|---|
| 1 | 다음 장소 카드의 `도착` 상태(`다음 장소로 출발` 단일 행동) | UI-002 | 미반영 |
| 2 | 당일 완료 상태(카드 자리에 완료 표시, 출발 행동 없음) | UI-006 | 미반영 |
| 3 | 상태 수정 시트(장소 행 탭 → 현재 상태별 행동만 표시) | UI-003 | 미반영 |
| 4 | 도착 예정 시각이 지난 카드의 `N분 지났어요` 문구 | UI-002 | 미반영 |

F006 구현에서 제외하는 Figma 요소: 변수 경고 배너·날씨 안내(F008), 알림 버튼(F011), 변수 버튼·장소 변경 토스트·`되돌리기`(F010), 도착·출발 확인 시트(F007). `TripDetailScreen`의 초 단위 카운트다운은 데모 연출이라 구현하지 않는다(UI-007).

**Tokens & Components**: 기존 `com.gilpick.ui.theme` token. 재사용: F005 `RouteMap`(marker/path 데이터만 전달, 시작 위치 marker 추가), F004 `ItineraryLabels`의 이동수단 아이콘·문구, F002/F004 `TripDetailScreen` 구조, 공통 오류 화면. 신규: `ActiveTravelScreen`(header·day dots·`NextPlaceCard`·지도·`ProgressItemList`), `StatusSheet`(ModalBottomSheet), `ProgressLabels`. 상태 칩은 문구+아이콘 병기.

**State & Interaction**: `ProgressViewModel`이 `StateFlow<ProgressUiState>`(`Loading`/`Empty`/`Error`/`Content`)를 노출한다. `Content`는 F004 overview(모든 날짜)와 PROG-001(오늘)을 합친 모델, `viewingDate`, `pendingAction`(요청 중인 전환)을 가진다. 전환 요청 중에는 기존 내용을 유지하고 해당 버튼만 비활성화한다. 실패하면 상태를 바꾸지 않고 원인+`다시 시도`를 보인다. 남은 시간·경과 시간은 저장된 ETA와 기기 시각으로 1분 단위 갱신한다. `onResume`마다 PROG-001을 다시 조회한다. `TripDetailViewModel`에 `startToday()`를 추가하고 위치 취득(권한 요청 → `getCurrentLocation` 10초 timeout → 실패 시 null)을 거쳐 PROG-002를 호출한 뒤 `ActiveTravelRoute(tripId)`로 이동한다. 진입점은 여행 상세의 `오늘 여행 시작`/`여행 진행 화면으로`뿐이다.

**Accessibility & Adaptive Layout**: 터치 48×48dp·간격 8dp, 아이콘 전용 버튼 `contentDescription`, 상태·이동수단은 색+문구+아이콘. 360dp·최대 글자 배율에서 카드 문구(`오후 2:35 · 12분 남았어요`)와 목록이 잘리지 않도록 줄바꿈 허용. 지도 정보는 목록으로도 제공(F005 규칙 유지). edge-to-edge inset 준수.

**Visual Validation**: `gilpick_api36`(captureToImage)로 시작 전·이동 중·도착·완료·건너뜀 포함·당일 완료·다른 날짜·loading/empty/error를 360dp와 최대 글자 배율에서 screenshot. 시트는 F004 방식대로 내용 composable을 inline 렌더해 캡처. `gilpick_api36_play`에서 로컬 API + `adb emu geo fix`로 위치 있는 시작과 권한 거부 흐름을 실제 확인한다.

## Constitution Check

*GATE: Phase 0 전 및 Phase 1 후 재검토 완료.*

| 원칙 | 반영 | 결과 |
|---|---|---|
| I. 사용자 통제와 fallback | 위치 권한·정확도·provider 실패가 시작·수동 전환을 막지 않음(FR-005·FR-020). 수동 출발은 명세대로 전용 되돌리기 없이 상태 수정으로 복구 | PASS |
| II. 계약 우선 SDD | PROG-001/002/006을 OpenAPI로 정의, ERD·api-spec 동기화 항목 명시(data-model.md) | PASS |
| III. 일관성·멱등성·추적성 | `progress_version` 충돌 감지, `Idempotency-Key` + `(trip_day_id, idempotency_key)` unique, 파생 전환 한 transaction, `progress_transitions.affected_items` 전후 기록, 서버 시각 기준 | PASS |
| IV. 외부 실패 격리 | 구간 계산은 transaction 밖·deadline 8초, 실패 시 ETA null(`정보 없음`)과 전환 성공 분리 | PASS |
| V. 보안·최소 데이터 | 인증·소유권·날짜 검증, 시작 위치 1점만 저장, 정밀 좌표 log 금지, 정확도·시각 서버 재검증 | PASS |

Phase 1 후에도 위반·예외 없음. 신규 migration과 계약 변경에는 contract/integration test와 ERD·api-spec 동기화가 필요하다.

## Project Structure

### Documentation (this feature)

```text
specs/006-trip-progress/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/progress.openapi.yaml
├── checklists/requirements.md
└── tasks.md                         # speckit-tasks 단계에서 생성
```

### Source Code (repository root)

```text
api/
├── migrations/versions/005_create_progress_tables.py
└── app/
    ├── api/v1/progress.py                 # PROG-001/002/006 router
    ├── models/progress.py                 # ProgressTransition, ProgressSegment (+ TripDay.progress_version)
    ├── schemas/progress.py                # 요청·응답 DTO
    └── services/
        ├── progress.py                    # 시작·전환·파생 규칙·당일 완료·멱등성
        ├── eta.py                         # ETA 재계산 (itinerary/route 저장 후에도 호출)
        └── route.py                       # 단일 구간 계산 함수 노출 (기존 provider 재사용)

android/app/src/main/java/com/gilpick/
├── progress/
│   ├── ProgressApi.kt                     # Retrofit + DTO
│   ├── ProgressRepository.kt
│   ├── ProgressUiState.kt
│   ├── ProgressViewModel.kt
│   ├── ActiveTravelScreen.kt              # header, NextPlaceCard, map, ProgressItemList
│   ├── StatusSheet.kt
│   ├── ProgressLabels.kt
│   ├── CurrentLocationProvider.kt         # play-services 1회 위치 + 유효성 필터
│   └── ProgressNavigation.kt              # ActiveTravelRoute(tripId), progressGraph(repository, map) 시드
├── trip/{TripDetailScreen,TripDetailViewModel}.kt   # 시작 버튼·권한·startToday
└── MainActivity.kt                        # progressGraph 연결
```

**Structure Decision**: 기존 모바일+API 구조와 F004·F005의 package·seam 방식(`itineraryGraph`/`routeGraph`의 repository·map 파라미터)을 그대로 따른다. ETA 계산은 `services/eta.py`로 분리해 `itinerary.save_day`와 `route` READY 전환 뒤에서도 호출한다.

## Implementation Design

### Backend

1. **migration 005**: `trip_days.progress_version`(int, default 0), `progress_transitions`(ERD 7.2 + `progress_version_after`, `idempotency_key`, unique), `progress_segments`.
2. **시작(PROG-002)**: 인증·소유권·날짜 검증 → 오늘(KST) 아니면 `409 DAY_NOT_TODAY`, 장소 0곳 `422 DAY_EMPTY` → 이미 시작이면 저장값 200 → 위치 유효성(100m·2분) 검사 → transaction: `IN_PROGRESS`, `actual_started_at=now`, `start_location`, `detection_active=true`, 첫 `PLANNED`→`EN_ROUTE`, transition `START`, `progress_version+1`, 커밋 → 유효 위치가 있으면 transaction 밖에서 도보 단일 구간 계산 → 별도 transaction에 `progress_segments` upsert + ETA 재계산 → PROG-001 형식으로 응답. 동시 시작은 `trip_days` row lock(`SELECT ... FOR UPDATE`)으로 직렬화한다.
3. **전환(PROG-006)**: item → day → trip 소유권 검증 → `NOT_STARTED`면 `409 DAY_NOT_STARTED` → `Idempotency-Key` 기존 기록이면 최초 결과 200 → `progress_version` 비교 → research 결정 3 표로 허용 여부·파생 변경 계산 → 한 transaction에 항목 상태·실제 시각·당일 상태·transition·`progress_version+1` 적용 → 건너뛰기로 새 인접 쌍이 생기고 `progress_segments`에 없으면 transaction 밖 단일 구간 계산 후 저장 → ETA 재계산 → 응답.
4. **ETA 재계산(`services/eta.py`)**: data-model.md 규칙. 입력은 day·items·활성 route payload·progress_segments. `itinerary.save_day`(진행 중 날짜)와 `route` READY 확정 뒤에도 호출한다. F004 저장으로 인접 쌍이 바뀌어 남는 `progress_segments` 행은 무해하므로 정리하지 않는다.
5. **PROG-001**: day·items·활성 route·segments를 읽어 `inboundTravel`(`PLANNED_ROUTE`/`COMPUTED`/null)을 파생한다.
6. **오류 code**: `DAY_NOT_TODAY`, `DAY_EMPTY`, `DAY_NOT_STARTED`, `INVALID_STATUS_TRANSITION`, 기존 `VERSION_CONFLICT`·`TRIP_FORBIDDEN`·`TRIP_NOT_FOUND`·`ITINERARY_ITEM_NOT_FOUND`. log에는 request ID, trip/day/item, transition_type, 전후 상태, provider 결과 code만 남긴다.

### Android

1. **`ProgressRepository`**: `getDayProgress`, `startDay(location?)`, `updateStatus(itemId, status, version)`; `Idempotency-Key`는 요청 내용(대상·목표 상태·`progressVersion`)에서 파생한 UUID(`UUID.nameUUIDFromBytes`)라 `다시 시도`는 자동으로 같은 key를 재사용하고, 전환이 적용돼 version이 바뀐 다음 요청은 새 key가 된다(호출자가 key를 보관하지 않음, 2026-09-07 T009 결정). 오류는 `ProgressError`로 정규화(F005 `RouteError` 방식).
2. **`TripDetailViewModel.startToday()`**: 오늘 날짜 판정(기기 KST) → `CurrentLocationProvider.current()`(권한·timeout·유효성 포함, 실패 시 null) → `startDay` → 성공 시 navigation event. 버튼 상태: 기간 밖 비활성+안내, 장소 0곳 `장소 추가` 안내, 시작됨 `여행 진행 화면으로`(PROG-001 `dayStatus`로 판정, TripDetail 진입 시 오늘 날짜만 조회).
3. **`ProgressViewModel`**: overview(F004) + progress(오늘) 병렬 조회 → `Content`. 행동은 목표 상태로 매핑(`도착했어요`/`도착으로 변경`→`ARRIVED`, `다음 장소로 출발`→`COMPLETED`, `건너뛰기`→`SKIPPED`, `완료 취소`→`ARRIVED`, `건너뛰기 취소`→`PLANNED`). 응답의 progress로 상태 교체. `409 VERSION_CONFLICT`는 재조회 후 안내.
4. **`ActiveTravelScreen`**: `viewingDate` 전환·`오늘로 돌아가기`; 오늘만 `NextPlaceCard`(EN_ROUTE/ARRIVED/완료 세 모드)·행동; 목록 행 탭 → `StatusSheet`; `장소 추가` → `ItineraryEditRoute(date=today, openSearch=true)`; `경로 보기` → `DayRouteRoute`.
5. **`RouteMap`·`DayRouteScreen` 확장**: 시작 위치 marker(있을 때)와 처리 상태별 marker 구분(색+숫자/체크)은 F005 marker 모델에 상태 필드를 더하는 최소 변경으로 한다. 시작된 날짜의 F005 경로 화면은 PROG-001을 함께 조회해 marker·구간 목록에 상태를 문구+아이콘으로 표시한다(F005 UI-010이 F006에 남긴 자리).

## Complexity Tracking

Constitution 위반이 없어 별도 정당화 항목은 없다.
