---

description: "Task list for F011 알림"
---

# Tasks: 알림

**Input**: `specs/011-notification/`의 spec.md·plan.md·research.md·data-model.md·contracts/notifications.openapi.yaml·quickstart.md

**담당 합의(2026-09-10)**: Backend = `ts`, Frontend(Android) = `jy`, Feature Owner = `ts`

**Tests**: 포함한다. spec UI-007(screenshot 검증)·quickstart·constitution "테스트와 관찰 가능성"이 요구한다.

**Organization**: user story 단위 phase. Setup·Foundational이 끝나면 US별로 병렬 가능하나, US2·US3·US5는 Backend 파이프라인(Phase 2)에, US1·US4·US6은 Android 골격(Phase 2)에 의존한다.

## Format: `[ID] [P?] [Story] Description in path`

- **[P]**: 다른 파일·미해결 선행 없음 → 병렬 가능
- 보조 메타데이터(들여쓰기): `영역`(BE/FE/통합) · `담당`(jy/ts) · `선행` · `검증`

---

## Phase 1: Setup (공유 인프라·빌드 설정)

- [x] T001 [P] FCM·보존 설정값 추가 in api/app/core/config.py, api/.env.example
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `Settings`에 `fcm_enabled`(기본 false)·`fcm_project_id`·`fcm_service_account_json`(SecretStr)·`fcm_request_timeout_seconds`(기본 5.0)·`notification_retention_days`(기본 90)·`notification_dispatch_interval_seconds`(기본 30)·`notification_cleanup_interval_seconds`(기본 3600)이 로드되고 `.env.example`에 대응 항목이 있는지 단위 확인
- [x] T002 [P] pyjwt RS256 서명 가능 여부 확인 in api/pyproject.toml
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `jwt.encode({}, private_key, algorithm="RS256")`가 동작하는지 확인. 실패하면 `pyjwt[crypto]`만 추가하고 `firebase-admin`은 추가하지 않는다. `uv.lock` 갱신 여부를 PR에 기록
- [x] T003 [P] FCM Gradle 배선 in android/build.gradle.kts, android/app/build.gradle.kts, android/.gitignore
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: `com.google.gms.google-services` 플러그인 classpath·id, `firebase-bom` + `com.google.firebase:firebase-messaging`, `apply(plugin = "com.google.gms.google-services")`, `.gitignore`에 `android/app/google-services.json`. `google-services.json`은 G001 Firebase 프로젝트에서 주입(placeholder로 `:app:assembleDebug` 통과 확인, PR에 미포함)
- [x] T004 [P] 알림 권한·messaging service 등록 in android/app/src/main/AndroidManifest.xml
  - 영역: FE
  - 담당: jy
  - 선행: T003
  - 검증: `POST_NOTIFICATIONS` 권한, `GilpickMessagingService`(`com.google.firebase.MESSAGING_EVENT` intent-filter) 등록. 서비스는 이 단계에서 빈 골격이어도 된다

**Checkpoint**: 빌드·설정 준비 완료

---

## Phase 2: Foundational (모든 user story의 선행 — 반드시 먼저)

**⚠️ CRITICAL**: 이 phase가 끝나기 전에는 어떤 user story도 시작할 수 없다.

### Backend 파이프라인

- [x] T005 Notification 모델과 metadata 등록 in api/app/models/notification.py, api/app/models/__init__.py, api/migrations/env.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `Notification`(`notifications` 테이블, data-model.md 1.1 컬럼) 정의, `models/__init__.py` 재노출, `migrations/env.py` import 목록에 `notification` 추가로 `Base.metadata`에 잡힘
- [x] T006 migration 011_create_notifications in api/migrations/versions/011_create_notifications.py
  - 영역: BE
  - 담당: ts
  - 선행: T005
  - 검증: `notifications` 테이블·`type` CHECK(5값)·인덱스 4개(`ix_notifications_user_unread`·`ix_notifications_retention`·`uq_notifications_dedup` partial·`ix_notifications_pending` partial), `device_sessions`에 `uq_device_sessions_fcm_token` partial unique 추가. `tests/integration/test_notification_migration.py`로 `upgrade → downgrade → upgrade` 왕복·인덱스·CHECK 확인. F010 승인 migration 병합을 반영해 `down_revision = "010_replacement_approval"`
- [x] T007 [P] FcmClient in api/app/clients/fcm.py
  - 영역: BE
  - 담당: ts
  - 선행: T001, T002
  - 검증: FCM HTTP v1(`.../messages:send`) + `pyjwt` RS256 → OAuth2 access token(~55분 캐시), data-only 메시지, 결과 `FcmSendResult`(`OK`/`INVALID_TOKEN`/`RETRYABLE`/`FATAL`), `FcmClientError(code, retryable, status_code)`. `fcm_enabled=false`면 발송 생략. `tests/unit/test_fcm_client.py`(`httpx2` fake + `tests/fixtures/fcm/*.json`: `send_ok`·`invalid_token`·`unavailable`·`oauth_token`)
