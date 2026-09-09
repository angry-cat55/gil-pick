# Research: 알림

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

spec의 Assumptions에서 plan으로 미룬 항목과 Technical Context의 미확정 사항을 결정한다. 각 항목은 결정·근거·검토한 대안을 적는다. 코드 위치는 2026-09-10 `main`(worktree) 기준이다.

## R1. 알림 유형(enum)

- **Decision**: `notification_type` = `PLACE_CHANGE_SUGGESTION`, `ARRIVAL_CHECK`, `DEPARTURE_CHECK`, `ARRIVAL_AUTO_CONFIRMED`, `DEPARTURE_AUTO_CONFIRMED` 5개. `notifications.type varchar(50)` + `CHECK`로 두고 PostgreSQL enum은 쓰지 않는다(`er-schema.md` 10절 규칙). 도착 재질문은 별도 유형이 아니라 `ARRIVAL_CHECK`를 `dedup_key`만 달리해 한 번 더 만든다(Figma `NotificationsScreen`도 재질문을 같은 "도착하셨나요?"로 표시).
- **Rationale**: spec FR-001의 4계기(감지 생성 / 도착·출발 확인 후보 / 자동 확정 / 재질문)를 덮으면서, api-spec 9절이 이미 쓰는 `PLACE_CHANGE_SUGGESTION`을 유지한다. COMPOSITE 전환(이전 완료 + 다음 도착 동시, `progress_transitions.transition_type='COMPOSITE'`)의 자동 확정도 `ARRIVAL_AUTO_CONFIRMED` 한 건으로 보낸다(spec Edge Cases).
- **Alternatives**: 유형을 2개(`PLACE_CHANGE_SUGGESTION`, `PROGRESS_CHECK`)로 줄이고 본문으로 구분 — 앱이 탭 목적지·아이콘을 유형으로 분기하므로(Figma 5개 아이콘) 유형이 필요. 재질문 전용 유형(`ARRIVAL_REPROMPT`) — 화면·목적지가 `ARRIVAL_CHECK`와 같아 중복.
- **동기화**: `er-schema.md` 10절 enum 표에 `notification_type` 행 추가, `api-spec.md` 9절 NOTI-001 응답에 `type` 값 목록 명시.

## R2. 중복 방지 키(`dedup_key`)

- **Decision**: `notifications`에 `(user_id, dedup_key) WHERE dedup_key IS NOT NULL` partial unique(`uq_notifications_dedup`)를 두고 생성은 `INSERT ... ON CONFLICT DO NOTHING`으로 한다. 키 구성:
  - 장소 변경 제안: `f"detection:{detection_id}"`
  - 도착 확인(최초·재질문): `f"transition:{transition_id}:arrival_check:{prompt_seq}"` (`prompt_seq` 1·2)
  - 출발 확인: `f"transition:{transition_id}:departure_check"`
  - 자동 확정: `f"transition:{transition_id}:auto"`
- **Rationale**: spec FR-002, `er-schema.md` 9.1("사용자 결정 전 동일 제안 알림 추가 생성 금지")·12절("optional unique active `dedup_key`"). `detection_id`는 거절 후 같은 장소에 새 감지가 생기면 새 UUID라, 키에 넣어도 새 감지에 대한 새 알림을 막지 않는다(F008 `detections` active fingerprint partial unique가 감지 자체의 중복을 이미 막는다). `transition_id`는 후보 하나당 고정이라 재평가·재시도·동시 요청에서 한 건만 남는다. 재질문은 `prompt_seq`로 최대 2건까지 허용(`DetectionService.MAX_ARRIVAL_PROMPTS = 2`).
- **Alternatives**: `(user_id, type, item_id)` 키 — 같은 장소에 도착·재도착이 반복되면 오래된 키와 충돌해 정당한 알림이 막힘. 앱 측 중복 제거 — 서버 재시도·다중 요청에서 중복이 새어 나감.

## R3. 장소 변경 제안 알림 생성 지점(F008 연결)

