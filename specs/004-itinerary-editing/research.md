# Phase 0 Research: 일정 구성

## 1. 저장 구조와 migration 범위

**Decision**: migration `003_create_itinerary_tables`가 `docs/design/er-schema.md` 5.2 `trip_days`, 5.3 `places`, 5.4 `itinerary_items`를 ERD의 전체 컬럼으로 한 번에 만든다. F006 전용 컬럼(`actual_started_at`, `start_location`, `detection_active`, `estimated_*`, `actual_*`, `auto_departure_suppressed`)은 nullable 또는 기본값으로 두고 F004는 쓰지 않는다.

**Rationale**: F002 research 2절이 "한 논리 테이블을 두 migration에 나누면 이력이 흩어진다"는 이유로 `trip_days`를 F004에 넘겼다. 같은 논리로 F004가 세 테이블을 ERD 그대로 만들면 F006은 컬럼 추가 없이 값만 채운다. `places`의 `location`은 PostGIS `geography(Point,4326)`이며 compose의 `postgis/postgis` 이미지로 이미 확장이 있다.

**Alternatives considered**:

- F004에 필요한 컬럼만 만들고 F006에서 `ALTER TABLE`: F002가 거부한 분할 migration을 되풀이한다.
- `places` 없이 `itinerary_items`에 장소 정보를 비정규화: ERD와 어긋나고 F009 대체 장소가 같은 장소 참조를 재사용할 수 없다.

## 2. 장소 참조 저장 방식

**Decision**: 일정 저장 요청의 각 항목은 provider 형식 `placeId`(`tourapi:{id}` 또는 `google:{id}`)를 쓴다. 신규 항목(`itemId == null`)은 서버의 기존 저장 여부와 무관하게 `place` 스냅샷(장소명, 내부 카테고리, nullable 원본 관광 분류·주소·대표 이미지, 위도·경도)을 항상 함께 보낸다. 서버는 `placeId`의 provider 식별자로 `places`를 upsert하고 내부 `place_id`를 항목에 연결한다. `google_place_id`는 `google:{id}`에서만 추출하며 F003 계약에 없는 TourAPI 장소의 Google 매칭 ID를 클라이언트에 요구하지 않는다. 응답 항목은 provider `placeId`와 표시용 `place` 요약(장소명, 카테고리, 주소, 대표 이미지)을 돌려준다.

**Rationale**: F003은 검색 결과를 저장하지 않으므로(F003 FR-014) 저장 시점에 서버가 장소 정보를 알 방법은 재조회 또는 클라이언트 스냅샷뿐이다. 재조회는 저장을 TourAPI·Google 가용성에 묶어 constitution IV(핵심 결과 보존)와 충돌한다. 스냅샷은 F003 `PlaceDto`에 이미 있는 값이라 Android 추가 호출이 없다. `places`의 partial unique(`tour_content_id`, `google_place_id`)가 중복 생성을 막는다.

**Alternatives considered**:

- 저장 시 서버가 PLACE-002로 재조회: 외부 장애가 일정 저장 실패로 번지고 저장 지연이 5초 이상 늘 수 있다.
- 내부 `place_id`(uuid)를 API에 노출: 클라이언트가 F003 결과와 일정 항목을 잇는 키가 두 개가 되어 상세 이동(F003 `PlaceDetailRoute(placeId)`)이 복잡해진다.

## 3. 저장 단위·버전·멱등 처리

**Decision**: 한 날짜의 항목 전체를 `PUT /trips/{tripId}/days/{date}/itinerary`로 저장한다. 버전은 `trip_days.schedule_version`이며, 아직 없는 날짜의 조회는 `version: 0`, 빈 항목, `routeStatus: NOT_CALCULATED`를 돌려주고 `version: 0`으로 저장하면 `trip_days` 행을 만들고 `201`을 반환한다. 저장은 하나의 transaction에서 (1) 소유권·날짜 범위 검증, (2) `schedule_version` 조건부 `UPDATE ... RETURNING`으로 선점, (3) `places` upsert, (4) 항목 diff 적용(기존 `itemId` 유지·갱신, 없는 항목 삭제, 새 항목 삽입)을 수행한다. 새 항목의 `item_id`는 `uuid5(trip_day_id, "{Idempotency-Key}:{sequence}")`로 정해 같은 요청의 재전송이 같은 행을 만든다. 요청이 만드는 결과 상태가 현재 저장 상태와 완전히 같으면 버전을 올리지 않고 현재 상태를 `200`으로 돌려준다.