- [x] T008 [P] dedup_key·본문 문구 생성 in api/app/services/notification/dedup.py, api/app/services/notification/messages.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: 유형별 `dedup_key`(research R2: `detection:{id}` / `transition:{id}:arrival_check:{seq}` / `transition:{id}:departure_check` / `transition:{id}:auto`), 유형별 `title`·`body`(research R14, 자동 확정은 `undo_deadline - now`로 남은 시간 계산·만료 문구). `tests/unit/test_notification_messages.py`
- [x] T009 NotificationService in api/app/services/notification/__init__.py
  - 영역: BE
  - 담당: ts
  - 선행: T005, T008
  - 검증: `create_place_change_suggestion(session, detection)`(설정 off면 no-op, `ON CONFLICT DO NOTHING`), `create_transition_check(session, transition, prompt_seq)`, `create_transition_auto_confirmed(session, transition)`, `list_notifications(user_id, cursor, limit, read)`(90일 필터·cursor), `mark_read(user_id, notification_id)`·`mark_all_read(user_id)`(멱등). `tests/unit/test_notification_service.py`
- [x] T010 NotificationDispatchService in api/app/services/notification/dispatch.py
  - 영역: BE
  - 담당: ts
  - 선행: T007, T009
  - 검증: `send_one(notification)` — 활성 기기 토큰 조회(`revoked_at IS NULL AND fcm_token IS NOT NULL`), 0개면 `sent_at`만 찍음, `RETRYABLE`이면 0.5s→1.5s 백오프 2회 재시도, `INVALID_TOKEN`이면 그 `fcm_token`만 `NULL`, 종료 시 `sent_at=now`·최종 실패는 `notification_delivery_failed` log(토큰·본문 없이). `tick(session)` — 미발송 큐 처리 + `IN_PROGRESS` 날짜에 `finalize_due_candidates` 호출 + 재질문 due 행 생성. `tests/unit/test_notification_dispatch.py`
- [x] T011 NOTI/DEV DTO in api/app/schemas/notification.py, api/app/schemas/device.py
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: `NotificationItem`·`NotificationListData`·`MarkReadResult`·`MarkAllResult`·`FcmTokenRegisterRequest`·`FcmTokenRegisterResult`가 contracts/notifications.openapi.yaml schema와 필드·필수 일치
- [x] T012 NOTI·DEV router 등록 in api/app/api/v1/notifications.py, api/app/api/v1/devices.py, api/app/main.py
  - 영역: BE
  - 담당: ts
  - 선행: T009, T011
  - 검증: NOTI-001(`GET /notifications`)·NOTI-002(`PATCH /notifications/{id}/read`)·NOTI-003(`PATCH /notifications/read-all`)·DEV-001(`PUT /devices/fcm-token`)·DEV-002(`DELETE /devices/{deviceId}/fcm-token`), 소유권 검증, `main.py` router 2개 등록. `tests/contract/test_notification_contract.py`(contracts YAML vs `create_app().openapi()`, 401/403/404)
- [x] T013 dispatch·cleanup job in api/app/jobs/notification_dispatch.py, api/app/jobs/notification_cleanup.py, api/app/main.py
  - 영역: BE
  - 담당: ts
  - 선행: T010
  - 검증: `run_notification_dispatch(session_factory, interval_seconds=...)`·`run_notification_cleanup(session_factory, interval_seconds=...)` + `cleanup_notifications(session, now=None)`(90일 초과 + 논리 삭제 여행 연결 행 삭제), `main.py` `lifespan`에 `asyncio.create_task` 2개·`finally` 취소. `tests/integration/test_notification_cleanup.py`(`test_auth_cleanup.py` 패턴), cycle 예외 격리

### Android 골격

- [x] T014 [P] NotificationApi·Repository in android/app/src/main/java/com/gilpick/notification/NotificationApi.kt, NotificationRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T012
  - 검증: `createNotificationRetrofit` + `NotificationService`(NOTI-001/002/003·DEV-001/002, `@Header("Authorization")`), `@Serializable` DTO, `NotificationRepository(api, auth)` + `.default(context)` + `AuthError.toNotificationError()`·`NotificationErrorCodes`. `NotificationRepositoryTest`(mockwebserver3, 401 재시도·매핑)
