# F010 일정 변경 기술 조사

`spec.md`의 요구사항을 만족하는 구현 방향과 그 근거다. 결정은 저장소의 기존 F004·F005·F006·F007·F008·F009 구현을 확인해 정했다.

## 1. 미리보기를 어디에 두나

**Decision**: `route_previews` 테이블에 저장한다. 계산한 대체 경로 payload와 비교 항목, 만든 시점의 `schedule_version`, 만료 시각을 함께 담는다.

**Rationale**:

- 미리보기는 계산한 경로 전체(구간별 좌표·시간)를 담아야 하고, 승인 시 그 값을 그대로 `routes`에 확정해야 한다(2절). 서명 토큰에 담기에는 크다.
- 한 감지 결과에 새 미리보기를 만들면 앞선 미리보기를 승인할 수 없어야 한다(FR-006). 서버가 상태를 들고 있어야 판정할 수 있다.
- `previewId`로 승인·거절을 받는 기존 API 초안(REPL-002·REPL-003)과 맞는다.

**Alternatives considered**: F009 후보 토큰(`verify_candidate_token`)처럼 서명 토큰으로만 두기 — 경로 payload를 담을 수 없고, 앞선 미리보기 무효화를 서버가 판정할 수 없다.

## 2. 승인의 원자성과 외부 호출 분리

**Decision**: **경로 계산을 미리보기 생성 시점에 끝내고, 승인 transaction 안에서는 외부 호출을 하지 않는다.** 승인은 미리보기에 저장된 계산 결과를 확정하기만 한다.

Google 장소의 최신 운영 상태는 승인 transaction을 시작하기 전에 별도 읽기 session으로 재확인한다. 명확히 방문 불가인 경우만 `ALTERNATIVE_UNAVAILABLE`로 거절하고, provider 조회 실패는 불가능 상태로 지어내지 않으며 미리보기에 저장된 마감 시각 검증을 유지한다(constitution IV).

승인 transaction이 하는 일:

1. `itinerary_items.place_id` 교체(3절)
2. `trip_days.schedule_version` 증가
3. 기존 활성 `routes`를 `HISTORICAL`로 내리고 미리보기의 경로를 새 version의 활성 행으로 삽입
4. `recalculate_day_eta` 호출
5. `place_replacements` 이력 삽입
6. `detections.status`를 `RESOLVED`로

**Rationale**:

- constitution III은 승인을 하나의 transaction으로 요구하는데, 기존 `RouteService.calculate_current`는 **provider 호출을 transaction 밖에 두도록 일부러 설계돼 있다**(입력 조회 transaction을 provider 호출 전에 종료). 승인 중에 경로를 계산하면 두 요구가 정면으로 충돌한다.
- 계산을 앞으로 당기면 승인 transaction은 DB 작업만 남아 전부 성공하거나 전부 실패한다. `spec.md` Q1 결정(경로 재계산 실패 시 승인 전체 취소)이 별도 보상 로직 없이 성립한다.
- 사용자 입장에서도 이미 본 그 경로가 그대로 확정된다. 승인 후 다시 계산하면 사용자가 본 비교와 다른 결과가 저장될 수 있다.

**Alternatives considered**:

- 승인 중 재계산 후 실패 시 보상 트랜잭션으로 되돌리기 — 부분 성공 구간이 생기고, 보상 자체가 실패하면 복구 불가.
- F005처럼 일정 저장은 성공시키고 경로만 `FAILED`로 남기기 — `spec.md` FR-014에서 배제한 선택지다. 진행 중 여행에서 경로 없는 일정이 남으면 남은 이동을 안내할 수 없다.

**따라서 FR-014의 "승인 중 경로 재계산 실패"는** 미리보기에 저장한 계산 결과를 쓸 수 없는 경우(미리보기 만료, 일정 version 불일치)로 좁혀진다. 이때 승인은 아무것도 바꾸지 않고 실패한다.