- **Decision**: `api/app/services/detection/evaluator.py`의 `_store_detection(...)`에서 감지가 **처음 INSERT**될 때만(=`on_conflict_do_update`의 갱신 분기가 아닐 때) `NotificationService.create_place_change_suggestion(session, detection)`를 같은 transaction 안에서 호출한다. 최초 INSERT 판별은 upsert 문에 `RETURNING (xmax = 0) AS inserted`를 붙여 얻는다. 이 서비스는 `users.replacement_suggestion_enabled`를 읽어 꺼져 있으면 아무것도 하지 않고(FR-003), 켜져 있으면 `notifications` 행을 `ON CONFLICT DO NOTHING`으로 만든다. FCM 발송은 transaction 커밋 뒤 R7의 dispatch가 맡는다.
- **Rationale**: `_store_detection`이 `ACTIVE` 감지 생성의 유일한 지점이고(`api-spec.md` 7.1 "장소 변경 제안 알림은 F011에서 처리한다"), 10분 주기 루프(`jobs/variable_detection.py::run_variable_detection` → `evaluate_all_active`)와 진행 변경 직후 `reevaluate_day` 둘 다 이 함수를 지난다. 알림 행을 감지 transaction에 포함하면 감지가 있으면 알림도 반드시 있다(FR-007). `dedup_key`로 재실행·동시성은 안전하다.
- **F008 파일 수정**: `evaluator.py` 1곳(`RETURNING` 추가 + 최초 INSERT 시 hook 호출), `evaluate_all_active`가 새로 만든 `detection_id` 목록을 반환하도록 시그니처 확장(dispatch 즉시 발송용). 외부 동작(감지 결과·응답)은 불변. F008 BE 담당(ts) review.
- **Alternatives**: 감지 조회(DETECT-001) 시점에 "알림 안 보낸 ACTIVE"를 찾아 발송 — 조회가 부수효과를 가지면 멱등성·캐시가 깨짐. outbox 테이블 신설 — `er-schema.md` 14절이 `notification_deliveries`를 제외했고 `notifications` 자체가 큐 역할을 할 수 있다(R7).

## R4. 도착·출발 확인·자동 확정 알림 생성 지점(F006/F007 연결)

- **Decision**: `api/app/services/detection/__init__.py`(변수 감지가 아니라 **진행 전환** `DetectionService`)에 hook을 세 곳 건다.
  1. `register_event(...)`가 `ProgressTransition(status="PENDING_CONFIRMATION")`를 만든 직후 → `NotificationService.create_transition_check(session, transition)` (`ARRIVAL_CHECK`/`DEPARTURE_CHECK`, `prompt_seq=1`).
  2. `_auto_confirm(day, transition)`가 `status="AUTO_CONFIRMED"`로 바꾼 직후 → `create_transition_auto_confirmed(session, transition)`.
  3. `decide_transition(...)`의 `NOT_ARRIVED` 분기에서 `next_prompt_at`을 계산할 때 → 재질문은 즉시 만들지 않고, `progress_transitions.auto_finalize_at`처럼 시각만 남은 상태를 R7 dispatch가 발견해 `ARRIVAL_CHECK prompt_seq=2` 행을 만든다.
  전부 해당 transaction 안에서 행만 만들고, FCM 발송은 커밋 후 dispatch가 한다.
- **Rationale**: `register_event`는 요청 경로라 확인 알림을 30초 안에 만들 수 있다(SC-012). `finalize_due_candidates`/`_auto_confirm`은 **요청이 올 때만 실행되는 지연 확정**(`ProgressService.get_day`·`update_item_status`·`register_event`·`decide_transition` 시작 시 호출)이므로, 자동 확정·재질문 알림의 적시성은 R7 dispatch가 `finalize_due_candidates`를 주기 호출해 보장한다. 시각 상수(300m·5분·10분·최대 2회·400m)는 `DetectionService` 상수와 `requirements.md` PROG-04~PROG-11을 그대로 따르고 F011은 재정의하지 않는다.
- **F006/F007 파일 수정**: `services/detection/__init__.py` 3곳에 hook 호출 추가(전환 규칙·응답 불변), `finalize_due_candidates`를 F011 dispatch가 호출(idempotent, 이미 4개 요청 경로에서 호출됨). F006/F007 BE 담당 review.
- **Alternatives**: 앱이 확인 후보를 폴링으로만 확인 — 앱이 백그라운드면 못 봄(spec US3 핵심). 확인 알림도 dispatch에만 맡김 — 후보 생성~발송 지연이 커져 SC-012 위험.