- [x] T015 [P] 상태 모델·라벨·navigation 골격 in android/app/src/main/java/com/gilpick/notification/NotificationUiState.kt, NotificationLabels.kt, NotificationNavigation.kt
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: `NotificationUiState`(Loading/Empty/Error/Content, `NotifGroup`·`NotifItemUi`·`target`), `NotificationLabels`(KST `오늘`/`어제`/`그 이전` 버킷·상대 시각·유형→아이콘·목적지), `NotificationNavigation`(`NotificationListRoute`·`VariableMonitorRoute`·`notificationGraph(navController, onSessionExpired, onOpenDetection, onOpenProgress)` stub)
- [x] T016 알림 채널·딥링크 골격 in android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T004, T015
  - 검증: `gilpick_notifications` 채널 생성(첫 진입), notification intent extras(`notif_type`·`trip_id`·`trip_day_id`·`item_id`·`detection_id`·`transition_id`) 파싱 → `PendingNotificationTarget` 상태, `onCreate`/`onNewIntent` 모두 처리, `NavHost` 구성 후 `LaunchedEffect`가 유형별 route로 이동하는 골격(빈 대상이면 목록으로), `notificationGraph` 배선. 교차 계약 review: F001 담당(`MainActivity`는 auth 흐름 인접)

**Checkpoint**: Backend 파이프라인·엔드포인트·Android 골격 준비 완료 — user story 착수 가능

---

## Phase 3: User Story 1 - 이 기기로 알림을 받도록 등록한다 (Priority: P1) 🎯 MVP

**Goal**: 로그인·토큰 갱신 시 기기 FCM 토큰 등록, 로그아웃 시 해제, 무효 토큰 자동 정리.

**Independent Test**: quickstart BE 6 + AND 4 — DEV-001 등록/갱신, 로그아웃 시 `fcm_token` NULL, 무효 토큰 확인 시 해당 컬럼만 NULL, 두 기기 모두 발송 대상.

- [x] T017 [US1] 로그아웃 시 FCM 토큰 정리 in api/app/services/auth.py
  - 영역: BE
  - 담당: ts
  - 선행: T006
  - 검증: `logout_device_session`의 `update(DeviceSession)...values(...)`에 `fcm_token=None` 추가. `POST /auth/logout` 후 그 세션 `fcm_token` NULL(quickstart BE 6.5). 교차 계약 review: F001 담당. 외부 동작·응답 불변
- [x] T018 [US1] DEV-001·DEV-002 동작 검증 in api/tests/contract/test_notification_contract.py, api/tests/unit/test_notification_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T012, T017
  - 검증: quickstart BE 6.1~6.7 — 등록·재등록 멱등, 같은 토큰 다른 세션 이동(partial unique), 활성 세션 없음 `404 DEVICE_SESSION_NOT_FOUND`, DEV-002 `204`, 타인 `deviceId` `403`/`404`
- [ ] T019 [P] [US1] FCM 토큰 durable worker in android/app/src/main/java/com/gilpick/notification/FcmTokenSyncWorker.kt, FcmTokenClearWorker.kt
  - 영역: FE
  - 담당: jy
  - 선행: T014
  - 검증: `FcmTokenSyncWorker`(현재 FCM 토큰 조회 → DEV-001, `SessionRevocationWorker` 패턴: unique work·`BackoffPolicy.EXPONENTIAL`·`NetworkType.CONNECTED`), `FcmTokenClearWorker`(DEV-002 + `FirebaseMessaging.deleteToken()` best-effort). `FcmTokenSyncWorkerTest`
- [ ] T020 [US1] 인증 흐름에 토큰 등록·해제 hook in android/app/src/main/java/com/gilpick/auth/AuthRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T019
  - 검증: `onSignedIn` 성공 후·`performRefresh` 성공 후 `FcmTokenSyncWorker.enqueue(context)`(FR-020), `logout()`에서 `FcmTokenClearWorker.enqueue(context)`. 푸시 권한 없음·토큰 실패가 로그인·다른 기능을 막지 않음(quickstart AND 4.3). 교차 계약 review: F001 담당(`AuthRepository`는 F001 소유)
- [ ] T021 [US1] onNewToken 연결 in android/app/src/main/java/com/gilpick/notification/GilpickMessagingService.kt
  - 영역: FE
  - 담당: jy
  - 선행: T004, T019
  - 검증: `onNewToken(token)` → `FcmTokenSyncWorker.enqueue`. (onMessageReceived는 US2/US3에서 채운다)

**Checkpoint**: US1 독립 검증 가능(발송 없이 등록·해제·정리)

---

## Phase 4: User Story 2 - 장소 변경 제안 알림을 받고 대체 장소로 들어간다 (Priority: P1)

**Goal**: `ACTIVE` 감지 최초 생성 시 장소 변경 제안 알림 생성·전달, 탭 시 대체 장소 화면.

