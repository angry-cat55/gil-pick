# Quickstart: F011 알림 검증

구현이 끝난 뒤 이 순서로 확인한다. Backend는 자동 test(FCM은 `httpx2` mock transport, 시각 고정)로, Android는 unit·androidTest·screenshot으로 검증하고, 실제 FCM 전달은 G001 Firebase 프로젝트와 `google-services.json`으로 마지막에 확인한다.

## 사전 조건

- `docker compose up -d postgres` → `cd api && alembic upgrade head`(migration `011_create_notifications` 포함) → `uvicorn app.main:app`.
- 환경변수: `JWT_SIGNING_SECRET` 필수. FCM은 `FCM_ENABLED=false`(기본)면 발송을 건너뛰고 `notifications.sent_at`만 찍는다. 실발송은 `FCM_ENABLED=true` + `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT_JSON`(G001).
- 오늘 날짜(Asia/Seoul)에 장소가 2곳 이상이고 `오늘 여행 시작`을 끝낸 여행이 필요하다(진행 알림용). 장소 변경 제안 알림은 남은 장소 하나에 F008 `ACTIVE` 감지가 새로 생기게 한다(그 장소 `estimated_arrival_at`을 폐점 이후로 두고 `evaluate_all_active` 1회 실행 — F008 quickstart BE 1).
- 계약: [contracts/notifications.openapi.yaml](contracts/notifications.openapi.yaml). 데이터·상태 모델: [data-model.md](data-model.md). 결정 근거: [research.md](research.md).

## 자동 검증

```bash
# Backend (api/)
.venv/Scripts/python.exe -m pytest \
  tests/unit/test_notification_service.py \
  tests/unit/test_fcm_client.py \
  tests/unit/test_notification_dispatch.py \
  tests/contract/test_notification_contract.py \
  tests/integration/test_notification_cleanup.py \
  tests/integration/test_notification_migration.py \
  tests/integration/test_notification_detection_hook.py \
  tests/contract/test_detections_contract.py     # hook 추가 후 회귀
# Android (android/)
gradlew.bat --offline -q :app:testDebugUnitTest :app:assembleDebug
gradlew.bat --offline :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.notification
```

`tests/contract/test_auth_contract.py`의 기존 실패는 F011과 무관하다(origin/main 기준).

## Backend 시나리오

### BE 1. 장소 변경 제안 알림 생성 (US2, FR-001·FR-002·FR-003, SC-001)

1. 설정 on 사용자에서 `_store_detection`이 감지를 **처음 INSERT**하면 `notifications` 1행(`type=PLACE_CHANGE_SUGGESTION`, `detection_id`·`trip_id` 채움, `dedup_key='detection:{id}'`, `sent_at=NULL`)이 생기는지 확인한다.
2. 같은 감지가 재평가로 **갱신**(`on_conflict_do_update`)되면 새 행이 생기지 않는지 확인한다.
3. `users.replacement_suggestion_enabled=false`면 감지는 생성되지만 알림 행은 생기지 않는지 확인한다(감지 생성·조회 불변).
4. `DISMISSED`·`INVALIDATED` 감지를 다시 평가해도 알림이 생기지 않는지 확인한다(FR-005).

### BE 2. 도착·출발 확인·자동·재질문 알림 생성 (US3, FR-001·FR-002·FR-004·FR-005)