## R5. FCM 전송 클라이언트

- **Decision**: `api/app/clients/fcm.py`에 `FcmClient(settings, client: httpx2.AsyncClient | None = None)`를 만들고 **FCM HTTP v1 API**(`POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`)를 직접 호출한다. 서비스 계정 인증은 `pyjwt`(이미 의존성)로 RS256 JWT를 만들어 `https://oauth2.googleapis.com/token`에서 access token을 교환하고 ~55분 캐시한다. 메시지는 **data-only**(`message.token`, `message.data` 문자열 맵). 결과는 `FcmSendResult` = `OK` / `INVALID_TOKEN`(`UNREGISTERED`·`INVALID_ARGUMENT` 중 토큰 오류) / `RETRYABLE`(`UNAVAILABLE`·`INTERNAL`·429) / `FATAL`. 오류 타입 `FcmClientError(code, retryable, status_code)`는 `TourApiClientError` 패턴을 따른다.
- **Rationale**: 기존 클라이언트가 전부 `httpx2` 얇은 래퍼이고 `firebase-admin`·`google-auth`는 `pyproject.toml`에 없다. HTTP v1 + `pyjwt`면 새 무거운 의존성 없이 같은 패턴으로 만든다. data-only여야 앱이 payload를 검사해 재조회 식별자만 담았음을 보장하고(FR-006, SC-004) 포그라운드에서 자동 표시를 막는다(FR-029).
- **의존성 확인**: RS256 서명에는 `pyjwt`의 `[crypto]` extra(`cryptography`)가 필요하다. `core/security.py`가 HS256만 쓰면 미설치일 수 있으니 tasks 첫 BE 작업에서 확인하고, 없으면 `pyjwt[crypto]`(가벼운 표준 라이브러리)만 추가한다. 그래도 `firebase-admin`은 도입하지 않는다.
- **설정**(`api/app/core/config.py` `Settings` + `.env.example`): `fcm_enabled: bool = False`, `fcm_project_id: str | None`, `fcm_service_account_json: SecretStr | None`(또는 경로), `fcm_request_timeout_seconds: float = 5.0`. `fcm_enabled=False`면 발송을 건너뛰고 `notification.sent_at`을 현재 시각으로 찍은 뒤 `fcm_skipped`를 log한다(로컬·CI에서 자격 없이 동작).
- **Alternatives**: `firebase-admin` SDK — 표준이지만 `uv.lock` 갱신과 큰 의존성 트리, 기존 얇은 클라이언트 패턴과 이질적. Legacy FCM(`/fcm/send` 서버 키) — 2024 지원 종료.

## R6. 재시도와 무효 토큰 처리

- **Decision**: 발송 단위는 `NotificationDispatchService.send_one(notification)`. (1) `notification.user_id`의 활성 기기 토큰(`device_sessions` where `revoked_at IS NULL AND fcm_token IS NOT NULL`)을 모은다. (2) 토큰이 0개면 `sent_at=now`로 찍고 종료(FR-007). (3) 각 토큰에 `FcmClient.send`. `RETRYABLE`이면 `0.5s → 1.5s` 백오프로 최대 2회 재시도(총 3회, FR-027). (4) `INVALID_TOKEN`이면 그 `device_sessions.fcm_token`만 `NULL`로(FR-008). (5) 모든 토큰 처리가 끝나면 `sent_at=now`로 찍는다(성공·최종 실패 공통 = "종단 발송 시도 완료"). 최종 실패는 `notification_delivery_failed`를 request-id·유형·대상 id·기기 수와 함께 log하되 토큰 원문·본문은 남기지 않는다(FR-024).
- **Rationale**: spec Clarifications 2026-09-10, constitution IV. `notifications` 테이블에 발송 상태 컬럼이 없고 `notification_deliveries`가 제외됐으므로(`er-schema.md` 14절) `sent_at`을 "종단 시도 시각"으로 쓴다. 한 토큰의 실패가 다른 토큰·행 커밋을 막지 않게 토큰 루프를 격리한다(FR-008·FR-009).
- **Alternatives**: `send_attempts` 컬럼 추가 — 스키마 확장 대비 이득이 작다(MVP는 이력화 안 함). 무제한 재시도 큐 — dispatch 주기가 이미 재시도 역할(다음 tick에 `sent_at IS NULL`을 다시 집음). 하지만 최종 실패를 영구 재시도하면 tick마다 낭비 → `sent_at`을 찍어 종료.

