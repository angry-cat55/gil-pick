# Data Model: F007 위치 기반 감지

migration `006_create_progress_events`가 아래 변경을 담는다. F006 migration 005가 만든 `progress_transitions` 컬럼은 **그대로 쓰며 추가하지 않는다**. 005는 `decision`·`auto_finalize_at`·`undo_deadline`·`cancelled_at`·`undone_at`·`trigger_event_id`를 이미 nullable로 만들어 뒀다.

## ProgressEvent (`progress_events` **신규**, ERD 7.1 기준)

앱이 올린 위치 이벤트 하나. 판정 근거이자 중복 방지 단위다.

| 필드 | 형식 | F007 규칙 |
|---|---|---|
| `progress_event_id` | uuid | PK |
| `client_event_id` | uuid | 앱이 만든 중복 방지 ID. `UNIQUE` |
| `trip_day_id` | uuid | FK `trip_days` cascade |
| `item_id` | uuid | FK `itinerary_items` cascade. 이벤트가 가리키는 장소 |
| `event_type` | varchar(20) | `DWELL`·`EXIT`·`REENTER` |
| `geofence_id` | varchar(255) | 앱이 등록한 지오펜스 식별자. `{itemId}:{ARRIVAL\|DEPARTURE}` 형식 |
| `location` | geography(Point,4326) | 이벤트 좌표 |
| `accuracy_meters` | numeric(6,2) | 위치 정확도 |
| `occurred_at` | timestamptz | 기기 이벤트 시각 |
| `received_at` | timestamptz | 서버 수신 시각 |
| `accepted` | boolean | 자동 판정에 사용했는지 |
| `rejection_reason` | varchar(80) nullable | `LOW_ACCURACY`·`STALE`·`DAY_NOT_IN_PROGRESS`·`ITEM_NOT_ELIGIBLE`·`DETECTION_PAUSED`·`PROMPT_LIMIT_REACHED`·`DEPARTURE_DETECTION_STOPPED` |

제약과 인덱스:

- `UNIQUE(client_event_id)` — 지오펜스 broadcast 중복 전달을 막는다.
- 정확도가 기준을 넘거나 `received_at - occurred_at`이 유효 시간을 넘으면 저장하되 `accepted=false`로 둔다(FR-002).
- `INDEX(trip_day_id, occurred_at DESC)` — 날짜별 최근 이벤트 조회.
- 좌표는 판정 근거로만 저장하고 이동 경로를 따로 축적하지 않는다(FR-026).

## ProgressTransition (기존 `progress_transitions`, **컬럼 추가 없음**)

F006이 쓰지 않던 필드를 F007이 채운다.

| 필드 | F006 | F007 규칙 |
|---|---|---|
| `transition_type` | `START`·`ARRIVE`·`DEPART`·`SKIP`·`UNDO_COMPLETE`·`UNDO_SKIP` | `ARRIVAL`(도착 후보), `DEPARTURE`(출발 후보), `COMPOSITE`(이전 완료+다음 도착, FR-009) |
| `status` | 항상 `CONFIRMED` | `PENDING_CONFIRMATION` → `CONFIRMED`(사용자 확인) / `AUTO_CONFIRMED`(무응답) / `CANCELLED`(거절·재진입·대상 소멸) / `UNDONE`(되돌림) |
| `source` | 항상 `MANUAL` | 후보 생성 `GEOFENCE_DWELL`·`GEOFENCE_EXIT`, 사용자 확인 `GEOFENCE_CONFIRMED`, 무응답 확정 `GEOFENCE_AUTO` |
| `decision` | null | `CONFIRM`·`NOT_ARRIVED`·`STILL_HERE` |
| `trigger_event_id` | null | 후보를 만든 `progress_events.progress_event_id`. migration 006에서 FK 연결 |
| `auto_finalize_at` | null | 후보 생성 시각 + 무응답 대기 시간 |
| `confirmed_at` | 수동 처리 시각 | 확정 시각(사용자 확인 또는 자동 확정) |
| `undo_deadline` | null | 자동 확정에만 설정. `confirmed_at` + 되돌리기 시간. 사용자가 직접 확인한 전환에는 설정하지 않는다(FR-020) |
| `cancelled_at` | null | 거절·재진입·대상 소멸로 후보가 사라진 시각 |
| `undone_at` | null | 되돌린 시각 |
| `affected_items` | 파생 변경 전부 | 같음. 되돌리기 시 `restoredStatus`를 채운다 |
| `progress_version_after` | 전환 후 값 | 같음. 후보 생성은 상태를 바꾸지 않으므로 생성 시점의 값을 그대로 기록한다 |