**Independent Test**: quickstart BE 1 + BE 3 + AND 3 — 감지 최초 INSERT → `notifications` 1행 → 등록 기기 전달(payload에 식별자만) → 탭 시 `AlternativePlacesRoute(detectionId, tripId)`, 재평가 갱신 시 재발송 없음.

- [x] T022 [US2] 감지 최초 생성 hook in api/app/services/detection/evaluator.py
  - 영역: BE
  - 담당: ts
  - 선행: T009
  - 검증: `_store_detection`의 upsert에 `RETURNING (xmax = 0) AS inserted` 추가, 최초 INSERT일 때만 `NotificationService.create_place_change_suggestion(session, detection)` 호출(같은 transaction). `evaluate_all_active`가 새로 만든 `detection_id` 목록을 반환하도록 시그니처 확장. `reevaluate_day` 경로도 동일. 교차 계약 review: F008 담당. 감지 결과·DETECT API 응답 불변 — `tests/contract/test_detections_contract.py` 회귀
- [x] T023 [US2] 감지 cycle 후 즉시 발송 in api/app/jobs/variable_detection.py
  - 영역: BE
  - 담당: ts
  - 선행: T010, T022
  - 검증: `run_variable_detection` cycle의 `session.begin()` 커밋 직후, 새 `detection_id`에 대응하는 미발송 알림에 `NotificationDispatchService.send_one`을 1회 시도(실패해도 다음 dispatch tick이 재시도). 감지 루프 예외 격리 유지
  - 교차 계약 review: F008 담당 (`api/app/jobs/variable_detection.py` 수정)
- [x] T024 [US2] 장소 변경 제안 생성·발송 검증 in api/tests/unit/test_notification_service.py, api/tests/unit/test_notification_dispatch.py
  - 영역: BE
  - 담당: ts
  - 선행: T022, T023
  - 검증: quickstart BE 1.1~1.4, BE 3.1·3.6 — 최초 INSERT만 1행(`dedup_key='detection:{id}'`), 갱신 시 무생성, payload에 좌표·평점·토큰 없음, FCM 실패가 감지 transaction을 롤백하지 않음
- [ ] T025 [US2] onMessageReceived + 대체 장소 딥링크 in android/app/src/main/java/com/gilpick/notification/GilpickMessagingService.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T016, T021
  - 검증: data-only 메시지 수신 → 백그라운드면 채널 알림 + `PendingIntent`(extras), 포그라운드면 무표시(FR-029). `PLACE_CHANGE_SUGGESTION` 탭 → `AlternativePlacesRoute(detectionId, tripId)` 이동 후 재조회(FR-019). `NotificationNavigationTest`(extras → route). `AlternativePlacesRoute` KDoc이 이미 F011 진입 전제

**Checkpoint**: US2 독립 검증 가능

---

## Phase 5: User Story 3 - 도착·출발 확인과 자동 처리 알림을 받는다 (Priority: P2)

**Goal**: 확인 후보 생성·자동 확정·재질문 시 알림 생성·전달, 탭 시 진행 화면.

**Independent Test**: quickstart BE 2 + AND 3 — `register_event` 후 `ARRIVAL_CHECK`/`DEPARTURE_CHECK` 1행, 무응답 자동 확정 시 `..._AUTO_CONFIRMED` 1행(되돌리기 남은 시간), 재질문 시 `prompt_seq=2` 1행(최대 2회), 당일 완료 후 미생성, 탭 시 `ActiveTravelRoute`.

- [x] T026 [US3] 확인·자동·재질문 hook in api/app/services/detection/__init__.py
  - 영역: BE
  - 담당: ts
  - 선행: T009
  - 검증: `register_event`가 `PENDING_CONFIRMATION` 전환 생성 직후 `create_transition_check(prompt_seq=1)`, `_auto_confirm` 직후 `create_transition_auto_confirmed`, `decide_transition(NOT_ARRIVED)`가 `next_prompt_at`을 남긴 뒤 dispatch가 발견해 `create_transition_check(prompt_seq=2)`. 전환 규칙·응답 불변. 교차 계약 review: F006/F007 담당
- [x] T027 [US3] dispatch finalize·재질문 알림 통합 검증 in api/tests/unit/test_notification_dispatch.py, api/tests/unit/test_notification_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T010, T026
  - 검증: T010의 `tick`과 T026의 hook을 함께 실행했을 때 만료 후보가 자동 확정 알림으로 이어지고, `next_prompt_at <= now` 후보에는 `prompt_seq=2` 알림이 한 번만 생성되는지 검증한다. `finalize_due_candidates` 호출과 재질문 스캔 자체는 T010 구현을 재사용한다.
  - 교차 계약 review: T026의 F006/F007 hook과 기존 전환 규칙·응답 불변을 담당자가 확인한다.
