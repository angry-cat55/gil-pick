# Data Model: 알림

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Contracts**: [contracts/notifications.openapi.yaml](contracts/notifications.openapi.yaml)

F011은 **새 테이블 1개(`notifications`)와 migration 1개**를 추가한다. 기존 `device_sessions.fcm_token`·`users.replacement_suggestion_enabled` 컬럼(migration `001`)을 재사용하고, `device_sessions`에 partial unique 인덱스 1개를 더한다.

## 1. 새 영속 데이터

### 1.1 `notifications` (신규, `er-schema.md` 9.1 그대로)

| 컬럼 | 타입 | NULL | 규칙 |
|---|---|---:|---|
| `notification_id` | uuid | N | PK |
| `user_id` | uuid | N | FK → `users.user_id` `ON DELETE CASCADE` |
| `trip_id` | uuid | Y | FK → `trips.trip_id` `ON DELETE SET NULL`. 모든 유형에 채운다(이동·정리 기준) |
| `trip_day_id` | uuid | Y | FK → `trip_days.trip_day_id` `ON DELETE SET NULL` |
| `item_id` | uuid | Y | FK → `itinerary_items.item_id` `ON DELETE SET NULL`. 도착·출발·자동·재질문에 채운다 |
| `detection_id` | uuid | Y | FK → `detections.detection_id` `ON DELETE SET NULL`. `PLACE_CHANGE_SUGGESTION`에 채운다 |
| `transition_id` | uuid | Y | FK → `progress_transitions.transition_id` `ON DELETE SET NULL`. 도착·출발·자동·재질문에 채운다 |
| `type` | varchar(50) | N | `CHECK (type IN ('PLACE_CHANGE_SUGGESTION','ARRIVAL_CHECK','DEPARTURE_CHECK','ARRIVAL_AUTO_CONFIRMED','DEPARTURE_AUTO_CONFIRMED'))` |
| `title` | varchar(200) | N | 서버 생성(R14) |
| `body` | text | N | 서버 생성(R14) |
| `dedup_key` | varchar(255) | Y | R2 구성. 있으면 `(user_id, dedup_key)` 유일 |
| `delivery_status` | varchar(20) | N | `PENDING`, `SENT`, `FAILED`, `NO_DEVICE` |
| `delivery_attempts` | integer | N | dispatch 시도 횟수. 기본 0, 최대 3 |
| `next_attempt_at` | timestamptz | Y | `PENDING` 재시도 가능 시각 |
| `sent_at` | timestamptz | Y | 최소 한 기기에 FCM 발송이 성공한 시각 |
| `read_at` | timestamptz | Y | 앱 읽음 시각 |
| `created_at` | timestamptz | N | 생성 시각. 보존·정렬 기준 |

**인덱스**

| 이름 | 정의 | 용도 |
|---|---|---|
| `ix_notifications_user_unread` | `(user_id, read_at, created_at DESC)` | NOTI-001 목록·`read` 필터 |
| `ix_notifications_retention` | `(created_at)` | 90일 정리 job |
| `uq_notifications_dedup` | unique `(user_id, dedup_key)` WHERE `dedup_key IS NOT NULL` | FR-002 중복 방지, `ON CONFLICT DO NOTHING` |
| `ix_notifications_pending` | `(next_attempt_at, created_at)` WHERE `delivery_status = 'PENDING'` | dispatch 큐 스캔 |

### 1.2 `device_sessions` (기존, 인덱스 1개 추가 + 쓰기 3종)

| 컬럼 | F011 사용 |
|---|---|
| `fcm_token` (Text, 있음) | DEV-001이 갱신, DEV-002·로그아웃·무효 확인이 `NULL`. 발송 대상 = `revoked_at IS NULL AND fcm_token IS NOT NULL` |
| `client_device_id` | DEV-001·DEV-002 소유권 키(`user_id + client_device_id`) |
| `platform` | payload `platform`(MVP `ANDROID` 고정) |
| `revoked_at` | 로그아웃 시 `fcm_token`도 함께 `NULL` |