제약(ERD 7.2에 이미 있음):

- 한 item에 `PENDING_CONFIRMATION` transition은 동시에 하나만 존재한다.
- `UNIQUE(trip_day_id, idempotency_key)`.

### 상태 전이

```text
                      geofence DWELL/EXIT
                              │
                              ▼
                   PENDING_CONFIRMATION ─── CONFIRM ──────────► CONFIRMED
                              │
                              ├── NOT_ARRIVED / STILL_HERE ───► CANCELLED
                              ├── REENTER (출발 후보) ─────────► CANCELLED
                              ├── 대상이 수동 처리·일정에서 삭제 ► CANCELLED
                              └── auto_finalize_at 경과 ───────► AUTO_CONFIRMED
                                                                     │
                                                          undo_deadline 안 되돌리기
                                                                     ▼
                                                                  UNDONE
```

`CONFIRMED`·`CANCELLED`·`UNDONE`은 종착 상태다. `AUTO_CONFIRMED`는 `undo_deadline`이 지나면 사실상 종착이 된다(별도 상태를 두지 않고 시각으로 판정한다).

## 파생 판정 규칙 (새 컬럼 없음)

`research.md` 3절의 결정에 따라 아래 다섯 규칙은 `progress_transitions` 조회로 계산한다. 저장 위치를 따로 두지 않는다.

| 규칙 | 근거 | 판정식 |
|---|---|---|
| 장소별 도착 질문 횟수 상한 | FR-008 | `count(ARRIVAL transitions of item) >= 질문 상한` → 후보 생성 안 함 |
| `아직이에요` 후 재질문 가능 시각 | FR-007 | `max(cancelled_at where decision=NOT_ARRIVED) + 재질문 간격` |
| 되돌린 뒤 감지 재개 시각 | FR-017a | `max(undone_at of same item·same type) + 재질문 간격` |
| `아직 머무는 중` 이후 자동 출발 중단 | FR-013 | `exists(DEPARTURE transition of item where decision=STILL_HERE)` |
| 두 번째 출발 되돌리기 이후 중단 | FR-017a | `count(DEPARTURE transitions of item where undone_at is not null) >= 2` |

## 감지 대상 (응답 전용, 저장하지 않음)

서버가 진행 조회 응답으로 내려주는 "지금 감지해야 할 것" 목록이다. 위 파생 규칙과 F006 상태로 매번 계산하며 테이블에 두지 않는다(`research.md` 4절).

| 필드 | 의미 |
|---|---|
| `itemId` | 대상 장소 |
| `kind` | `ARRIVAL`·`DEPARTURE` |
| `latitude`·`longitude` | 지오펜스 중심. 장소 좌표 |
| `radiusMeters` | 종류별 반경 |
| `dwellMinutes` | `ARRIVAL`에만. 체류 판정 시간 |
| `geofenceId` | `{itemId}:{kind}`. 앱이 등록·해제에 그대로 쓴다 |

대상이 되는 조건:

- 그 날짜가 `IN_PROGRESS`이고 `detection_active=true`
- `ARRIVAL`: `status=EN_ROUTE`인 항목 하나. 질문 상한 미도달, 재질문·재개 시각 경과
- `DEPARTURE`: `status=ARRIVED`인 항목 하나. `STILL_HERE` 선택 없음, 되돌리기 2회 미도달, 재개 시각 경과

날짜가 완료되면 목록은 비고, 앱은 등록된 지오펜스를 모두 해제한다(FR-022).

## TripDay (기존 `trip_days`, 컬럼 추가 없음)

| 필드 | F007 규칙 |
|---|---|
| `detection_active` | F006이 시작 시 true, 완료 시 false로 관리한다. F007은 감지 대상 산출에서 이 값을 읽기만 한다 |
| `progress_version` | 후보 생성·취소는 올리지 않는다(상태를 바꾸지 않으므로). 확정·되돌리기는 F006 전환과 같이 +1 |

## ItineraryItem (기존 `itinerary_items`, 컬럼 추가 없음)

F007은 상태와 시각 필드를 F006 전환 함수를 통해서만 바꾼다. 직접 쓰지 않는다.

## 유지·삭제

- `progress_events`는 `trip_days` 삭제 시 cascade로 함께 사라진다. 여행 삭제 시 위치 좌표가 남지 않는다.
- 취소·되돌린 transition도 이력으로 남긴다. constitution III의 추적 가능성 요구이며, 파생 판정(질문 횟수·재개 시각)이 이 이력을 근거로 쓴다.