- [x] T028 [US3] 확인 알림 즉시 발송 in api/app/api/v1/progress.py
  - 영역: BE
  - 담당: ts
  - 선행: T010, T026
  - 검증: `register_progress_event` 응답 후 `BackgroundTasks`로 방금 만든 확인 알림에 `send_one` 1회 시도(SC-012 30초). 교차 계약 review: F007 담당(`progress.py`)
- [x] T029 [US3] 진행 알림 생성·설정 무관 검증 in api/tests/unit/test_notification_service.py, api/tests/unit/test_notification_dispatch.py
  - 영역: BE
  - 담당: ts
  - 선행: T026, T027, T028
  - 검증: quickstart BE 2.1~2.6, BE 3 — 유형·`dedup_key`·`transition_id`·`item_id`, 최대 2회 재질문, `아직 머무는 중`·당일 완료 후 미생성(SC-011), 설정 off여도 진행 알림 전달(SC-003), COMPOSITE 자동 확정 1건
- [ ] T030 [US3] 진행 화면 딥링크 in android/app/src/main/java/com/gilpick/notification/GilpickMessagingService.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T025
  - 검증: `ARRIVAL_CHECK`·`DEPARTURE_CHECK`·`ARRIVAL_AUTO_CONFIRMED`·`DEPARTURE_AUTO_CONFIRMED` 탭 → `ActiveTravelRoute(tripId, tripName)` 이동 후 서버 상태 재조회(FR-019, 후보 만료·자동 확정 반영). `NotificationNavigationTest`

**Checkpoint**: US3 독립 검증 가능

---

## Phase 6: User Story 4 - 알림 목록 화면에서 지난 알림을 확인하고 이동한다 (Priority: P2)

**Goal**: 알림 목록 화면 — 날짜 그룹·읽음·모두 읽음·항목 탭 이동, loading/empty/error/content.

**Independent Test**: quickstart AND 1 + AND 2 + BE 5 — 오늘/어제 그룹 최신순, 안 읽음 표식, 탭 시 읽음 + 대상 이동, "모두 읽음", 빈·오류 상태, 대상 삭제 시 안내.

- [ ] T031 [US4] NotificationListViewModel in android/app/src/main/java/com/gilpick/notification/NotificationListViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T014, T015
  - 검증: NOTI-001 조회 → `NotifGroup` 그룹핑(KST), 안 읽음 분류, 탭 시 NOTI-002 + `onOpen(target)`, "모두 읽음" NOTI-003, 재조회 중 기존 목록 유지, 오류·세션 만료 매핑. `NotificationListViewModelTest`
- [ ] T032 [US4] NotificationListScreen·Row in android/app/src/main/java/com/gilpick/notification/NotificationListScreen.kt, NotificationRow.kt
  - 영역: FE
  - 담당: jy
  - 선행: T031
  - 검증: Figma `NotificationsScreen` 형식 + 4상태. 상태·접근성 완료 기준:
    - `loading`: 1초 초과 시에만 대기 표시(그 전 무표시)
    - `empty`: 받은 알림 0 — 아이콘 상자·제목·설명(ui-guidelines 9절)
    - `error`: 조회 실패 — `다시 시도하기` + 돌아가기
    - `content`: 날짜 그룹·최신순, 안 읽음 `surfaceTint` 배경 + `groupDot` 점(색 단독 금지), 제목·본문·상대 시각
    - 접근성: 행·아이콘 버튼 터치 48dp·간격 8dp, "모두 읽음"·뒤로 가기 `contentDescription`, 360dp·fontScale 2.0에서 잘림 없음
- [ ] T033 [US4] 알림 목록 화면 상태·이동 test in android/app/src/androidTest/java/com/gilpick/notification/NotificationListScreenTest.kt, NotificationNavigationTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T032
  - 검증: quickstart AND 1.1~1.4, AND 2.1~2.3 — 그룹·안 읽음·상태 전환·모두 읽음, 유형별 탭 목적지, 대상 없음(여행 논리 삭제) 안내, 3탭 이하 도달(SC-009)
- [ ] T034 [P] [US4] 알림 목록 screenshot test in android/app/src/androidTest/java/com/gilpick/notification/NotificationsScreenshotTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T032
  - 검증: `content`(읽음·안 읽음·2그룹 혼재)·`empty`·`error` × 기본 배율·360dp/fontScale2 = 6장 캡처(`captureToImage`), `adb pull` 후 Figma `NotificationsScreen` 대조(SC-010)
- [x] T035 [US4] 알림 목록 조회·읽음 검증 in api/tests/contract/test_notification_contract.py, api/tests/unit/test_notification_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T012
  - 검증: quickstart BE 5.1~5.6 — 90일 필터·`read` 필터·cursor, NOTI-002 멱등·`403`/`404`, NOTI-003 `{updated}`, 대상 논리 삭제·무효화 후에도 `200`(FR-016), 타인 알림 격리(SC-007)