## 3. 장소를 어떻게 교체하나

**Decision**: `itinerary_items` 행을 그대로 두고 `place_id`만 바꾼다. `item_id`는 유지한다.

**Rationale**:

- `item_id`가 유지되면 F006 진행 상태(`status`·시각), F007 감지 대상(`geofenceId`가 `itemId` 기반), F008 감지 결과(`fingerprint = trip_day_id:item_id`), F009 후보 토큰 claim이 모두 그대로 유효하다.
- `sequence`·`planned_stay_minutes`·`stay_source`·`transport_mode_to_next`가 자동으로 유지돼 FR-009를 별도 처리 없이 만족한다.
- 되돌리기는 `place_id`를 원래 값으로 되돌리는 한 줄이면 된다.

**Alternatives considered**: 기존 항목 삭제 후 새 항목 삽입 — `item_id`가 바뀌어 진행 상태·감지 이력·되돌리기 대상이 모두 끊긴다. `uq_itinerary_items_day_sequence` 때문에 순서 재배치도 필요해진다.

**주의**: 교체 대상은 `status = 'PLANNED'` 또는 `'EN_ROUTE'`이고 `actual_arrived_at`·`completed_at`이 비어 있어야 한다(FR-010). 승인 시 `SELECT ... FOR UPDATE`로 다시 확인한다.

## 4. 되돌리기 가능 판정

**Decision**: `place_replacements` 이력 행에 `undo_expires_at`(승인 시각 + 30초), `undone_at`, `approved_schedule_version`을 둔다. 되돌릴 수 있는 조건은 세 가지를 모두 만족할 때다.

- `undone_at IS NULL`
- 서버 시각 ≤ `undo_expires_at`
- `trip_days.schedule_version == approved_schedule_version`

**Rationale**:

- 후속 일정 변경 감지(FR-017)를 별도 플래그 없이 version 비교로 얻는다. 승인 이후 어떤 경로로든 그 날짜 일정이 바뀌면 version이 올라 자동으로 되돌리기가 막힌다. `docs/planning/requirements.md` REPL-02의 "후속 일정 변경이 생기면 이전 되돌리기를 비활성화한다"가 그대로 구현된다.
- 만료 판정을 서버 시각으로 한다는 요구(FR-015)를 만족하고, 앱은 `undoExpiresAt`을 표시에만 쓴다. F007 되돌리기와 같은 방식이라 앱 코드도 재사용된다.
- 되돌리기는 자연 멱등이다. 이미 되돌린 행에 다시 요청하면 같은 결과를 돌려준다(FR-017 마지막 문장).

**되돌리기 transaction**: `place_id` 원복 → `schedule_version` 증가 → 승인 전 version의 경로를 다시 활성 행으로 복사 → `recalculate_day_eta` → `undone_at` 기록 → 감지 결과 복귀(5절). 승인과 마찬가지로 외부 호출이 없다. 승인 전 경로는 `routes`에 `HISTORICAL`로 남아 있어 다시 계산할 필요가 없다.

## 5. 되돌린 뒤 감지 결과 복귀와 fingerprint 충돌

**Decision**: 되돌리기는 그 감지 결과를 `ACTIVE`로 되돌린다. 단 **같은 `fingerprint`의 `ACTIVE` 감지 결과가 이미 있으면** 되돌린 쪽을 `INVALIDATED`로 두고 새 `ACTIVE`를 그대로 둔다.

**Rationale**:

- `detections`에는 `uq_detections_active_fingerprint`(`fingerprint`에 대한 `status='ACTIVE'` 부분 unique)가 걸려 있다. `fingerprint = trip_day_id:item_id`이고 3절에서 `item_id`가 유지되므로, 승인 후 F008이 그 항목을 다시 평가해 새 `ACTIVE`를 만들었다면 되돌리기가 그대로 `ACTIVE`로 바꾸면 **제약 위반으로 되돌리기 자체가 실패한다.**
- 사용자 관점에서 두 경우의 결과는 같다. 원래 장소가 돌아왔고 그 장소에 대한 `ACTIVE` 감지 결과가 있어 배너가 보인다. 어느 행이 그 역할을 하는지는 사용자에게 보이지 않는다.
- 새 `ACTIVE`가 더 최신 평가이므로 그쪽을 남기는 것이 맞다.

