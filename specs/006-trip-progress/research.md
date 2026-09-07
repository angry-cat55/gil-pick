# Phase 0 Research: F006 여행 진행

## 결정 1: 도착 예정 시각은 서버가 전환 시점에만 계산·저장한다

**Decision**: ETA는 서버가 `itinerary_items.estimated_arrival_at`·`estimated_departure_at`에 저장하고, 시작·도착·출발·건너뛰기·상태 수정·F004 저장·F005 경로 갱신 시점에만 다시 계산한다. 시간 경과에 따른 주기 재계산은 없다. 앱은 저장된 ETA와 현재 시각으로 `N분 남았어요`/`N분 지났어요`만 계산한다.

**Rationale**: F008 변수 감지가 서버에서 ETA를 입력으로 쓰므로 계산 주체가 서버여야 한다. 전환 시점 계산만으로 spec FR-004~FR-015를 만족하고, ERD의 기존 컬럼을 그대로 쓴다.

**Alternatives considered**: 앱 계산은 F008과 값이 어긋난다. 주기 재계산은 spec Assumptions("시간이 흐른다는 이유만으로 재계산하지 않는다")에 어긋난다.

## 결정 2: 진행 동시성 version은 `schedule_version`과 분리한다

**Decision**: `trip_days.progress_version`(integer, 기본 0)을 추가하고 시작·상태 전환 요청은 `progressVersion`으로 충돌을 감지한다. `schedule_version`은 F004 일정 저장에서만 증가한다.

**Rationale**: F005 활성 경로는 `(trip_day_id, schedule_version)` unique이고 활성 경로 version이 현재 일정 version과 같아야 한다. 진행 전환이 `schedule_version`을 올리면 정상 경로가 `HISTORICAL`로 밀리거나 F005 불변식이 깨진다. ERD `progress_transitions.schedule_version_before/after`는 전환 당시 일정 version 스냅샷으로 유지한다.

**Alternatives considered**: `schedule_version` 공용 사용은 위 충돌 때문에 제외. version 없이 상태 검사만 하는 방식은 constitution III(동시성 제어)에 어긋난다.

## 결정 3: 수동 전환은 PROG-006 하나로 처리하고 서버가 파생 전환을 계산한다

**Decision**: `PATCH /itinerary-items/{itemId}/status`에 목표 상태(`ARRIVED`·`COMPLETED`·`SKIPPED`·`PLANNED`)와 `progressVersion`, `Idempotency-Key`를 보낸다. 서버는 목표 상태에 따라 파생 변경(다음 장소 `EN_ROUTE`, 이후 장소 `PLANNED` 초기화, 기존 `ARRIVED`의 자동 `COMPLETED`, 당일 완료·복귀)을 한 transaction으로 적용하고 `affected_items`에 기록한다.

| 사용자 행동 | 요청 status | 서버 파생 규칙 |
|---|---|---|
| 도착했어요 / 도착으로 변경 | `ARRIVED` | 앞 `ARRIVED`→`COMPLETED`, 앞 `EN_ROUTE`→`PLANNED`, 뒤 전부 `PLANNED`, 남은 장소 없으면 당일 완료 |
| 다음 장소로 출발 | `COMPLETED` | 대상은 `ARRIVED`여야 함. 다음 `PLANNED`→`EN_ROUTE` |
| 건너뛰기 | `SKIPPED` | 대상이 `EN_ROUTE`였으면 다음 `PLANNED`→`EN_ROUTE`. 남은 장소 없으면 당일 완료 |
| 완료 취소 | `ARRIVED` (대상 `COMPLETED`) | 뒤 전부 `PLANNED`, 당일 완료였다면 `IN_PROGRESS` 복귀 |
| 건너뛰기 취소 | `PLANNED` (대상 `SKIPPED`) | `EN_ROUTE`·`ARRIVED`가 없으면 순서상 첫 `PLANNED`→`EN_ROUTE`, 당일 완료였다면 복귀 |

허용되지 않는 조합은 `422 INVALID_STATUS_TRANSITION`. 모든 파생 변경 뒤 ETA를 다시 계산하고 `progress_version`을 1 올린다.

**Rationale**: api-spec PROG-006 계약을 확장 없이 쓰고, 앱이 파생 규칙을 알 필요가 없어 FE·BE 규칙이 한 곳(서버)에만 있다.

**Alternatives considered**: 행동별 endpoint(`/arrive`, `/depart`, `/skip`)는 계약 수가 늘고 F007 자동 전환과 규칙이 중복된다.

## 결정 4: 멱등성은 `progress_transitions.idempotency_key`로 보장한다

**Decision**: 시작(PROG-002)과 상태 전환(PROG-006)은 `Idempotency-Key` header를 필수로 받고, `(trip_day_id, idempotency_key)` unique로 같은 요청의 재전송에 최초 결과를 돌려준다. 시작 재요청은 key가 달라도 이미 `IN_PROGRESS`면 저장된 시작 시각을 그대로 200으로 돌려준다(FR-002).

**Rationale**: F002 여행 생성과 같은 방식이며 별도 멱등 table이 필요 없다. 전환 기록이 곧 멱등 저장소다.

**Alternatives considered**: version만으로는 재전송이 `409`가 되어 FR-017의 "같은 결과 반환"을 만족하지 못한다.

## 결정 5: 계획에 없는 구간은 `progress_segments`에 저장하고 기존 provider를 재사용한다

