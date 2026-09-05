# Data Model: 일정 구성

저장 구조는 `docs/design/er-schema.md` 5.2~5.4를 따른다. 이 문서는 F004가 쓰는 필드, 검증 규칙, 상태와 UI state만 적는다. 컬럼 전체 정의는 ERD를 참조한다.

## 1. `TripDay` (`trip_days`)

| 필드 | 형식 | 규칙 |
|---|---|---|
| `trip_day_id` | uuid | PK |
| `trip_id` | uuid | FK → `trips`, `UNIQUE(trip_id, visit_date)` |
| `visit_date` | date | 여행 기간 안(`trips.start_date`~`end_date`) |
| `day_number` | smallint | `visit_date - start_date + 1`, `UNIQUE(trip_id, day_number)` |
| `status` | enum | F004는 항상 `NOT_STARTED`로 생성. 전환은 F006 |
| `schedule_version` | integer | 생성 시 1, 저장이 상태를 바꿀 때마다 +1 |

- 첫 저장(`version: 0`) 때 생성한다. 조회만으로는 만들지 않는다.
- 여행 기간 축소로 범위 밖이 되면 항목과 함께 삭제한다(F002 PATCH 확인 후).
- F006 컬럼(`actual_started_at` 등)은 migration에 포함하되 F004는 읽거나 쓰지 않는다.

## 2. `Place` (`places`)

| 필드 | 형식 | 규칙 |
|---|---|---|
| `place_id` | uuid | PK, 서버 내부용. API에는 노출하지 않는다 |
| `tour_content_id` | varchar(255), null | `tourapi:{id}`의 `{id}`. partial unique |
| `google_place_id` | varchar(255), null | `google:{id}`의 `{id}` 또는 TourAPI 장소의 확정 매칭 Google 식별자. partial unique |
| `name` | varchar(255) | F003 `name` |
| `category` | varchar(30) | F003 `category` 6개 enum |
| `tour_category_1~3` | varchar(120), null | F003 `tourApiCategory.large/middle/small` |
| `address` | text, null | F003 `address` |
| `location` | geography(Point) | F003 `latitude`·`longitude`. 둘 다 없으면 저장 거부(`422 INVALID_ITINERARY`) |
| `image_url` | text, null | F003 `imageUrl` |

- provider 식별자로 upsert한다. 이미 있으면 `name`·`address`·`image_url`·`tour_category_*`만 갱신하고 `place_id`는 유지한다.
- 평점·리뷰 수·영업시간·provider 원문은 저장하지 않는다.

## 3. `ItineraryItem` (`itinerary_items`)

| 필드 | 형식 | 규칙 |
|---|---|---|
| `item_id` | uuid | PK. 새 항목은 `uuid5(trip_day_id, "{Idempotency-Key}:{sequence}")` |
| `trip_day_id` | uuid | FK → `trip_days` |
| `place_id` | uuid | FK → `places` |
| `sequence` | smallint | 1부터 연속, `UNIQUE(trip_day_id, sequence)` |
| `status` | enum | `PLANNED`(F004 기본), `EN_ROUTE`, `ARRIVED`, `COMPLETED`, `SKIPPED`(F006) |
| `planned_stay_minutes` | smallint | 30~360, 30의 배수 |
| `stay_source` | enum | `RECOMMENDED`(추천값 그대로), `USER_ADJUSTED`(사용자가 바꿈) |
| `transport_mode_to_next` | enum, null | `WALK`, `TRANSIT`, `CAR`. 마지막 항목은 null, 그 외는 필수 |

**저장 검증 순서** (`422 INVALID_ITINERARY`, `details.violations[]`):

1. 항목 수 ≤ 10
2. `sequence`가 1..N 연속·중복 없음
3. `plannedStayMinutes` 30~360, `% 30 == 0`
4. 마지막 항목 `transportModeToNext == null`, 나머지는 non-null
5. 새 항목(`itemId == null`)에 `place` 스냅샷 존재, 좌표 존재
6. 기존 `itemId`가 같은 `trip_day`에 속함

**처리된 항목 잠금** (`409 ITINERARY_ITEM_LOCKED`): 저장된 `status != PLANNED`인 항목은 `place_id`·`transport_mode_to_next` 변경과 요청에서의 누락(삭제)을 거부한다. 요청의 `status`는 무시하고 저장값을 유지한다.

## 4. 저장 결과 판정

- 요청이 만드는 항목 집합(`item_id`, `place_id`, `sequence`, `planned_stay_minutes`, `stay_source`, `transport_mode_to_next`)이 현재 저장값과 완전히 같으면 no-op: 버전을 올리지 않고 `200`.
- 다르면 `schedule_version`을 조건부 `UPDATE`로 +1 하고 diff를 적용한다. 조건부 갱신이 0행이면 `409 VERSION_CONFLICT`.

## 5. 여행 기간 축소 영향

`update_trip`이 새 기간 밖 `trip_days`의 항목 수를 `deletedItemCount`로 계산한다. 0이면 확인 없이 진행, 1 이상이면 `confirmDeleteOutOfRangeItems=true`일 때만 해당 `trip_days`를 삭제(항목 cascade)하고 `trips`를 갱신한다. 삭제된 날짜 수와 항목 수는 처리 시각·여행 버전 전후와 함께 log에 남긴다.

## 6. UI State (Android)

### `ItineraryEditUiState`

| 필드 | 설명 |
|---|---|
| `tripId`, `dates` | 여행 기간의 날짜 목록(탭) |
| `selectedDate` | 편집 중인 날짜 |
| `savedVersion` | 마지막 조회·저장 버전 |
| `draft: List<DraftItem>` | 화면 편집 상태. `SavedStateHandle`에 보존 |
| `phase` | `Loading`(1초 규칙), `Empty`, `Content`, `Failed(error)` |
| `saving` | 저장 중 `저장` 비활성 |
| `dirty` | `draft != saved` → 닫기 확인 필요 |
| `dialog` | `None`, `Stay(itemIndex)`, `Transport(itemIndex)`, `DiscardConfirm`, `Limit` |

`DraftItem`: `itemId?`, `placeId`, `placeSnapshot`(F003 `PlaceDto` 최소 필드), `stayMinutes`, `staySource`, `transportToNext?`, `status`.

동작: `addFromSearch(place, request)`는 `draft` 끝에 `PLANNED` 항목을 붙이고 직전 항목의 `transportToNext`에 `request.transport`를 넣는다(첫 항목이면 무시). `moveUp/moveDown/remove/setStay/setTransport`는 `draft`만 바꾸고, `save()`가 `sequence`를 1..N으로 매겨 PUT한다. 409는 `savedVersion`을 최신으로 바꿔 최대 2회 재시도한다.

### `TripDetailUiState` 확장

F002 `TripDetailUiState`에 `itinerary: ItineraryOverview`(`Loading`/`Content(days)`/`Failed`)를 더한다. 여행 기본 정보 조회와 독립적으로 실패한다(US3 시나리오 4).

## 7. 상태 전이

```text
TripDay:  (없음) --첫 PUT(version 0)--> NOT_STARTED(v1) --PUT--> NOT_STARTED(v+1)   # IN_PROGRESS/COMPLETED는 F006
Item:     PLANNED --F004 편집--> PLANNED                                             # 나머지 전환은 F006
```