**Alternatives considered**:

- 무조건 `ACTIVE`로 되돌리고 기존 `ACTIVE`를 `INVALIDATED`로 — 더 오래된 평가를 남기게 된다.
- 부분 unique 인덱스를 없애기 — F008이 중복 감지를 억제하려고 둔 제약이라 F010이 지울 수 없다.

**문서 동기화 필요**: F009 `data-model.md`는 `RESOLVED`(F010 승인)만 정의하고 역방향이 없다. 이 결정으로 `RESOLVED → ACTIVE`와 `RESOLVED → INVALIDATED` 두 전이가 추가된다. F008·F009 문서를 같은 변경 범위에서 갱신한다.

## 6. 멱등성

**Decision**: 미리보기 생성(REPL-001)과 승인(REPL-002)은 `Idempotency-Key`를 받는다. F006의 `response_snapshot` 방식을 재사용하되, 승인 key와 응답은 승인 이력인 `place_replacements` 행에 저장한다. 거절(REPL-003)과 되돌리기(REPL-004)는 키 없이 자연 멱등으로 둔다.

**Rationale**:

- constitution III이 생성·승인 요청을 멱등 대상으로 지목한다. 승인이 두 번 처리되면 일정이 두 번 바뀐다(FR-011).
- 거절은 미리보기를 폐기하는 것뿐이라 두 번 해도 같다. 되돌리기는 4절대로 `undone_at`으로 자연 멱등이며, 이후 감지 상태가 다시 바뀌어도 같은 결과를 반환하도록 최초 REPL-004 응답을 `place_replacements.response_snapshot._undoResult`에 함께 보존한다.
- F006·F007이 이미 같은 헤더와 저장소를 쓰므로 앱과 서버 양쪽에 새 개념이 없다.

## 7. 미리보기 유효 시간

**Decision**: 5분(`PREVIEW_TTL_MINUTES = 5`).

**Rationale**:

- F009 후보 토큰이 15분이고 미리보기는 그보다 짧아야 한다(FR-005).
- 사용자가 비교를 보고 결정하는 데 필요한 시간은 SC-004 기준 60초다. 5분이면 충분히 여유가 있다.
- 진행 중 여행에서는 일정·위치·운영 상태가 계속 변한다. 오래 열어 두면 승인 시점 재검증(FR-010)에서 걸릴 확률만 높아진다.

**Alternatives considered**: 후보 토큰과 같은 15분 — 만료보다 재검증 실패가 먼저 나서 사용자가 받는 안내가 덜 정확해진다.

## 8. 비교 항목을 어떻게 만드나

**Decision**: 네 항목을 이렇게 얻는다.

| 항목 | 기존 값 | 변경 값 |
|---|---|---|
| 그 날짜 전체 이동 시간 | 현재 활성 `routes.total_duration_seconds` | 미리보기 계산 결과 |
| 그 날짜 전체 이동 거리 | 현재 활성 `routes.total_distance_meters` | 미리보기 계산 결과 |
| 대상 항목 도착 예정 시각 | `itinerary_items.estimated_arrival_at` | 미리보기 경로로 다시 계산한 ETA |
| 대상 항목 운영 마감 시각 | 기존 장소의 마감 시각 | 대체 장소의 마감 시각 |

운영 마감 시각은 F008 `services/detection/operating_hours.py`의 조회를 재사용한다. 확보하지 못한 항목은 값을 만들지 않고 없음으로 둔다(FR-002, constitution IV).

