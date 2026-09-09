# F010 일정 변경 데이터 모델

`spec.md`의 Key Entities와 `research.md`의 결정을 저장소의 실제 구조에 맞춘 것이다.

## 1. 신규 테이블

### 1.1 `route_previews` (변경 경로 미리보기)

승인 전까지 실제 일정과 무관한 계산 결과다. 승인 transaction이 외부 호출 없이 끝나도록 계산된 경로를 여기에 담아 둔다(`research.md` 2절).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `preview_id` | uuid PK | |
| `detection_id` | uuid FK → `detections` (CASCADE) | 이 변경을 촉발한 감지 결과 |
| `trip_day_id` | uuid FK → `trip_days` (CASCADE) | 조회·잠금 편의를 위한 비정규화 |
| `item_id` | uuid FK → `itinerary_items` (CASCADE) | 바꿀 대상 항목 |
| `original_place_id` | uuid FK → `places` | 만든 시점의 기존 장소 |
| `alternative_place_id` | uuid FK → `places` | 바꿀 대체 장소 |
| `schedule_version` | int | 만든 시점의 `trip_days.schedule_version` |
| `route_payload` | jsonb | 계산한 대체 경로. `routes.route_payload`와 같은 형식 |
| `total_duration_seconds` | int | 대체 경로 전체 이동 시간 |
| `total_distance_meters` | int | 대체 경로 전체 이동 거리 |
| `provider` | varchar(20) | 경로 제공자. `routes.provider`와 같음 |
| `comparison` | jsonb | 비교 항목 네 쌍(2.1) |
| `status` | varchar(20) | `PENDING` \| `APPROVED` \| `REJECTED` \| `SUPERSEDED` |
| `expires_at` | timestamptz | 만든 시각 + `PREVIEW_TTL_MINUTES`(5분) |
| `created_at` | timestamptz | |

**제약과 인덱스**

- `CHECK status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED')`
- `uq_route_previews_pending_detection`: `detection_id`에 대한 `status = 'PENDING'` 부분 unique.
  한 감지 결과에 승인 가능한 미리보기는 하나뿐이다(FR-006). 새로 만들 때 기존 `PENDING`을 `SUPERSEDED`로 내린 뒤 삽입한다.
- `ix_route_previews_detection`: `(detection_id, created_at DESC)`

**상태 전이**

```
PENDING ──승인(REPL-002)──> APPROVED
   │
   ├──거절(REPL-003)──> REJECTED
   └──같은 감지 결과에 새 미리보기──> SUPERSEDED
```

`expires_at`이 지난 `PENDING`은 상태를 바꾸지 않는다. 만료 판정은 조회·승인 시 서버 시각으로 한다. 상태를 바꾸는 배치를 두지 않는 이유는 만료가 시간 비교만으로 결정되고, 지연 확정 작업을 두지 않는 F007 방식(`research.md` 2절)과 같은 판단이다.

**대체 장소가 `places`에 없을 때**: F004 `ItineraryService._upsert_places`와 같은 방식으로 미리보기 생성 시점에 저장한다. 미리보기 생성이 `places`를 추가하는 것은 일정 변경이 아니므로 FR-001의 "일정을 바꾸지 않는다"에 어긋나지 않는다.

### 1.2 `place_replacements` (장소 변경 이력)

승인으로 실제 일정이 바뀐 사실 하나다. 되돌리기 판정의 근거이기도 하다(`research.md` 4절).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `replacement_id` | uuid PK | |
| `preview_id` | uuid FK → `route_previews` | 어떤 미리보기를 승인했는지 |
| `detection_id` | uuid FK → `detections` | 어떤 감지 결과로 인한 변경인지 |
| `trip_day_id` | uuid FK → `trip_days` (CASCADE) | |
| `item_id` | uuid FK → `itinerary_items` | 바뀐 항목. 승인 전후로 같다 |
| `original_place_id` | uuid FK → `places` | 되돌릴 때 복원할 장소 |
| `new_place_id` | uuid FK → `places` | |
| `before_schedule_version` | int | 승인 직전 version. 되돌릴 경로를 찾는 키 |
| `approved_schedule_version` | int | 승인으로 오른 version |
| `approved_at` | timestamptz | 서버 시각 |
| `undo_expires_at` | timestamptz | `approved_at + 30초` |
| `idempotency_key` | varchar(255) | REPL-002 승인 요청을 식별하는 client key |
| `response_snapshot` | jsonb | 동일 key 재요청에 반환할 첫 승인 응답 |
| `undone_at` | timestamptz \| null | 되돌린 시각 |
| `undo_schedule_version` | int \| null | 되돌리기로 오른 version |