- [ ] T036 [US4] 알림 진입점(벨) in android/app/src/main/java/com/gilpick/trip/TripListScreen.kt, android/app/src/main/java/com/gilpick/progress/ActiveTravelScreen.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T016, T032
  - 검증: `TripListScreen`·`ActiveTravelScreen` `Header`에 알림 벨 아이콘 + `onNotifications` 파라미터 → `MainActivity`가 `NotificationListRoute`로 navigate. `contentDescription` 지정. 교차 계약 review: `TripListScreen`은 F002 담당(hs), `ActiveTravelScreen`은 F006 담당(jy 본인이나 F006 범위임을 PR에 명시)

**Checkpoint**: US4 독립 검증 가능

---

## Phase 7: User Story 5 - 장소 변경 제안 알림을 끈다 (Priority: P3)

**Goal**: `users.replacement_suggestion_enabled`가 false면 장소 변경 제안 알림을 생성·발송하지 않는다. 도착·출발 알림은 무관.

**Independent Test**: quickstart BE 1.3 + BE 2.5 + 실서버 5 — 설정 off 시 감지는 생기되 `PLACE_CHANGE_SUGGESTION` 무생성, 같은 사용자 도착 확인 알림은 정상.

- [x] T037 [US5] 발송 시 설정 존중 검증 in api/tests/unit/test_notification_service.py
  - 영역: BE
  - 담당: ts
  - 선행: T009, T022
  - 검증: `create_place_change_suggestion`이 `replacement_suggestion_enabled=false`면 행·발송 없음(감지 생성·조회 불변), 다시 true면 이후 감지부터 재개(소급 없음, FR-022), 도착·출발·자동·재질문은 설정과 무관(FR-004, SC-003). PREF-001·PREF-002는 F012 범위이므로 여기서 구현하지 않고 컬럼 값 fixture로만 검증
- [ ] T038 [US5] 통합 확인 in specs/011-notification/quickstart.md 실서버 5
  - 영역: 통합
  - 담당: ts
  - 선행: T037, T024, T029
  - 검증: G001 환경에서 설정 컬럼 false → 장소 변경 제안 알림 미수신, 도착 확인 알림 수신 확인. 자동 test가 없는 수동 절차이므로 실행 결과와 확인 화면을 PR에 기록

**Checkpoint**: US5 독립 검증 가능

---

## Phase 8: User Story 6 - 감지 결과 목록을 훑어본다 (Priority: P3)

**Goal**: 감지 결과 목록 화면(Figma `VariableMonitorScreen`) — 시간순·위험순, 각 항목에서 대체 장소 화면 이동.

**Independent Test**: quickstart AND 5 — `ACTIVE` 감지 3건 목록·정렬 토글·"대체 장소 보기" 이동·빈 상태.

- [ ] T039 [P] [US6] VariableMonitorViewModel in android/app/src/main/java/com/gilpick/notification/VariableMonitorViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T015
  - 검증: `com.gilpick.alternative.AlternativeRepository.listDetections(tripId, status = ACTIVE)` 재사용(읽기 전용), `sort` = TIME|RISK, `Content`/`Empty`/`Error`, "대체 장소 보기" → `onOpenDetection(detectionId, tripId)`. `VariableMonitorViewModelTest`. 교차 계약 review: F009 담당(jy)
- [ ] T040 [US6] VariableMonitorScreen in android/app/src/main/java/com/gilpick/notification/VariableMonitorScreen.kt, android/app/src/main/java/com/gilpick/notification/NotificationNavigation.kt
  - 영역: FE
  - 담당: jy
  - 선행: T039
  - 검증: Figma `VariableMonitorScreen` 형식 + 상태:
    - `content`: 장소명·요약·변수별 수준·방문 예정 시각·감지 경과, 시간순↔위험순 토글, 변수 제외 항목은 사유 문구(없는 값 안 지어냄)
    - `empty`: Figma `hasAlerts=false` — "모든 일정이 예정대로예요" + 진행 화면으로
    - `error`: F009 오류 형식 재사용
    - 접근성: 터치 48dp·간격 8dp, 360dp·fontScale 2.0 잘림 없음
    - `notificationGraph`에 `VariableMonitorRoute` 배선, "대체 장소 보기" → `AlternativePlacesRoute`
- [ ] T041 [US6] 감지 목록 화면 test·screenshot in android/app/src/androidTest/java/com/gilpick/notification/VariableMonitorScreenTest.kt, VariableMonitorScreenshotTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T040
  - 검증: quickstart AND 5.1~5.3 — 3건·정렬·이동값·변수 제외 문구·빈 상태. screenshot `content`·`empty` × 2배율 = 4장, Figma 대조