**Decision**: `현재 위치→첫 장소`(도보)와 건너뛰기로 이어진 `이전→다음 장소`(이전 장소의 `transport_mode_to_next`) 구간은 F005 `RouteCalculationService`의 provider(TMAP/ODsay)를 단일 구간으로 호출해 `progress_segments`에 저장한다. ETA 재계산은 인접 쌍이 F005 활성 경로 구간이면 그 값을, 아니면 `progress_segments`의 값을 쓰고, 둘 다 없으면 ETA를 null(`정보 없음`)로 둔다. 계산은 transaction 밖에서 하고 실패해도 상태 전환은 성공으로 커밋한다(spec Edge Case).

**Rationale**: clarify 결정(A안)을 그대로 구현한다. 저장해 두면 되돌리기·체류 시간 변경 뒤 재계산에서 provider를 다시 호출하지 않는다. 지도 형상은 진행 화면에 필요 없으므로 이동시간·거리·수단만 저장한다.

**Alternatives considered**: 남은 경로 전체 재계산(`/route/recalculate`)은 clarify에서 제외했다. 구간 값을 transition에만 두면 재계산 시 재조회가 복잡하다.

## 결정 6: 당일 완료 판정과 F008 준비 값

**Decision**: 전환 후 `PLANNED`·`EN_ROUTE` 장소가 하나도 없으면 `trip_days.status=COMPLETED`, `completed_at`, `detection_active=false`. 시작 시 `status=IN_PROGRESS`, `detection_active=true`. 완료 상태에서 상태 수정으로 남은 장소가 생기면 `IN_PROGRESS`, `completed_at=null`, `detection_active=true`로 복귀한다.

**Rationale**: spec FR-013·FR-014. `detection_active`는 ERD에 이미 있고 F008이 대상 날짜를 고르는 기준이므로 값만 맞춰 둔다.

**Alternatives considered**: 자정 자동 완료는 spec Assumptions에서 제외했다.

## 결정 7: Android 현재 위치는 Play Services 위치 API 1회 호출

**Decision**: `play-services-location`을 추가하고 `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY)`을 시작 버튼 탭 시 1회 호출한다. 권한(`ACCESS_FINE_LOCATION`, 기본값으로 `ACCESS_COARSE_LOCATION`)이 없으면 `rememberLauncherForActivityResult`로 앱 사용 중 권한을 요청하고, 거부·실패·10초 timeout이면 위치 없이 시작한다. 정확도 100m 초과·2분 경과 위치는 앱에서 보내지 않고 서버도 같은 기준으로 다시 검증한다(LOC-01).

**Rationale**: `LocationManager.getCurrentLocation`은 API 30+라 minSdk 26에서 못 쓴다. F007 지오펜스도 Play Services 위치 API가 필요하므로 의존성 추가는 한 번이다.

**Alternatives considered**: `LocationManager.requestSingleUpdate`(deprecated)와 `getLastKnownLocation`(오래된 위치)은 유효성 기준을 맞추기 어렵다.

## 결정 8: Android 진행 화면 구조

**Decision**: 새 `com.gilpick.progress` package에 `ProgressApi`·`ProgressRepository`·`ProgressViewModel`·`ActiveTravelScreen`·`StatusSheet`·`ProgressNavigation`·`CurrentLocationProvider`를 둔다. 화면 데이터는 F004 여행 일정 overview(ITIN-003, 모든 날짜의 장소명·체류·이동수단)와 PROG-001(오늘 날짜의 상태·ETA·실제 시각·진입 구간)을 합쳐 만든다. 지도는 F005 `RouteMap`을 marker/path 데이터만 바꿔 재사용하고, `경로 보기`는 `DayRouteRoute`로 이동한다. `TripDetailScreen`의 `오늘 여행 시작` 버튼과 `TripDetailViewModel`에 시작 흐름을 붙이고 시작 뒤 `ActiveTravelRoute(tripId)`로 이동한다.

**Rationale**: 기존 F004·F005 화면·repository·navigation 구조(`itineraryGraph`/`routeGraph`의 repository·map 시드)를 그대로 따른다. PROG-001에 장소명을 넣지 않아 F004 계약을 유일한 장소 정보 원천으로 유지한다.

**Alternatives considered**: PROG-001에 전체 일정을 중복 포함하면 두 계약이 같은 데이터를 다르게 표현하게 된다.

## 확인 근거와 한계

- 저장소 확정 사실: `trip_days`에 `status`·`actual_started_at`·`start_location`·`detection_active`·`completed_at`, `itinerary_items`에 `status`·`estimated_*`·`actual_*`·`completed_at`이 migration 003으로 이미 존재한다. `progress_transitions`·`progress_events` table은 아직 없다. F004 저장은 `status != PLANNED` 항목의 장소·순서·이동수단 변경을 `409 ITINERARY_ITEM_LOCKED`로 거부한다. F005 `RouteCalculationService`는 `TransportMode`별 provider를 보유한다.
- 문서 동기화 필요: `docs/design/er-schema.md`(`progress_version`, `progress_segments`, `idempotency_key`), `docs/design/api-spec.md`(PROG-001 응답 확장, PROG-002 요청에 `currentLocation`·`progressVersion`, PROG-006 `progressVersion`·`Idempotency-Key`·응답 확장). 같은 docs PR에서 반영한다.
- 구현 전 재검증: 단일 구간 provider 호출의 timeout은 F005와 같은 시도당 5초·1회 재시도를 쓰되 시작 응답 SC-001(10초)을 넘지 않게 전체 deadline 8초로 둔다. 실제 값은 구현 test에서 조정한다.