**제약과 인덱스**

- `uq_place_replacements_preview`: `preview_id` unique. 한 미리보기는 한 번만 승인된다(FR-011).
- `ix_place_replacements_day`: `(trip_day_id, approved_at DESC)`

**되돌릴 수 있는 조건** (셋을 모두 만족)

1. `undone_at IS NULL`
2. 서버 시각 ≤ `undo_expires_at`
3. `trip_days.schedule_version = approved_schedule_version`

3번이 후속 일정 변경 감지를 대신한다. 승인 이후 어떤 경로로든 그 날짜 일정이 바뀌면 version이 올라 자동으로 되돌릴 수 없게 된다.

## 2. 값 구조

### 2.1 비교 항목 (`comparison`)

네 항목 각각이 `{ before, after }` 쌍이다. 확보하지 못한 값은 `null`이고, 앱은 `정보 없음`으로 표시한다(FR-002, UI-002).

| 키 | before | after |
|---|---|---|
| `totalDurationSeconds` | 현재 활성 `routes.total_duration_seconds` | 미리보기 계산 결과 |
| `totalDistanceMeters` | 현재 활성 `routes.total_distance_meters` | 미리보기 계산 결과 |
| `estimatedArrivalAt` | `itinerary_items.estimated_arrival_at` | 미리보기 경로로 다시 계산한 ETA |
| `closesAt` | 기존 장소의 마감 시각 | 대체 장소의 마감 시각 |

`closesAt`은 F008 `services/detection/operating_hours.py` 조회를 재사용한다. 실패하면 그 항목만 `null`로 두고 나머지를 제공한다(constitution IV).

### 2.2 기존 테이블 변경

**없다.** `itinerary_items.place_id`를 바꾸고 `trip_days.schedule_version`을 올리고 `routes`에 새 version 행을 넣는 것은 모두 기존 컬럼으로 처리된다(`research.md` 3절). `detections`도 기존 `status`·`resolved_at`만 쓴다.

## 3. 상태 전이

### 3.1 승인 (하나의 transaction)

```
  transaction 전 Google 운영 상태 재확인(해당 시)
  FOR UPDATE로 trip_days·미리보기·대상 항목·감지 결과 잠금
  ├ 재검증: 미리보기 PENDING·미만료, schedule_version 일치,
  │         item이 아직 미방문(PLANNED|EN_ROUTE, actual_arrived_at·completed_at null),
  │         detection이 ACTIVE, 대체 장소가 그 날짜에 중복 아님, 대체 장소 방문 가능
  ├ itinerary_items.place_id ← alternative_place_id
  ├ trip_days.schedule_version += 1
  ├ 기존 활성 routes → HISTORICAL, 미리보기 경로를 새 version 활성 행으로 삽입
  ├ recalculate_day_eta(trip_day_id)
  ├ place_replacements 삽입 (undo_expires_at = now + 30s)
  ├ route_previews.status ← APPROVED
  └ detections.status ← RESOLVED, resolved_at ← now
```

재검증 중 하나라도 어긋나면 transaction 전체를 되돌리고 원인별 오류를 반환한다(FR-010). 외부 호출이 없으므로 부분 성공 상태가 생기지 않는다.

### 3.2 되돌리기 (하나의 transaction)

```
FOR UPDATE로 trip_days 잠금
  ├ 판정: undone_at null, 미만료, schedule_version = approved_schedule_version
  ├ itinerary_items.place_id ← original_place_id
  ├ trip_days.schedule_version += 1
  ├ before_schedule_version의 HISTORICAL 경로를 새 version 활성 행으로 복사
  ├ recalculate_day_eta(trip_day_id)
  ├ place_replacements.undone_at ← now, undo_schedule_version ← 새 version
  └ 감지 결과 복귀 (3.3)
```