## R7. 배경 실행 모델 — `notification_dispatch` job

- **Decision**: `api/app/jobs/notification_dispatch.py`에 `run_notification_dispatch(session_factory, *, interval_seconds=30)` asyncio 루프를 새로 만들고 `main.py` `lifespan`에서 `asyncio.create_task`로 띄운다(기존 `run_auth_cleanup`·`run_variable_detection`과 같은 패턴). 매 tick:
  1. `IN_PROGRESS`인 `trip_days`에 대해 `DetectionService(session).finalize_due_candidates(day)`를 호출해 만료된 무응답 후보를 제때 자동 확정하고(→ R4 hook 2가 `ARRIVAL_AUTO_CONFIRMED` 행 생성), `next_prompt_at <= now`인 후보에 `ARRIVAL_CHECK prompt_seq=2` 행을 만든다.
  2. `notifications WHERE sent_at IS NULL ORDER BY created_at LIMIT 200`을 읽어 각 행에 `send_one`(R6).
  `notifications` 테이블 자체가 발송 큐다(`sent_at IS NULL` = 미발송).
- **동기 경로와의 관계**: `register_event`(확인 알림)와 10분 감지 루프(장소 변경 제안)는 행을 만든 뒤 `BackgroundTasks`(요청 경로) 또는 루프 커밋 직후 한 번 즉시 `send_one`을 시도해 지연을 30초 밑으로 낮춘다. dispatch tick은 즉시 발송이 실패·누락됐을 때의 안전망이자 자동 확정·재질문의 적시 발송 담당이다.
- **Rationale**: 자동 확정·재질문은 F006/F007이 **지연(요청 시)** 설계라, 앱이 백그라운드면 알림이 늦는다. 30초 tick이 `finalize_due_candidates`(idempotent)를 대신 호출해 적시성을 준다. 새 스케줄러 프레임워크 없이 기존 asyncio 루프 패턴을 재사용한다. 단일 worker 전제(`main.py` 주석)와 일치.
- **F006/F007 협의 필요**: dispatch가 `finalize_due_candidates`를 호출하면 자동 확정이 "다음 요청" 대신 "마감 후 ~30초"에 기록된다(되돌리기 창은 `auto_finalize_at` 기준이라 불변, 사용자에게 유리). 이는 F006/F007 동작 시점 변경이므로 해당 담당 review로 확정한다(plan Constitution Check 교차 계약 review).
- **Alternatives**: APScheduler·celery-beat 도입 — 저장소에 스케줄러 인프라가 없고 단일 loop로 충분. 10분 감지 루프에 얹기 — 5분 되돌리기 창에 10분 granularity는 너무 굵다. 자동 확정 알림을 지연 그대로 두기 — spec US3·Figma가 요구하는 "앱 밖 통지"가 깨진다.

## R8. 알림 목록 조회·읽음·보존