1. `DetectionService.register_event`가 `PENDING_CONFIRMATION` `ARRIVAL` 전환을 만들면 `notifications`에 `type=ARRIVAL_CHECK`, `dedup_key='transition:{id}:arrival_check:1'`, `transition_id`·`item_id` 채운 1행이 생기는지 확인한다. `EXIT`면 `DEPARTURE_CHECK`.
2. `finalize_due_candidates`가 무응답 후보를 `AUTO_CONFIRMED`로 바꾸면 `ARRIVAL_AUTO_CONFIRMED`(또는 `DEPARTURE_...`) 1행이 생기고 본문에 되돌리기 남은 시간이 들어가는지 확인한다. `undo_deadline` 이후면 "되돌리기 시간이 지났어요".
3. `decide_transition(NOT_ARRIVED)` 후 `next_prompt_at`이 지나면 dispatch가 `ARRIVAL_CHECK` `prompt_seq=2` 1행을 만들고, 세 번째 재질문은 만들지 않는지(최대 2회) 확인한다.
4. `아직 머무는 중`으로 자동 출발 감지가 멈춘 장소, 당일 완료된 날짜에는 추가 확인 알림이 생기지 않는지 확인한다(FR-005, SC-011).
5. 설정 off 사용자여도 1~3의 진행 알림은 그대로 생기는지 확인한다(FR-004, SC-003).
6. COMPOSITE 자동 확정(이전 완료 + 다음 도착)은 알림 1건인지 확인한다(Edge Cases).

### BE 3. FCM 발송·재시도·무효 토큰 (FR-006~FR-009·FR-027, SC-004·SC-005)

1. `FcmClient` mock이 성공을 주면 `NotificationDispatchService.send_one`이 사용자 활성 기기 토큰 전부에 data-only 메시지(payload에 재조회 식별자 + `title`·`body`만, 좌표·평점·토큰 없음)를 보내고 `sent_at`을 찍는지 확인한다.
2. mock이 `UNAVAILABLE`을 2회 주고 3회차 성공이면 재시도 2회 후 성공, 3회 모두 실패면 `sent_at`을 찍고 `notification_delivery_failed`를 log(토큰·본문 없이)하는지 확인한다.
3. mock이 `UNREGISTERED`를 주면 그 `device_sessions.fcm_token`만 `NULL`이 되고 다른 토큰 전송·행 커밋은 영향 없는지 확인한다.
4. 활성 기기 토큰이 0개면 실패가 아니라 `sent_at`만 찍히고 목록 조회에는 남는지 확인한다(FR-007).
5. `FCM_ENABLED=false`면 발송을 건너뛰고 `sent_at`을 찍으며 행은 정상인지 확인한다.
6. FCM 호출 실패가 감지·전환 transaction을 롤백하지 않는지(발송은 커밋 후) 확인한다.

### BE 4. dispatch job (research R7)

1. `notifications WHERE sent_at IS NULL`을 tick이 큐로 읽어 `send_one`을 부르고, 처리된 행은 다음 tick에 다시 잡히지 않는지 확인한다.
2. tick이 `IN_PROGRESS` `trip_days`에 `finalize_due_candidates`를 호출해 만료 후보가 요청 없이도 자동 확정되고 그 자동 확정 알림이 생성·발송되는지 확인한다(idempotent 재호출 안전).
3. 동기 즉시 발송(요청 경로 `BackgroundTasks`)이 성공하면 tick이 그 행을 재발송하지 않는지 확인한다.

### BE 5. NOTI-001·002·003 (US4, FR-013~FR-016, SC-007·SC-008)

1. 오늘 2건·어제 1건·91일 전 1건이 있으면 `GET /api/v1/notifications`가 90일 이내 3건만 최신순으로, `meta.pagination`과 함께 반환하는지 확인한다.
2. `?read=false`가 안 읽은 것만, `?read=true`가 읽은 것만 반환하는지 확인한다.
3. `PATCH /notifications/{id}/read` → `{read:true}`, 재요청도 `200` 동일(멱등). 다른 사용자 알림이면 `403`/`404`.
4. `PATCH /notifications/read-all` → `{updated:n}`, 재요청은 `{updated:0}`.
5. 알림이 가리키는 여행을 논리 삭제하거나 감지를 `INVALIDATED`로 만든 뒤에도 NOTI-001이 `200`으로 그 알림을 포함해 반환하는지 확인한다(FR-016).
6. 다른 사용자 토큰으로 NOTI-001/002/003 호출 시 목록은 그 사용자 것만, 단건은 `403`/`404`.