추가 인덱스: `uq_device_sessions_fcm_token` unique `(fcm_token)` WHERE `fcm_token IS NOT NULL AND revoked_at IS NULL` (`er-schema.md` 12절이 요구하나 미생성 상태였음).

### 1.3 읽기만 하는 테이블

| 테이블 | 용도 |
|---|---|
| `users` | `replacement_suggestion_enabled`(발송 시 존중, FR-003·FR-021), `user_id` 소유권 |
| `detections` | `PLACE_CHANGE_SUGGESTION` 계기·이동 대상. 최초 INSERT 판별(R3). `status`·`reason`·`item_id`·`trip_day_id` |
| `progress_transitions` | 도착·출발·자동·재질문 계기·이동 대상. `status`·`auto_finalize_at`·`undo_deadline`·`primary_item_id` |
| `trip_days` | `status='IN_PROGRESS'` 대상 스캔(dispatch), 날짜 |
| `trips` | 소유권, 논리 삭제(`deleted_at`) 여부(정리) |
| `itinerary_items` + `places` | 본문 문구의 장소명 |

## 2. 상태 전이

### 2.1 `notifications.delivery_status`와 `sent_at`

```text
(행 생성, PENDING, attempts=0, sent_at=NULL)
   │  dispatch 또는 동기 즉시 발송
   ▼
SENT      (최소 1개 기기 성공, sent_at=now)
FAILED    (영구 실패 또는 dispatch 3회 소진)
NO_DEVICE (활성 기기 없음)
```

- `PENDING`만 dispatch 대상이다. 일시 실패는 `next_attempt_at` 이후 다시 시도하며 3회째 실패하면 `FAILED`로 끝낸다.
- 일부 기기 성공은 `SENT`로 끝내 이미 받은 기기의 중복 수신을 막는다.
- `read_at`은 사용자 행동(NOTI-002/003)으로만 바뀌며 `sent_at`과 독립.

### 2.2 F011이 소비만 하는 상태(바꾸지 않음)

| 원천 | F011 반응 |
|---|---|
| `detections.status` `→ ACTIVE` (최초 INSERT, F008) | `PLACE_CHANGE_SUGGESTION` 행 생성(설정 on일 때) |
| `progress_transitions.status` `→ PENDING_CONFIRMATION` (F007 `register_event`) | `ARRIVAL_CHECK`/`DEPARTURE_CHECK` `prompt_seq=1` 행 생성 |
| `progress_transitions.status` `→ AUTO_CONFIRMED` (F006/F007 `_auto_confirm`) | `ARRIVAL_AUTO_CONFIRMED`/`DEPARTURE_AUTO_CONFIRMED` 행 생성 |
| `progress_transitions` `next_prompt_at <= now` & 도착 질문 ≤1회 | `ARRIVAL_CHECK` `prompt_seq=2` 행 생성(dispatch가 발견) |
| `detections.status` `→ DISMISSED/RESOLVED/INVALIDATED` · `trip_days` 당일 완료 | 이후 그 대상 알림 생성 안 함(FR-005) |

## 3. 일시 데이터(응답 전용)

### 3.1 NOTI-001 항목 `NotificationItem`

| 필드 | 규칙 |
|---|---|
| `notificationId`·`type`·`title`·`body`·`createdAt` | 그대로 |
| `read` | `read_at IS NOT NULL` |
| `tripId` | 항상 존재 |
| `tripDayId`·`itemId`·`detectionId`·`transitionId` | 유형에 따라 있거나 없음(1.1 규칙) |

목록은 `created_at >= now - 90d`, `(created_at DESC, notification_id DESC)` cursor, envelope·`meta.pagination`은 `api-spec.md` 1절.

### 3.2 FCM data payload (저장 안 함, 발송 시 구성)

```text
{
  "type": "<notification_type>",
  "notificationId": "<uuid>",
  "tripId": "<uuid>",
  "tripDayId": "<uuid?>",
  "itemId": "<uuid?>",
  "detectionId": "<uuid?>",
  "transitionId": "<uuid?>",
  "title": "<title>",
  "body": "<body>"
}
```