**Rationale**: FR-008·FR-009는 통째 저장, 버전 충돌 감지, 재전송 시 중복·이중 증가 금지를 요구한다. F002가 `uuid5(user_id, key)`로 생성 멱등성을 잡은 방식을 항목 단위로 확장하고, "동일 결과 상태면 no-op" 규칙이 Q4의 자동 재저장(최신 버전으로 같은 내용 재전송)에서도 버전이 불필요하게 오르지 않게 한다. 별도 idempotency 저장 테이블은 ERD에 없어 추가하지 않는다.

**Alternatives considered**:

- 항목별 개별 API(POST/PATCH/DELETE item): 순서 재배치가 여러 요청으로 쪼개져 중간 상태가 노출되고 Figma의 `저장` 버튼 모델과 맞지 않는다.
- Idempotency-Key 저장 테이블: 스키마 추가 없이 uuid5 + no-op 판정으로 같은 보장을 얻는다.

## 4. 버전 충돌 시 앱 동작

**Decision**: 서버가 `409 VERSION_CONFLICT`를 주면 Android는 편집 초안을 유지한 채 최신 일정을 조회해 `version`만 바꿔 같은 초안을 다시 저장한다. 최대 2회 재시도 후 실패하면 저장 실패 안내와 재시도 버튼을 보여 준다. 재저장은 사용자에게 알리지 않는다(spec Clarifications 2026-09-05, 마지막 저장이 이김).

**Rationale**: spec Q4 결정. 위험(다른 기기 변경 덮어쓰기)은 spec Assumptions에 기록했다.

## 5. 경로 상태 `NOT_CALCULATED`

**Decision**: ITIN-001·002·003 응답의 `routeStatus` enum에 `NOT_CALCULATED`를 추가하고 F004는 항상 이 값과 `route: null`을 돌려준다. `routes` 테이블 행은 만들지 않는다. F005가 저장 후 경로 계산을 붙일 때 `READY`·`FAILED`를 채운다.

**Rationale**: spec Q1 결정. Android는 이 값에서 도착 시각·구간 소요 시간을 그리지 않는다.

## 6. 여행 상세용 일정 개요 조회

**Decision**: `GET /trips/{tripId}/itinerary`(ITIN-003 신설)가 여행의 모든 날짜를 `days[]`로 돌려준다. 일정이 없는 날짜도 `version: 0`, 빈 항목으로 포함해 앱이 여행 기간과 대조하지 않게 한다. 상한은 7일 × 10곳이라 페이징하지 않는다.

**Rationale**: 여행 상세(FR-014)는 날짜별 목록을 한 화면에 그린다. 날짜마다 ITIN-001을 부르면 최대 7회 순차 호출이 되어 SC-002(3초)를 위협한다.

**Alternatives considered**: F002 `GET /trips/{tripId}` 응답에 일정을 포함: F002 계약과 화면 소유권을 건드리고 목록·상세 공용 DTO가 커진다.

## 7. 여행 기간 축소와 일정 삭제

**Decision**: F002 `PATCH /trips/{tripId}`의 `update_trip`이 새 기간 밖 `trip_days`에 속한 `itinerary_items` 수를 세어 `409 CONFIRMATION_REQUIRED`의 `deletedItemCount`로 돌려주고, `confirmDeleteOutOfRangeItems=true`이면 같은 transaction에서 해당 `trip_days`(항목 cascade)를 삭제한다. 기간 밖 항목이 0건이면 확인 없이 진행한다(현재의 "항상 409" 동작 수정). Android `TripFormViewModel`·`TripFormScreen`은 409를 받으면 `삭제될 장소 N곳` 대화상자를 띄우고 동의 시 `confirmDeleteOutOfRangeItems=true`로 재요청한다.

**Rationale**: spec Q1 결정(F004 범위). #178 검증에서 앱에 동의 UI가 없어 축소 저장이 불가능했던 문제를 함께 닫는다. F002 파일을 수정하므로 F002 담당자(hs) review를 받는다.

## 8. 처리된 장소 편집 제한