**Checkpoint**: US6 독립 검증 가능

---

## Phase 9: Polish & Cross-Cutting

- [x] T042 [P] 계약 문서 동기화 in docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: BE
  - 담당: ts
  - 선행: T012, T006
  - 검증: data-model.md 5절 목록 — api-spec 2절(NOTI-001·002·DEV-001·002 `[x]`, NOTI-003 행 신설)·9절(NOTI-001 `type` 값·선택 식별자 필드·90일 문구, NOTI-003 신설, DEV-001 `404` 명시, PREF는 F012 표시 유지), er-schema 1절(알림 90일 보존)·9.1(`type` CHECK·`dedup_key`·`sent_at` 의미)·10절(`notification_type` enum 행)·12절(`ix_notifications_pending`·`fcm_token` partial unique 반영). 같은 PR에 포함. 설정 필드명(`replacement_suggestion_enabled` ↔ `placeChangeSuggestionNotificationEnabled`) 통일은 F011 범위 밖 — F012 PREF 계약 작업에서 처리하므로 이 task에서 건드리지 않는다
- [x] T043 [P] 요구사항·기능 명세 동기화 in docs/planning/requirements.md, docs/planning/functional-spec.md
  - 영역: BE
  - 담당: ts
  - 선행: 없음
  - 검증: requirements NOTI-01·functional-spec 6절에 전달 목표 30초·재시도·포그라운드 미표시·90일 보존 반영(spec Clarifications 2026-09-10과 일치). 새 정책값을 지어내지 않고 spec 값만 옮김
- [x] T044 관찰 가능성 점검 in api/app/services/notification/dispatch.py, api/app/services/notification/__init__.py
  - 영역: BE
  - 담당: ts
  - 선행: T010, T013
  - 검증: 알림 생성·전달마다 request id·유형·대상 id·기기 수·전달 결과 요약 log, 토큰 원문·정밀 위치·본문 개인정보 미기록(FR-024, constitution V). `SensitiveDataFilter` 통과 확인
- [ ] T045 전체 자동 검증 실행 in specs/011-notification/quickstart.md 자동 검증
  - 영역: 통합
  - 담당: ts
  - 선행: Phase 3~8 완료
  - 검증: quickstart "자동 검증" 블록의 BE pytest 7개 + Android `testDebugUnitTest`·`assembleDebug`·`connectedDebugAndroidTest -P...package=com.gilpick.notification` 실행, 결과를 PR에 기록. 기존 `test_auth_contract.py` 실패는 무관(origin/main 기준)
- [ ] T046 실서버·실 FCM 종단 검증 in specs/011-notification/quickstart.md 실서버 1~6
  - 영역: 통합
  - 담당: jy
  - 선행: T045, G001(Firebase 프로젝트·`google-services.json`)
  - 검증: 실기기(`gilpick_api36_play`)에서 로그인→DEV-001, 감지 생성→30초 내 장소 변경 제안 수신→탭→대체 장소 화면, 도착 후보→확인 알림→진행 화면, 설정 off→장소 변경 제안 미수신·도착 알림 유지, 로그아웃→미수신. G001 미준비면 이 task는 `blocked by` 환경 Issue로 연결하고 실행 못 한 항목·이유를 PR에 남긴다
- [ ] T047 Feature 상태 전이 in docs/planning/mvp-features.md
  - 영역: 통합
  - 담당: ts
  - 선행: T045
  - 검증: 검증된 tasks 문서 PR에서 F011 `SPEC → READY`, 첫 구현 Issue PR에서 `IN_PROGRESS`, 전체 검증 PR에서 `VERIFY`, 관련 PR `main` 병합 후 `DONE`으로 갱신(전이를 일으킨 PR에 포함)

---

## Dependencies & Execution Order

### Phase 순서

- **Phase 1 Setup**: 즉시 시작. T001·T002(BE)와 T003·T004(FE)는 병렬
- **Phase 2 Foundational**: Setup 후. BE(T005~T013)와 FE 골격(T014~T016)은 T012(엔드포인트) 지점에서만 만나므로 대체로 병렬. **모든 user story를 차단**
- **Phase 3~8 User Stories**: Foundational 후
  - US1(P1): T017~T021 — BE는 T006, FE는 T014·T019
  - US2(P1): T022~T025 — BE는 T009·T010, FE는 T016
  - US3(P2): T026~T030 — BE는 T009·T010, FE는 T025
  - US4(P2): T031~T036 — FE는 T014·T015, BE는 T012
  - US5(P3): T037~T038 — T009·T022·T024·T029
  - US6(P3): T039~T041 — T015 + F009 `AlternativeRepository`
- **Phase 9 Polish**: 해당 user story 완료 후. T042·T043은 T006·T012 이후 언제든

### User Story 간 관계