- **Decision**:
  - NOTI-001 `GET /api/v1/notifications?cursor&limit&read` — `notifications` where `user_id=principal AND created_at >= now - interval '90 days'`, `read`가 오면 `read_at IS NULL`/`NOT NULL`로 필터, `(created_at desc, notification_id desc)` cursor. 항목: `notificationId, type, tripId?, tripDayId?, itemId?, detectionId?, transitionId?, title, body, read(=`read_at is not null`), createdAt`.
  - NOTI-002 `PATCH /api/v1/notifications/{id}/read` — 소유 검증 후 `read_at = coalesce(read_at, now)` → `{notificationId, read: true}`. 멱등.
  - **NOTI-003 `PATCH /api/v1/notifications/read-all`**(신설) — `UPDATE notifications SET read_at=now WHERE user_id=principal AND read_at IS NULL` → `{updated: n}`. FR-014 "모두 읽음", Figma 헤더 버튼. api-spec 2·9절에 추가(doc sync).
  - 보존: 조회에서 90일 초과 제외 + `jobs/notification_cleanup.py`(`run_notification_cleanup`, `cleanup_notifications(session, *, now=None)`)가 `auth_cleanup.py` 패턴대로 `delete(Notification).where(created_at < now - 90d)`와 `delete` of `trip_id`가 논리 삭제된 여행을 가리키는 행을 지운다. `lifespan`에 네 번째 `create_task`.
- **Rationale**: spec FR-013·FR-014·FR-016·FR-030, Clarifications 2026-09-10. cursor·envelope·소유권은 `api-spec.md` 1절과 기존 목록 API를 따른다. 대상(여행·감지·전환)이 지워져도 목록 조회는 FK가 nullable이고 LEFT 조인이 없어 실패하지 않는다(FR-016); 앱이 열 때 대상 없음을 안내한다.
- **동기화**: `api-spec.md` 2절(NOTI-001·002 `[x]`, NOTI-003 행 추가), 9절(NOTI-001 응답 필드·90일 문구, NOTI-003 신설), `er-schema.md` 1절(알림 90일 보존 문구).
- **Alternatives**: 읽음 배지 카운트 엔드포인트 — Figma에 숫자 배지가 없어 불필요(YAGNI). 보존 무기한 — constitution V 위반, `trips` 논리 삭제와 정합 안 맞음.

## R9. 기기 토큰 등록·해제(DEV-001·DEV-002)

- **Decision**:
  - DEV-001 `PUT /api/v1/devices/fcm-token` body `{deviceId, fcmToken, platform}` → 활성 `device_sessions`(`user_id=principal AND client_device_id=deviceId AND revoked_at IS NULL`)의 `fcm_token`을 갱신, `{deviceId, registered: true}`. 0행이면 `404 DEVICE_SESSION_NOT_FOUND`(로그인으로 세션이 먼저 생긴다는 전제, subagent §2). 같은 토큰이 다른 세션에 있으면 R10의 partial unique로 그 세션 값을 먼저 `NULL`로 밀어낸다(토큰 이동). PUT이라 멱등(FR-010).
  - DEV-002 `DELETE /api/v1/devices/{deviceId}/fcm-token` → 그 세션의 `fcm_token`을 `NULL`, `204`. 로그아웃(`services/auth.py::logout_device_session`)의 `update(...).values(...)`에도 `fcm_token=None`을 추가해 앱이 DEV-002를 못 부르고 로그아웃해도 정리되게 한다(FR-011).
- **Rationale**: spec FR-010~FR-012, `api-spec.md` 9절, `er-schema.md` 4.2. 세션 행에 컬럼이 이미 있어 새 테이블이 없다. 소유권은 `client_device_id`가 principal의 세션인지로 검증(FR-012).
- **F001 파일 수정**: `services/auth.py::logout_device_session` 1줄(`fcm_token=None` 추가). 외부 동작 불변, F001 담당 review.
- **Alternatives**: 별도 `devices` 테이블 — `er-schema.md` 14절이 `device_sessions`로 통합하기로 함. 토큰 없는 상태에서 DEV-001이 세션을 만들기 — 인증·기기 등록 책임이 섞임, F001 계약 침범.

## R10. Migration