### BE 6. DEV-001·DEV-002·로그아웃 (US1, FR-010~FR-012, SC-006)

1. 로그인된 기기로 `PUT /api/v1/devices/fcm-token` → `{registered:true}`, `device_sessions.fcm_token` 저장. 다른 값으로 재요청 → 같은 세션 행 갱신, 세션 수 불변.
2. 같은 토큰을 다른 세션에 `PUT`하면 이전 세션의 `fcm_token`이 `NULL`로 밀려나는지(partial unique) 확인한다.
3. 해당 기기의 활성 세션이 없으면 `404 DEVICE_SESSION_NOT_FOUND`.
4. `DELETE /api/v1/devices/{deviceId}/fcm-token` → `204`, 그 세션 토큰 `NULL`, 다른 기기 토큰 불변.
5. `POST /api/v1/auth/logout`(AUTH-005) 후 그 세션의 `fcm_token`도 `NULL`인지 확인한다.
6. 다른 사용자 `deviceId`로 DEV-002 → `403`/`404`.
7. 두 기기에 토큰이 있으면 발송이 두 기기 모두를 대상으로 하는지 확인한다(BE 3와 연계).

### BE 7. 90일 정리 job (FR-030, SC-014)

1. `cleanup_notifications(session, now=T)`가 `created_at < T - 90d` 행과 `trip_id`가 논리 삭제된 여행을 가리키는 행을 지우고 그 외는 남기는지 `test_notification_cleanup.py`에서 확인한다(`test_auth_cleanup.py` 패턴).
2. `run_notification_cleanup` 루프가 `lifespan`에서 뜨고 예외가 cycle 단위로 격리되는지 확인한다.

### BE 8. Migration 011 (data-model 1)

1. `alembic upgrade head` → `alembic downgrade -1` → `alembic upgrade head` 왕복이 되고, `notifications` 테이블·인덱스 4개·`type` CHECK·`uq_device_sessions_fcm_token`가 생기는지 `test_notification_migration.py`에서 확인한다.
2. `migrations/env.py` 모델 import에 `notification`이 있어 `Base.metadata`에 테이블이 잡히는지 확인한다.

## Android 시나리오

### AND 1. 알림 목록 화면 상태 (US4, UI-001·UI-002·UI-004, SC-010)

1. fake repository로 오늘 2건(안 읽음 1)·어제 1건 → "오늘"/"어제" 그룹, 각 최신순, 안 읽은 행은 `surfaceTint` 배경 + `groupDot` 점, 제목·본문·상대 시각이 보이는지 `NotificationListScreenTest`로 확인한다.
2. 0건 → 빈 상태(아이콘 상자·제목·설명). 조회 실패 → 오류 상태 + `다시 시도하기` + 돌아가기, 재시도 시 재조회.
3. 1초 미만 로딩엔 대기 표시 없음, 재조회 중 기존 목록 유지.
4. 헤더 "모두 읽음" 탭 → NOTI-003 호출, 로컬 목록이 모두 읽음 표시로 바뀌는지 확인한다.

### AND 2. 탭 이동·읽음·대상 없음 (US4, FR-016·FR-018, SC-009)

1. `PLACE_CHANGE_SUGGESTION` 행 탭 → NOTI-002 호출 후 `onOpen(Alternative(detectionId, tripId))`; `ARRIVAL_CHECK`/자동/재질문 탭 → `onOpen(Progress(tripId, tripName))` (`NotificationNavigationTest`).
2. 대상 여행이 없는(논리 삭제) 알림 탭 → 이동한 화면이 "더 이상 볼 수 없는 대상" 문구를 보이고 목록으로 돌아갈 수 있는지 확인한다.
3. 3탭 이하로 목록→대상 화면 도달(SC-009).

### AND 3. FCM 수신·딥링크 (US2·US3, FR-006·FR-019·FR-029)