**Decision**: 저장 시 기존 항목의 `status`가 `PLANNED`가 아니면 `place_id`·`transport_mode_to_next` 값 변경과 삭제를 `409 ITINERARY_ITEM_LOCKED`(details에 `itemId`)로 거부하고, `planned_stay_minutes`·`sequence` 변경만 허용한다. 순서 변경으로 다음 장소가 달라져도 기존 `transport_mode_to_next` enum 값은 해당 항목에 유지하며 F005가 변경된 순서로 경로를 다시 계산한다. 요청의 `status`는 무시하고 저장된 값을 유지한다. Android 편집 화면은 `status`가 `COMPLETED`·`SKIPPED`·`ARRIVED`·`EN_ROUTE`인 행에 상태 표시를 하고 삭제·`변경` 행동을 숨긴다. 검증은 fixture로 처리된 항목을 만들어 수행한다.

**Rationale**: spec Q3 결정. F006이 상태를 바꾸기 전에도 규칙이 계약에 고정된다.

## 9. Android 순서 변경 조작

**Decision**: 행별 위·아래 이동 버튼을 먼저 구현하고, 손잡이 끌기는 Compose foundation의 `detectDragGesturesAfterLongPress`와 `LazyListState`로 직접 구현한다. 새 라이브러리는 추가하지 않는다. 끌기가 검증 기준(48dp·360dp·글자 확대)을 만족하지 못하면 버튼만 남기고 차이를 PR에 기록한다.

**Rationale**: spec Q3(UI-008) 결정은 두 조작 병행이다. 버튼만으로 FR-005를 만족하므로 끌기는 보강이며, 저장소 규칙상 몇 줄로 되는 일에 의존성을 더하지 않는다.

**Alternatives considered**: `sh.calvin.reorderable` 등 외부 라이브러리: 버전 고정과 Compose BOM 호환 검증 비용이 있고 버튼 대안이 필수라 이득이 작다.

## 10. Android 화면 진입과 결과 전달

**Decision**: type-safe route `ItineraryEditRoute(tripId, date, openSearch: Boolean = false)`를 두고, 여행 상세의 `일정 편집`은 첫 날짜로, 날짜 헤더의 `장소 추가`는 그 날짜로 `openSearch=true`를 넘겨 진입 직후 F003 `PlaceSearchRoute`로 이동한다. F003 `placeGraph`의 `onAddToSchedule` 콜백은 `(PlaceDto, AddToScheduleRequest)`를 이전 back stack entry의 `SavedStateHandle`에 결과로 넣고 검색·상세를 pop한다. 편집 ViewModel은 그 결과를 초안 끝에 붙인다. 초안은 `SavedStateHandle`에 두어 회전·프로세스 종료 뒤에도 유지한다.

**Rationale**: F003 T035가 진입점 연결을 F004로 넘겼고, `AddToScheduleRequest`는 이미 이동 수단·체류 시간을 담고 있다. Navigation Compose 2.10 표준 결과 전달 방식이라 새 의존성이 없다.

## 11. 검증 오류 표현

**Decision**: 형식 오류(누락 필드, enum 밖 값)는 `400 INVALID_REQUEST`, 규칙 위반(순서 빈틈·중복, 체류 시간 단위·범위, 마지막 장소 이동 수단, 11곳 이상, 기간 밖 날짜)은 `422 INVALID_ITINERARY`에 `details.violations[]`(field, itemIndex, reason)로 돌려준다. 소유권 위반 `403 TRIP_FORBIDDEN`, 없는 여행 `404 TRIP_NOT_FOUND`은 F002 코드를 재사용한다.

**Rationale**: api-spec ITIN-002가 `400`·`422`를 구분해 두었고, Android가 어떤 값이 잘못됐는지 안내(spec Edge Case)하려면 항목 index가 필요하다.

## 12. Backend 담당자 확인 항목

- migration 003이 세 테이블을 한 번에 만드는 범위(1절)와 PostGIS `geography` 컬럼 사용.
- `update_trip`의 `deletedItemCount` 실제 계산과 0건일 때 확인 생략으로의 동작 변경(7절). 기존 F002 contract test 수정 필요.
- `uuid5` 기반 `item_id`와 no-op 판정(3절)이 F002 생성 멱등 규칙과 일관되는지.