- **Decision**: 새 파일 `api/migrations/versions/009_create_notifications.py`(`down_revision = "008_create_detections"`).
  - `notifications` 테이블: `er-schema.md` 9.1 컬럼 그대로 + `type`에 `CHECK (type IN (...5개...))`, FK는 `users`(`ON DELETE CASCADE`), `trips`·`trip_days`·`itinerary_items`·`detections`·`progress_transitions`(`ON DELETE SET NULL`, 전부 nullable).
  - 인덱스: `ix_notifications_user_unread (user_id, read_at, created_at DESC)`, `ix_notifications_retention (created_at)`, partial unique `uq_notifications_dedup (user_id, dedup_key) WHERE dedup_key IS NOT NULL`.
  - `device_sessions`: `er-schema.md` 12절이 요구하지만 아직 없는 partial unique `uq_device_sessions_fcm_token (fcm_token) WHERE fcm_token IS NOT NULL AND revoked_at IS NULL` 추가.
  - `api/migrations/env.py`의 모델 import 목록에 `notification` 추가(현재 `auth, itinerary, progress, trip`만).
  - 새 모델 `api/app/models/notification.py`(`Notification`), `models/__init__.py` 재노출에 추가.
- **Rationale**: subagent §7. hand-written 스타일(explicit `op.create_table` + `op.create_index(postgresql_where=...)`)을 `008`처럼 따른다.
- **Alternatives**: autogenerate — 프로젝트가 hand-written만 씀. `users.replacement_suggestion_enabled`·`device_sessions.fcm_token` 재생성 — 이미 `001`에 있음.

## R11. Android — FCM 설정과 수신

- **Decision**: 새 패키지 `com.gilpick.notification`.
  - Gradle: `android/build.gradle.kts`에 `com.google.gms.google-services` 플러그인, `android/app/build.gradle.kts`에 `firebase-bom` + `firebase-messaging`, `apply(plugin = "com.google.gms.google-services")`. `android/app/google-services.json`은 `.gitignore`(G001 Firebase 프로젝트에서 주입). `AndroidManifest.xml`에 `POST_NOTIFICATIONS` 권한과 `GilpickMessagingService`(`com.google.firebase.MESSAGING_EVENT`) 등록. 알림 채널 `gilpick_notifications`는 `MainActivity` 첫 진입 또는 신설 `GilpickApplication.onCreate`에서 생성.
  - `GilpickMessagingService : FirebaseMessagingService`: `onNewToken(token)` → `FcmTokenSyncWorker`(durable, `SessionRevocationWorker` 패턴) enqueue → DEV-001. `onMessageReceived(msg)`(data-only): 앱이 포그라운드가 아니면 채널 알림 + `PendingIntent`(→ `MainActivity`, extras `notif_type`·`trip_id`·`detection_id`·`transition_id`), 포그라운드면 시스템 알림도 인앱 표시도 하지 않는다(FR-029; 선택적으로 목록 새로고침 신호만).
  - DEV-001 hook: `com.gilpick.auth.AuthRepository`의 `onSignedIn` 성공 후와 `performRefresh` 성공 후 `FcmTokenSyncWorker.enqueue(context)`(FR-020). DEV-002: `AuthRepository.logout()` → `FcmTokenClearWorker` enqueue + best-effort `FirebaseMessaging.getInstance().deleteToken()`.
- **Rationale**: subagent §12·§15. 토큰 등록·해제는 오프라인에도 견뎌야 하므로 `WorkManager`(이미 의존성) durable worker로, 인증 흐름의 유일 진입점 `AuthRepository`에 건다.
- **Alternatives**: 로그인 성공 화면에서 직접 DEV-001 호출 — 오프라인·프로세스 종료에 취약. notification message(자동 표시) — payload에 본문이 실려 FR-006·SC-004 위반, 포그라운드 억제 불가.

## R12. Android — 알림 목록·감지 목록 화면과 딥링크