**Rationale**: 기존 값을 새로 계산하지 않고 저장된 값을 쓴다. 사용자가 지금 보고 있는 일정의 값과 비교 화면의 값이 어긋나지 않아야 한다.

**Alternatives considered**: F009 후보 응답의 `closesAt`을 그대로 받기 — 후보 조회와 미리보기 사이에 시간이 흘렀고, 직접 검색으로 고른 장소에는 그 값이 없다.

## 9. Android 화면 구조와 되돌리기 자리 공유

**Decision**: 새 패키지 `com.gilpick.replacement`에 미리보기 화면을 만든다. 되돌리기는 **진행 화면의 기존 `UndoToast` 자리를 공유**한다.

- F009가 `alternativeGraph(onSelectPlace: (SelectedAlternative) -> Unit)` 콜백을 남겨 두었다. `MainActivity`에서 이 콜백을 미리보기 route로 연결한다.
- 승인 성공 → 진행 화면으로 돌아가며 되돌리기 정보를 전달한다.
- `ProgressUiState.Content`에 장소 변경 되돌리기 상태를 더하고, 표시 지점 하나에서 **남은 시간이 짧은 쪽(F010, 30초)을 먼저** 보인다(UI-006a).

**Rationale**:

- 진행 화면의 되돌리기 표시 지점은 한 군데뿐이라 두 토스트가 겹쳐 그려질 수 없다. 무엇을 그 자리에 놓을지 고르는 판단만 추가하면 된다.
- 먼저 사라지는 쪽을 먼저 보여야 두 되돌리기를 모두 쓸 수 있다. F007이 5분, F010이 30초다.
- 사용자가 F007에서 이미 익힌 위치와 형식이라 새로 배울 것이 없다.

**Alternatives considered**: 미리보기 화면에 결과 상태를 두고 거기서만 되돌리기 — 승인 후 사용자가 진행 화면으로 돌아가는 순간 30초 중 대부분을 쓸 수 없게 된다.

**주의**: `com.gilpick.alternative` 패키지는 F009 구현(PR #333)에서 만들어진다. F010 Android 작업은 그 병합 이후에 시작한다.

## 10. Figma에 없는 화면 상태

**Decision**: Figma Make `Design UI from Reference`에 세 가지를 추가한 뒤 저장소 사본을 갱신한다.

1. `RoutePreviewScreen`의 **승인 진행 중·승인 실패** 상태 — 지금은 `변경 승인` 버튼에서 끝난다.
2. `ActiveTravelScreen`의 **장소 변경 되돌리기 토스트** — F007 자동 확정 토스트만 있고 장소 변경 문구가 없다.
3. `RoutePreviewScreen`의 **비교 항목 `정보 없음`** 표시 — 값을 확보하지 못한 항목의 모양.

**Rationale**: AGENTS.md 6절에 따라 Figma가 모양의 정본이고, 없는 화면을 코드에서 임의로 만들지 않는다. F007 T003과 같은 방식으로 사람이 Figma에 추가한 뒤 MCP로 원본을 받아 사본을 갱신한다.

**이미 있는 것은 그대로 쓴다**: `RoutePreviewScreen`의 지도·기존/변경 범례·비교 표·`변경 승인`·`다른 후보 보기`, `RouteRecalculatingScreen`의 계산 진행 표시와 "계산에 실패해도 기존 일정은 그대로 유지됩니다" 문구.

## 11. 일정 버전 필드 이름

**Decision**: `scheduleVersion`으로 통일한다.

**Rationale**: `docs/design/api-spec.md`의 REPL-001 초안은 `itineraryVersion`으로 적혀 있지만 F004 ITIN-002·F006 PROG-001·`trip_days.schedule_version`이 모두 `scheduleVersion`이다. 같은 값을 두 이름으로 부르면 앱과 서버가 갈라진다. api-spec의 REPL 절을 같은 변경 범위에서 고친다.