1. `GilpickMessagingService.onMessageReceived`에 data-only 메시지(식별자 + `title`·`body`)를 주고, 앱이 백그라운드면 채널 알림 + `PendingIntent`가 생기는지, 포그라운드면 시스템 알림도 인앱 배너도 생기지 않는지 확인한다(FR-029).
2. 알림 탭 → `MainActivity` extras 파싱 → `PendingNotificationTarget` → `type`에 따라 `AlternativePlacesRoute` 또는 `ActiveTravelRoute`로 이동, 이동 후 화면이 서버 상태를 재조회(FR-019)하는지 `NotificationNavigationTest`로 확인한다.
3. `onNewIntent`(앱 실행 중 다른 알림 탭)도 같은 경로인지 확인한다.

### AND 4. 기기 토큰 등록·해제 (US1, FR-020)

1. `AuthRepository.onSignedIn` 성공 → `FcmTokenSyncWorker`가 enqueue되고 DEV-001을 호출하는지(`FcmTokenSyncWorkerTest`). `performRefresh` 성공에도 enqueue.
2. `AuthRepository.logout()` → `FcmTokenClearWorker` enqueue + `FirebaseMessaging.deleteToken()` best-effort, 네트워크 없으면 재시도(백오프)로 견디는지 확인한다.
3. 푸시 권한이 없거나 토큰 획득 실패여도 로그인·다른 기능이 정상인지 확인한다.

### AND 5. 감지 목록 화면 (US6, UI-008)

1. fake로 `ACTIVE` 감지 3건 → 목록에 장소명·요약·변수별 수준·방문 예정 시각·감지 경과, 시간순↔위험순 토글, "대체 장소 보기" → `onOpenDetection(detectionId, tripId)` (`VariableMonitorScreenTest`).
2. 변수 하나가 데이터 없음으로 제외된 항목은 사유 문구가 보이고 없는 값을 지어내지 않는지 확인한다.
3. `ACTIVE` 감지 0건 → "모든 일정이 예정대로예요" 빈 상태 + 진행 화면으로.

### AND 6. Screenshot (UI-007)

1. `NotificationsScreenshotTest`: `content`(읽음·안 읽음·2그룹 혼재)·`empty`·`error` × 기본 배율·360dp/fontScale2 = 6장.
2. `VariableMonitorScreenshotTest`: `content`·`empty` × 2배율 = 4장.
3. `adb pull`로 꺼내 Figma `NotificationsScreen`·`VariableMonitorScreen`과 사람이 대조.

### AND 7. 진입점 (UI, research R12)

1. `TripListScreen` 헤더 벨 탭 → `onNotifications` → `NotificationListRoute` 이동(`TripListScreen`이 F002 파일이므로 변경은 hs review).
2. `ActiveTravelScreen` 헤더 벨 탭 → `onNotifications` → `NotificationListRoute` (F006 파일, jy review).

## 실서버·실 FCM (G001)

1. G001 Firebase 프로젝트의 `google-services.json`을 `android/app/`에 두고 `FCM_ENABLED=true` + 서비스 계정 JSON으로 API를 띄운다.
2. 실기기(`gilpick_api36_play`)에서 로그인 → DEV-001 등록 확인(서버 로그 또는 DB) → 앱을 백그라운드로.
3. 남은 장소에 F008 `ACTIVE` 감지를 만든다 → 30초 안에 실기기에 장소 변경 제안 알림 도착, 탭 → 대체 장소 화면이 그 감지로 열리는지 확인한다.
4. 도착 지오펜스 DWELL을 발생(또는 `progress/events` 호출) → 도착 확인 알림 도착 → 탭 → 진행 화면.
5. 설정 컬럼을 `false`로 두고 3을 반복 → 장소 변경 제안 알림이 오지 않고, 도착 확인 알림은 여전히 오는지 확인한다.
6. 로그아웃 → 그 기기로 이후 알림이 오지 않는지 확인한다.