승인 전 경로가 `routes`에 `HISTORICAL`로 남아 있어 다시 계산하지 않는다.

### 3.3 되돌리기의 감지 결과 복귀

`research.md` 5절의 fingerprint 충돌 처리다.

```
같은 fingerprint의 ACTIVE detection이 있나?
  ├ 없다 → 되돌린 detection.status ← ACTIVE, resolved_at ← null
  └ 있다 → 되돌린 detection.status ← INVALIDATED (새 ACTIVE를 그대로 둔다)
```

두 경우 모두 사용자에게는 원래 장소에 대한 `ACTIVE` 감지 결과가 하나 보인다. `uq_detections_active_fingerprint` 부분 unique 제약을 위반하지 않는다.

**F008·F009 문서 동기화 대상**: `RESOLVED → ACTIVE`, `RESOLVED → INVALIDATED` 두 전이가 추가된다. F009 `data-model.md`는 `RESOLVED`(F010 승인)만 정의하고 역방향이 없다.

## 4. Android 상태 모델

### 4.1 미리보기 화면 (`com.gilpick.replacement`)

```kotlin
sealed interface PreviewUiState {
    data object Loading : PreviewUiState                       // 1초 지연 후 표시(ui-guidelines 9절)
    data class Error(val error: ReplacementError) : PreviewUiState
    data class Content(
        val preview: RoutePreviewDto,
        val now: Instant,
        val approving: Boolean = false,
        val approveFailure: ReplacementError? = null,
    ) : PreviewUiState
}
```

- `empty` 상태는 없다. 후보가 없는 상황은 F009가 처리한다(UI-004).
- `approveFailure`는 원인별로 다음 행동이 달라야 한다(UI-005). `ScheduleChanged`·`PreviewExpired`·`AlreadyVisited`·`AlternativeUnavailable`·`Network`.
  **경로 관련 실패는 여기 없다.** 승인은 미리보기에서 계산을 마친 경로를 확정만 하므로 경로를 얻지 못하는 상황은 `Error`(미리보기 생성 실패)에서만 나타난다(`research.md` 2절).

### 4.2 진행 화면 확장 (`ProgressUiState.Content`)

| 필드 | 규칙 |
|---|---|
| `replacementUndo: ReplacementUndoDto?` | PROG-001 응답의 되돌릴 수 있는 장소 변경. 없으면 null |
| `replacementUndoPending: Boolean` | 되돌리는 중. 행동을 잠근다 |
| `replacementUndoError: ReplacementError?` | 마지막 되돌리기 실패 |

**표시 우선순위(UI-006a)**: 되돌리기 표시 지점 하나에서 아래 순서로 고른다.

1. `replacementUndo`(장소 변경, 30초)가 있으면 그것
2. 없으면 `visibleUndoable`(F007 자동 확정, 5분)

남은 시간이 짧은 쪽을 먼저 보여야 두 되돌리기를 모두 쓸 수 있다. 장소 변경 되돌리기가 끝나거나 되돌려지면 자리가 비고 F007 토스트가 남은 시간만큼 이어서 보인다.

## 5. 계약 문서 동기화 대상

- `docs/design/api-spec.md`: 2절 구현 현황 REPL-001~004 행, 8절 REPL 절 전체. 비교 항목·감지 결과 연동·되돌리기 거절 사유를 추가하고, REPL-001 요청의 `itineraryVersion`을 `scheduleVersion`으로 고친다(`research.md` 11절).
- `docs/design/er-schema.md`: `route_previews`·`place_replacements` 두 테이블과 API 매핑에 REPL-001~004 추가.
- F009 `data-model.md`·F008 감지 결과 상태 전이: 3.3의 두 전이 추가.
- F006 `data-model.md`: PROG-001 응답에 되돌릴 수 있는 장소 변경 필드가 추가되고 `ProgressUiState.Content`가 확장된다.
- `docs/planning/functional-spec.md` 5.3: 경로 계산을 미리보기 시점에 끝낸다는 순서를 반영한다.