- US1은 다른 story와 독립(발송 대상 준비). US2·US3은 US1이 없어도 "행 생성·목록 적재"까지 검증 가능하나 실 전달 검증은 US1 필요
- US2·US3은 같은 `services/detection/` 패키지지만 T022는 `evaluator.py`, T026은 `__init__.py`를 수정하므로 서로 독립이다. dispatch(T010)는 공유하므로 T010 완료 후 병렬
- US4는 US2·US3이 만든 알림을 소비하지만 fake repository로 독립 검증 가능
- US5는 US2 hook(T022)에 의존
- US6은 F009 재사용만 하므로 Phase 2 FE 골격 후 독립

### 파일 소유권 조율(같은 파일 동시 수정 금지)

- `api/app/services/notification/dispatch.py`: T010(생성·finalize·재질문 스캔) → T044(로그). 순차. T027은 이 구현과 T026 hook의 통합 test만 추가
- `android/.../MainActivity.kt`: T016(골격) → T025(대체 딥링크) → T030(진행 딥링크) → T036(벨 배선). 순차, 한 담당(jy)
- `android/.../GilpickMessagingService.kt`: T021(onNewToken) → T025(onMessageReceived) → T030(유형별). 순차
- `api/app/main.py`: T012(router)·T013(job) — 같은 파일, 순서 조율
- `api/app/services/detection/__init__.py`(F006/F007)·`evaluator.py`(F008): F011이 hook만 추가. 해당 Feature 담당 review 필수

### Within each story

- 테스트는 구현과 같은 phase. 계약·상태 test는 구현 직후 검증에 포함
- 모델 → 서비스 → 엔드포인트 → hook → 화면 순
- story 완료(코드 + 명시된 test + 문서 동기화)까지 끝나야 다음 우선순위로

### Parallel Opportunities

- Phase 1: T001+T002(BE) ∥ T003+T004(FE)
- Phase 2: T007·T008·T011·T014·T015는 서로 [P]. T005→T006, T009→T010→T013는 순차
- Foundational 후: US1(jy+ts) ∥ US2(ts) ∥ US4 FE(jy) 등 담당·파일이 겹치지 않는 범위에서 병렬
- Phase 9: T042 ∥ T043

---

## Parallel Example: Phase 2 Foundational

```text
# 동시 착수 가능 (다른 파일):
T007  FcmClient (api/app/clients/fcm.py)                 — ts
T008  dedup·messages (api/app/services/notification/…)   — ts
T011  NOTI/DEV DTO (api/app/schemas/…)                   — ts
T014  NotificationApi·Repository (com.gilpick.notification) — jy
T015  상태 모델·라벨·navigation 골격                       — jy

# 순차 체인:
T005 → T006 (모델 → migration)
T009 → T010 → T013 (서비스 → dispatch → job)
T012 는 T009·T011 후
```

---

## Implementation Strategy

### MVP (US1 + US2)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 US1(기기 토큰) → 독립 검증
3. Phase 4 US2(장소 변경 제안 알림) → 독립 검증 → 첫 구현 Issue PR에서 F011 `IN_PROGRESS`
4. **STOP & VALIDATE**: 감지 생성 → 실기기 알림 → 대체 장소 화면(quickstart 실서버 2~3)

### Incremental Delivery

1. Setup + Foundational → 기반
2. US1 → US2 → (MVP) → US3(진행 알림) → US4(알림 목록) → US5(끄기) → US6(감지 목록)
3. 각 story는 이전을 깨지 않고 가치를 더한다
4. Phase 9 문서 동기화·quickstart·상태 전이

### Parallel Team

- Foundational: ts가 BE 파이프라인, jy가 Android 골격 병렬
- 이후: ts는 US2·US3·US5 BE hook·검증, jy는 US1·US4·US6 FE 화면·worker·딥링크
- 통합 검증(T045·T046)과 NOTI/DEV DTO(T011↔T014)는 두 담당이 함께 확인

---

## Notes

- `[P]` = 다른 파일·미해결 선행 없음
- `[Story]` 라벨로 추적. Setup·Foundational·Polish는 라벨 없음
- test는 구현과 같은 phase에서 통과시킨다(별도 TDD 요구는 없음, 동작 검증 기준)
- 자동 test가 없는 문서·통합 task(T038·T042·T043·T046·T047)는 확인 방법과 결과를 PR에 기록
- F008·F006/F007·F001·F009·F002 파일 수정은 외부 동작 불변이어도 해당 Feature 담당 review를 받는다(constitution 교차 계약 review)
- commit은 task 또는 논리 묶음 단위, `<type>: <한글 요약>`
- `speckit-taskstoissues` 후 보조 메타데이터의 담당·선행을 실제 GitHub Issue assignee·dependency에 반영했는지 확인