모든 값은 문자열. 없는 식별자는 키 자체를 생략. 좌표·평점·정밀 위치·토큰·후보 목록 없음(FR-006, SC-004).

## 4. Android 상태 모델

### 4.1 `NotificationUiState` (`com.gilpick.notification`)

```text
Loading                                   // 1초 지연 후에만 표시
Empty                                     // 받은 알림 0
Error(error: NotificationError, retryable) // 조회 실패·세션 만료
Content(
  groups: List<NotifGroup>,               // 오늘 / 어제 / 그 이전, 각 최신순
  refreshing: Boolean                     // 재조회 중 기존 목록 유지
)
```

- `NotifGroup(label: DateBucket, items: List<NotifItemUi>)`, `NotifItemUi(id, type, title, body, relativeTime, unread, target)`.
- `target` = `Alternative(detectionId, tripId)` | `Progress(tripId, tripName)` — 유형에서 파생(`NotificationLabels`).
- 탭 → NOTI-002 → `onOpen(target)`. "모두 읽음" → NOTI-003 → 로컬 상태도 읽음 표시.

### 4.2 `VariableMonitorUiState` (US6)

```text
Loading
Empty                                     // ACTIVE 감지 0 (Figma hasAlerts=false)
Error(retryable)
Content(items: List<DetectionListItemUi>, sort: TIME | RISK)
```

- 데이터는 `com.gilpick.alternative.AlternativeRepository.listDetections(tripId, status = ACTIVE)` 재사용(F009 DTO `DetectionListItemDto`).
- 항목 "대체 장소 보기" → `onOpenDetection(detectionId, tripId)` → `AlternativePlacesRoute`.

### 4.3 딥링크 파라미터 `PendingNotificationTarget` (`MainActivity`)

`type`, `tripId`, `tripDayId?`, `itemId?`, `detectionId?`, `transitionId?` — 시스템 알림 `PendingIntent` extras에서 파싱. `NavHost` 구성 후 `LaunchedEffect`가 `type`으로 `AlternativePlacesRoute` 또는 `ActiveTravelRoute`로 이동하고 화면이 서버 상태 재조회(FR-019).

## 5. 계약 문서 동기화 대상

- `docs/design/api-spec.md`:
  - 2절 구현 현황: NOTI-001·NOTI-002·DEV-001·DEV-002 `[x]`, **NOTI-003 `PATCH /notifications/read-all` 행 신설**.
  - 9절: NOTI-001 응답에 `type` 값 목록·선택 식별자 필드(`tripDayId`·`itemId`·`transitionId`)·"생성 후 90일만 조회" 문구, NOTI-003 신설, DEV-001에 "활성 세션 필요·`404 DEVICE_SESSION_NOT_FOUND`" 명시, PREF-001·PREF-002는 F012 범위로 표시 유지.
- `docs/design/er-schema.md`:
  - 1절: "알림은 생성 후 90일 보관 후 삭제, 여행 논리 삭제 시 연결 알림도 정리" 문구 추가.
  - 9.1: `type` CHECK 값 5개, `dedup_key` 구성, `delivery_status`·재시도 필드와 `sent_at` = "최소 한 기기 성공 시각" 의미.
  - 10절 enum 표: `notification_type` 행 추가.
  - 12절: `notifications` 인덱스에 `ix_notifications_pending` 추가, `device_sessions` partial unique `fcm_token` 실제 생성 반영.
- `docs/planning/requirements.md` NOTI-01 / `docs/planning/functional-spec.md` 6절: 전달 목표 30초·재시도·포그라운드 미표시·90일 보존을 반영(spec Clarifications 2026-09-10와 일치).
- 설정 필드명(`users.replacement_suggestion_enabled` ↔ API `placeChangeSuggestionNotificationEnabled`) 통일은 **F011 범위 밖**이다. F011은 DB 컬럼만 읽고, 표기 정합은 F012의 `PREF-001`·`PREF-002` 계약 작업에서 처리한다.
- `docs/planning/mvp-features.md`: F011 상태 `SPEC → READY`는 검증된 tasks 문서 PR에서.