- **Decision**: `com.gilpick.notification`에 `NotificationApi.kt`(`createNotificationRetrofit`, `NotificationService`: NOTI-001/002/003, DEV-001/002), `NotificationRepository.kt`(`NotificationRepository(api, auth)` + `.default(context)`), `NotificationUiState.kt`, `NotificationListViewModel.kt`, `NotificationListScreen.kt`(Figma `NotificationsScreen`), `NotificationLabels.kt`(KST 날짜 그룹 `오늘`/`어제`/`그 이전`, 상대 시각, 유형→아이콘·목적지), `NotificationNavigation.kt`(`NotificationListRoute`, `VariableMonitorRoute`, `notificationGraph(navController, onSessionExpired, onOpenDetection, onOpenProgress)`).
  - 감지 목록 화면(US6): 같은 패키지에 `VariableMonitorScreen.kt` + `VariableMonitorViewModel.kt`. 데이터는 F009 `com.gilpick.alternative.AlternativeRepository.listDetections(status=ACTIVE)`를 재사용한다(읽기 전용, F009 담당 jy review). "대체 장소 보기" → `onOpenDetection(detectionId, tripId)` → 기존 `AlternativePlacesRoute`.
  - 딥링크: `MainActivity`(`singleTask`)의 `onCreate`/`onNewIntent`에서 notification extras를 파싱해 `pendingNotificationTarget` 상태에 담고, `NavHost` 구성 후 `LaunchedEffect`가 유형에 따라 `AlternativePlacesRoute(detectionId, tripId)`(장소 변경 제안) 또는 `ActiveTravelRoute(tripId, tripName)`(도착·출발·자동 처리·재질문)로 이동한 뒤 화면이 서버 상태를 재조회한다(FR-018·FR-019). `AlternativePlacesRoute` KDoc이 이미 "F011 푸시 진입도 같은 route"라고 전제.
  - 진입점: `trip/TripListScreen.kt`와 `progress/ActiveTravelScreen.kt`의 `Header`에 알림 벨 아이콘 + `onNotifications` 파라미터 추가 → `navController.navigate(NotificationListRoute)`. F002·F006 파일 수정이라 각 담당(hs·jy) review.
- **Rationale**: subagent §11·§14·§16·§17. 기존 feature 패키지 형태(`Api/Repository/ViewModel/UiState/Navigation/Screen/Labels`)와 type-safe route·`xxxGraph` 패턴을 그대로 따른다. `GilpickColors.surfaceTint`(읽지 않은 알림 배경)·`GilpickSizing.groupDot`(그룹 헤더 점)이 이미 있다.
- **Alternatives**: 감지 목록 화면을 `com.gilpick.alternative`에 추가 — F009 패키지 비대(이미 10파일), 소유 경계 흐림. `NavController.handleDeepLink` + `<nav-graph>` 딥링크 — 저장소에 전례가 없고 auth App Link만 intent-filter가 있음, extras 방식이 더 단순.

## R13. 화면 상태·문구·접근성

- **Decision**:
  - `NotificationUiState` = `Loading`(1초 지연 표시) / `Empty`(받은 알림 0) / `Error`(조회 실패, 다시 시도·돌아가기) / `Content(groups: List<NotifGroup>, refreshing)`. `NotifGroup` = 날짜 구간 라벨 + 항목들. 새로고침 중 기존 목록 유지.
  - 안 읽음 = `surfaceTint` 배경 + `groupDot` 크기 점(색 단독 금지, UI-002). "모두 읽음"은 헤더 아이콘 버튼 + `contentDescription`.
  - 항목 탭 → NOTI-002 읽음 처리 → 유형별 목적지. 대상 없음(여행 논리 삭제·감지 무효화)은 이동한 화면이 문구로 안내(FR-016, UI-003).
  - `VariableMonitorScreen` 상태 = `Content`(시간순·위험순 정렬 토글) / `Empty`(Figma `hasAlerts=false`: "모든 일정이 예정대로예요" + 진행 화면으로) / `Error`. 변수 제외 항목은 사유 문구, 없는 값 안 지어냄(UI-008).
  - 문구: 진행 알림은 `~해요` 상태·행동 문장, 장소 변경 제안은 방문 판단 문장(ui-guidelines 8절). 본문은 서버(`notifications.title`·`body`)가 만든 값을 그대로 표시.
  - 접근성: 행·아이콘 버튼 터치 48dp·간격 8dp, 360dp·fontScale 2.0에서 잘림 없음(`weight(1f)`+줄바꿈), `statusBarsPadding`/`navigationBarsPadding`.
