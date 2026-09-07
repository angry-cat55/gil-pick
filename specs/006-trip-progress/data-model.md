# Data Model: F006 여행 진행

migration `005_create_progress_tables`가 아래 변경을 담는다. F004 migration 003이 만든 컬럼은 그대로 쓴다.

## TripDay (기존 `trip_days` 확장)

| 필드 | 형식 | F006 규칙 |
|---|---|---|
| `status` | enum | `NOT_STARTED` → 시작 시 `IN_PROGRESS` → 남은 장소 없음 시 `COMPLETED`, 상태 수정으로 남은 장소 생기면 `IN_PROGRESS` 복귀 |
| `actual_started_at` | timestamptz | 시작 요청 최초 서버 수신 시각. 한 번 저장 후 불변 |
| `start_location` | geography(Point) nullable | 유효한 현재 위치가 있을 때만 저장. 첫 ETA 기준점 |
| `detection_active` | boolean | 시작 시 true, 완료 시 false, 복귀 시 true (F008 대상 선별용) |
| `completed_at` | timestamptz nullable | 당일 완료 시각. 복귀 시 null |
| `progress_version` | integer **신규** | 기본 0. 시작·모든 전환마다 +1. 요청의 `progressVersion`과 다르면 `409 VERSION_CONFLICT` |

## ItineraryItem (기존 `itinerary_items`, 컬럼 추가 없음)

| 필드 | F006 규칙 |
|---|---|
| `status` | `PLANNED`·`EN_ROUTE`·`ARRIVED`·`COMPLETED`·`SKIPPED`. 한 날짜에 `EN_ROUTE` 최대 1, `ARRIVED` 최대 1 |
| `estimated_arrival_at` | 남은 장소(`PLANNED`·`EN_ROUTE`)의 ETA. 계산 불가면 null. `COMPLETED`·`SKIPPED`는 갱신하지 않음 |
| `estimated_departure_at` | `estimated_arrival_at + planned_stay_minutes`. ETA null이면 null |
| `actual_arrived_at` | `ARRIVED` 전환 서버 시각. 상태 수정으로 `PLANNED`로 돌아가면 null |
| `actual_departed_at` | `COMPLETED` 전환 서버 시각 |
| `completed_at` | `actual_departed_at`과 같은 시각. 완료 취소 시 null |

## ProgressTransition (`progress_transitions` **신규**, ERD 7.2 기준)

| 필드 | 형식 | F006 규칙 |
|---|---|---|
| `transition_id` | uuid | PK |
| `trip_day_id` | uuid | FK `trip_days` cascade |
| `primary_item_id` | uuid nullable | 사용자가 지목한 항목. 시작 전환은 null |
| `trigger_event_id` | uuid nullable | F006은 항상 null (F007 위치 이벤트용) |
| `transition_type` | varchar(30) | `START`, `ARRIVE`, `DEPART`, `SKIP`, `UNDO_COMPLETE`, `UNDO_SKIP` |
| `status` | varchar(30) | F006은 항상 `CONFIRMED` (F007이 `PENDING_CONFIRMATION`·`AUTO_CONFIRMED`·`CANCELLED`·`UNDONE` 추가) |
| `source` | varchar(30) | F006은 항상 `MANUAL` |
| `decision`, `auto_finalize_at`, `undo_deadline`, `cancelled_at`, `undone_at` | nullable | F006 미사용 (F007) |
| `affected_items` | jsonb | `[{itemId, beforeStatus, afterStatus}]` 파생 변경 전부 포함. 당일 상태가 바뀌면 `{dayStatusBefore, dayStatusAfter}` 포함 |
| `detected_at`, `confirmed_at` | timestamptz | 수동 처리 서버 시각 (둘 다 같은 값) |
| `schedule_version_before`, `schedule_version_after` | integer | 전환 당시 일정 version 스냅샷 (F006은 같은 값) |
| `progress_version_after` | integer **ERD 추가** | 전환 후 `progress_version` |
| `idempotency_key` | uuid **ERD 추가** | 요청 `Idempotency-Key`. `UNIQUE(trip_day_id, idempotency_key)` |
| `created_at` | timestamptz | 서버 시각 |

## ProgressSegment (`progress_segments` **신규**)

계획 경로에 없는 구간의 이동시간. 한 날짜에 `(from_item_id, to_item_id)`당 최신 1행.

| 필드 | 형식 | 규칙 |
|---|---|---|
| `progress_segment_id` | uuid | PK |
| `trip_day_id` | uuid | FK cascade |
| `from_item_id` | uuid nullable | null = 시작 위치(`start_location`) |
| `to_item_id` | uuid | FK `itinerary_items` cascade |
| `transport_mode` | enum | `WALK`(시작 구간 고정) 또는 이전 장소 `transport_mode_to_next` |
| `provider` | enum | `TMAP`·`ODSAY` |
| `duration_seconds`, `distance_meters` | integer | 0 이상 |
| `computed_at` | timestamptz | 계산 시각 |

`UNIQUE(trip_day_id, from_item_id, to_item_id)` (from null 포함은 partial unique). 계산 실패 시 행을 만들지 않는다.

## ETA 계산 규칙 (서버)

```text
remaining = 순서대로 status ∈ {PLANNED, EN_ROUTE, ARRIVED} 인 항목 (SKIPPED·COMPLETED 제외)
prev = 직전 처리 항목: 가장 최근 COMPLETED(actual_departed_at) 또는 없음
for item in remaining:
  base =
    prev 없음                      → actual_started_at
    prev.status == COMPLETED       → prev.actual_departed_at
    prev.status == ARRIVED         → prev.actual_arrived_at + prev.stay
    else                           → prev.estimated_arrival_at + prev.stay (null이면 null)
  inbound =
    (prev, item)이 활성 경로 인접 구간 → route segment duration
    else progress_segments[(prev?.id, item.id)] → duration
    else null
  item.eta = base + inbound (둘 중 하나라도 null이면 null)
  item.estimated_departure_at = eta + stay
  prev = item
```

- `ARRIVED` 항목 자신의 ETA는 갱신하지 않는다(실제 도착 시각이 있음).
- 시작 시 `start_location`이 없으면 첫 항목 inbound = 0.
- F004 저장(장소 추가·순서 변경·체류 변경)과 F005 경로 갱신(READY 전환) 뒤에도 같은 함수를 호출한다. `progress_segments`의 `to_item_id`가 삭제되면 cascade로 사라진다.

## 상태 전이

```text
TripDay:  NOT_STARTED --start--> IN_PROGRESS --남은 장소 0--> COMPLETED --상태 수정으로 남은 장소 생김--> IN_PROGRESS
Item:     PLANNED → EN_ROUTE → ARRIVED → COMPLETED
          any → SKIPPED (건너뛰기), SKIPPED → PLANNED (취소), COMPLETED → ARRIVED (완료 취소)
          PLANNED/EN_ROUTE → ARRIVED (도착으로 변경, 도착했어요)
불변식:   EN_ROUTE ≤ 1, ARRIVED ≤ 1, 시작 전 날짜는 전부 PLANNED
```

파생 규칙 표는 `research.md` 결정 3을 따른다.

## 문서 동기화

- `docs/design/er-schema.md`: `trip_days.progress_version`, `progress_transitions.progress_version_after`·`idempotency_key`, `progress_segments` 절 추가.
- `docs/design/api-spec.md`: PROG-001·002·006 요청·응답을 `contracts/progress.openapi.yaml`과 맞춘다.