- **Rationale**: spec UI-001~UI-008, ui-guidelines 8·9·10절, F009 화면이 쓴 상태 모델과 동일 계열. 공용 list-row/empty/error 컴포넌트가 없어(subagent §17) `com.gilpick.notification` 내부 composable로 4상태를 직접 만든다(F009도 동일).
- **Alternatives**: 공용 `EmptyState`/`ErrorState`를 `ui/component`로 승격 후 재사용 — 이번 범위를 넘고 F009·기타 화면 회귀 위험. F011 안에서만 만들고 필요하면 후속에 승격.

## R14. 서버 본문 문구 생성

- **Decision**: `NotificationService`가 유형별 `title`·`body`를 서버에서 만든다.
  - 장소 변경 제안: `title="다음 장소 변경을 추천해요"`, `body`는 감지 `reason`(DETECT-002가 이미 쓰는 문구, 예 "도착 시각에 영업이 어렵거나 곧 문을 닫아요")에 장소명을 붙인다.
  - 도착 확인: `title="도착하셨나요?"`, `body="{장소명} 근처에 머물고 있어요."`.
  - 출발 확인: `title="다음 장소로 이동하셨나요?"`, `body="{장소명}에서 나온 것 같아요."`.
  - 자동 도착/출발: `title="도착으로 자동 처리했어요"`/`"출발로 자동 처리했어요"`, `body`는 발송 시점의 남은 되돌리기 시간을 계산(`undo_deadline - now`)해 "N분 안에 되돌릴 수 있어요", 만료면 "되돌리기 시간이 지났어요".
  - 재질문: `title="아직 도착 안 하셨나요?"`, `body="{장소명} 도착을 다시 확인해요."`.
  본문에 좌표·평점·정밀 위치·후보 목록을 넣지 않는다(FR-006).
- **Rationale**: FCM payload는 재조회 식별자만이라(FR-006) 앱이 목록·시스템 알림에 보여줄 문구가 서버에 있어야 한다(`notifications.title`·`body` NOT NULL, `er-schema.md` 9.1). Figma `NotificationsScreen` 예시 문구를 기준으로 한다.
- **Alternatives**: 앱이 유형+식별자로 문구를 재구성 — 목록 조회 없이 시스템 알림을 못 만들고 문구 정본이 둘로 갈라짐.

## R15. 테스트 전략

- **BE**:
  - unit: `tests/unit/test_notification_service.py`(유형별 행 생성·`dedup_key`·설정 off 억제·본문 문구·되돌리기 시간 계산), `test_fcm_client.py`(HTTP v1 요청 형태·OAuth2 JWT·결과 분류, `httpx2` fake + `tests/fixtures/fcm/*.json`), `test_notification_dispatch.py`(미발송 큐 처리·재시도 2회·무효 토큰 null·0기기 처리·`finalize_due_candidates` 연동).
  - contract: `tests/contract/test_notification_contract.py`(`specs/011-notification/contracts/notifications.openapi.yaml` vs `create_app().openapi()`, NOTI-001/002/003·DEV-001/002, 401/403/404).
  - integration: `tests/integration/test_notification_cleanup.py`(`test_auth_cleanup.py` 패턴, 90일·논리 삭제 여행), `test_notification_migration.py`(009 up/down, 인덱스·CHECK).
  - 회귀: `tests/contract/test_detections_contract.py`·진행 관련 기존 테스트가 hook 추가 후에도 통과.
- **Android**:
  - unit: `NotificationListViewModelTest`(그룹핑·읽음·모두 읽음·오류·새로고침 유지), `NotificationRepositoryTest`(NOTI/DEV 매핑, 401 재시도), `VariableMonitorViewModelTest`(정렬·빈 상태·이동값), `FcmTokenSyncWorkerTest`.
  - androidTest: `NotificationListScreenTest`·`VariableMonitorScreenTest`(상태·탭 이동·접근성), `NotificationNavigationTest`(딥링크 extras → route), `NotificationsScreenshotTest`·`VariableMonitorScreenshotTest`(content·empty·error × 기본·360dp/fontScale2).
  - 실기기·실FCM: quickstart.md AND 5(G001 Firebase 프로젝트 + `google-services.json`).
- **Rationale**: subagent §10·§18. 외부 연동은 `httpx2` fake·fixture, 시각 고정. FCM 실발송은 G001 의존.
