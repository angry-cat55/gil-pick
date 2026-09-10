# Implementation Plan: 알림

**Branch**: `docs/ts-f011-notification-spec` (Spec Kit 논리 식별자 `011-notification`) | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/011-notification/spec.md`

## Summary

F011은 이미 존재하는 도메인 이벤트에 **알림 생성·전달**을 연결한다. Backend는 (1) F008 `_store_detection`의 감지 최초 INSERT에 `PLACE_CHANGE_SUGGESTION` 알림을, (2) F007 `DetectionService.register_event`의 확인 후보 생성에 `ARRIVAL_CHECK`/`DEPARTURE_CHECK`를, (3) F006/F007 `_auto_confirm`에 `..._AUTO_CONFIRMED`를, (4) 재질문 시각 도래에 두 번째 `ARRIVAL_CHECK`를 만든다. 알림 행은 각 도메인 transaction 안에서 `notifications`(신규 테이블)에 `dedup_key` 유일 제약으로 만들고, FCM 발송은 커밋 뒤 새 `notification_dispatch` asyncio 루프(30초)가 담당한다 — `notifications WHERE sent_at IS NULL`을 큐로, `FcmClient`(FCM HTTP v1 + `pyjwt`, 새 무거운 의존성 없음)로 data-only 메시지를 보내며 일시 오류는 최대 2회 재시도, 무효 토큰은 그 `device_sessions.fcm_token`만 비운다. 같은 루프가 `IN_PROGRESS` 날짜에 `finalize_due_candidates`를 호출해 자동 확정·재질문 알림을 제때 만든다. NOTI-001~003(모두 읽음 신설)·DEV-001·DEV-002 endpoint와 90일 정리 job을 추가한다. 장소 변경 제안 알림은 `users.replacement_suggestion_enabled`가 켜져 있을 때만 만들고, 도착·출발 알림은 설정과 무관하다. PREF-001·PREF-002 설정 화면·엔드포인트는 F012 범위다.

Android는 새 패키지 `com.gilpick.notification`에 알림 목록 화면(Figma `NotificationsScreen`)·감지 목록 화면(Figma `VariableMonitorScreen`, US6)·`NotificationService`(NOTI/DEV)·`FirebaseMessagingService` 구현을 만들고, `AuthRepository`의 로그인·토큰 갱신·로그아웃에 DEV-001·DEV-002를 durable worker로 건다. 푸시 탭은 `MainActivity` intent extras → `AlternativePlacesRoute` 또는 `ActiveTravelRoute`로 이동한 뒤 서버 상태를 재조회한다. `TripListScreen`·`ActiveTravelScreen` 헤더에 알림 벨 진입점을 더한다. FCM Gradle 설정(`google-services` 플러그인·`firebase-messaging`·`google-services.json`·`POST_NOTIFICATIONS`)을 추가한다.

결정 근거는 [research.md](research.md), 계약은 [contracts/notifications.openapi.yaml](contracts/notifications.openapi.yaml), 데이터·상태 모델은 [data-model.md](data-model.md), 검증 절차는 [quickstart.md](quickstart.md)다.

## Technical Context

**Language/Version**: Backend Python 3.13 (FastAPI). Android Kotlin, Jetpack Compose, minSdk 26 / targetSdk 36 (기존 유지)

**Primary Dependencies**: Backend는 FastAPI, SQLAlchemy 2 async, `httpx2`, `pydantic-settings`, **`pyjwt`(이미 있음, FCM OAuth2 assertion에 재사용)** — `firebase-admin`은 도입하지 않는다(research R5). Android는 Retrofit 3 + kotlinx.serialization, Navigation Compose, WorkManager(이미 있음) + **신규 `com.google.firebase:firebase-messaging`(firebase-bom) 및 `com.google.gms.google-services` 플러그인**

**Storage**: PostgreSQL/PostGIS. **신규 테이블 1개(`notifications`)와 migration 1개(`011_create_notifications`)**. `device_sessions.fcm_token` 갱신·`NULL`화, `users.replacement_suggestion_enabled` 읽기, `detections`·`progress_transitions`·`trip_days`·`trips`·`itinerary_items`·`places` 읽기. `device_sessions`에 partial unique 인덱스 1개 추가

**Testing**: pytest unit/contract(`httpx2` mock transport, 고정 시각)/integration(실 Postgres, `test_auth_cleanup.py`·`test_*_migration.py` 패턴). Android JUnit unit(fake repository/service) + androidTest(Compose UI·navigation·screenshot, 계측 `captureToImage`, 에뮬레이터 `gilpick_api36_play`) + 실서버·실 FCM 수동 절차(quickstart 실서버 1~6)

**Target Platform**: Linux API 서버 단일 인스턴스(background job은 worker마다 뜨므로 uvicorn 단일 worker 전제) + Android 앱 + Firebase FCM(HTTP v1)

**Project Type**: Mobile + API

**Performance Goals**: NOTI-001/002/003·DEV-001/002는 기존 목록·PATCH endpoint와 동급(p95 < 300ms). 알림 행 생성은 감지 cycle·PROG-003 transaction에 INSERT(+`ON CONFLICT`) 1건 추가 — 무시 가능. `notification_dispatch` tick(30초): 미발송 행 ≤ 200건/tick, 행당 FCM 호출 ≤ (기기 수 × 3회 재시도), 재시도 백오프 0.5s→1.5s. SC-012: 확인 알림은 `register_event`(요청 경로) 직후 `BackgroundTasks` 즉시 발송으로 이벤트→발송요청 30초 이내. 장소 변경 제안은 10분 감지 cycle 커밋 직후 즉시 발송. 자동 확정·재질문은 dispatch tick(≤30초)이 `finalize_due_candidates`로 제때 생성·발송

**Constraints**: 알림 생성·조회·읽음·기기 등록은 감지·전환·일정·경로 상태를 바꾸지 않는다(FR-025). FCM 발송은 도메인 커밋 이후에만 하고 발송 실패가 도메인 전이를 롤백하지 않는다(FR-009, constitution IV). 일시 오류 재시도 ≤ 2회, 무효 토큰은 그 컬럼만 `NULL`(FR-008·FR-027). payload는 재조회 식별자 + `title`·`body`만, 좌표·평점·정밀 위치·토큰 금지(FR-006, SC-004). 모든 endpoint는 `user_id`(알림) / `user_id + client_device_id`(기기) 소유권 검증(constitution V). 조정값(`fcm_enabled`, timeout, 재시도, 보존 90일, dispatch 주기)은 `core/config.py` `Settings` 한곳. 화면 값은 `com.gilpick.ui.theme` 토큰만 사용

**Scale/Scope**: 신규 endpoint 5개(NOTI-001·002·003·DEV-001·002), 신규 BE 파일(`models/notification.py`, `schemas/notification.py`, `schemas/device.py`, `services/notification/{__init__,dedup,messages}.py`, `services/notification/dispatch.py`, `clients/fcm.py`, `api/v1/notifications.py`, `api/v1/devices.py`, `jobs/notification_dispatch.py`, `jobs/notification_cleanup.py`, migration `010`) + F008·F006/F007·F001 파일에 동작 불변 hook 추가 4건 + `main.py`·`migrations/env.py`·`models/__init__.py`·`core/config.py`·`.env.example` 수정. Android 신규 패키지 `com.gilpick.notification` ~10파일 + FCM messaging service + 2 worker + `MainActivity`·`AuthRepository`·`TripListScreen`·`ActiveTravelScreen`·Gradle·Manifest 수정. F012(설정 화면·PREF) 제외

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`(3·5·8·9·10절), Figma Make 저장소 사본 `docs/design/figma-make/src/screens/NotificationsScreen.tsx`(알림 목록, 단일 상태), `VariableMonitorScreen.tsx`(감지 목록, `hasAlerts` true/false), 진입점은 `MyTripsScreen.tsx`·`ActiveTravelScreen.tsx` 헤더의 알림 버튼. Figma에 없는 상태(빈·오류·로딩)는 ui-guidelines 9절로 만든다.

**Tokens & Components**: 색·간격·크기·곡률은 `GilpickTheme` 토큰만 — 안 읽은 알림 배경 `surfaceTint`(이미 "읽지 않은 알림" 용도), 그룹 헤더·안 읽음 점 `GilpickSizing.groupDot`(8dp), 아이콘 배경 `primaryContainer`(안 읽음)·`background`(읽음), 보조 글자 `muted`·`faint`, 오류 상태 `errorContainer`, 감지 목록의 위험 색은 F008/F009가 쓰는 `error`/`warning`/`amber`. **새 토큰 추가 없음.** 재사용: `ui/component`에 list-row/empty/error 공용 컴포넌트가 없어(subagent §17) `com.gilpick.notification` 내부 composable로 4상태·행을 직접 만든다(F009 화면과 같은 방식). 감지 목록은 F009 `DetectionListItemDto`와 `AlternativeRepository.listDetections`를 재사용한다. 신규 composable: `NotificationListScreen`, `NotificationRow`, `NotifDateHeader`, `VariableMonitorScreen`.

**State & Interaction**: `NotificationUiState` = `Loading`(1초 지연 표시) / `Empty`(받은 알림 0) / `Error`(다시 시도·돌아가기) / `Content(groups, refreshing)` — `groups`는 `오늘`/`어제`/`그 이전` KST 구간(`TripListScreen`의 `TripGroupHeader` 선례). 행 탭 → NOTI-002 읽음 → 유형별 목적지(`Alternative(detectionId,tripId)` / `Progress(tripId,tripName)`), 헤더 "모두 읽음" → NOTI-003. 재조회 중 기존 목록 유지. 대상 없음(여행 논리 삭제·감지 무효화)은 이동 화면이 문구로 안내(FR-016). `VariableMonitorUiState` = `Loading`/`Empty`(Figma `hasAlerts=false`)/`Error`/`Content(items, sort=TIME|RISK)`. 푸시 탭 → `MainActivity` extras → `PendingNotificationTarget` → `NavHost` 준비 후 이동 + 재조회(FR-019). 포그라운드 수신은 화면 안 표시 없음(FR-029).

**Accessibility & Adaptive Layout**: 알림 행·아이콘 전용 버튼 터치 영역 `sizeIn(minHeight = 48.dp)`·간격 8dp, "모두 읽음"·뒤로 가기 버튼 `contentDescription`, 안 읽음은 `surfaceTint` 배경 + 점 표식(색 단독 금지, UI-002), 단독 의미 전달 글자 12sp 이상, 화면 너비 360dp·시스템 글자 최대 배율(fontScale 2.0)에서 `weight(1f)` + 줄바꿈으로 가로 스크롤·잘림 없음, `statusBarsPadding`/`navigationBarsPadding`. 스크린리더 추가 작업은 범위 밖(ui-guidelines 10절 "범위").

**Visual Validation**: `NotificationsScreenshotTest` = `content`(읽음·안 읽음·2그룹 혼재)·`empty`·`error` × 기본 배율·360dp+fontScale2 = 6장, `VariableMonitorScreenshotTest` = `content`·`empty` × 2배율 = 4장(계측 `captureToImage`, `adb pull` 후 사람이 Figma와 대조). 실기기/`gilpick_api36_play` 실서버·실 FCM 절차는 quickstart 실서버 1~6. 적용하지 않는 상태: 알림 목록의 `loading`은 1초 초과 시에만(그 전 표시 없음), 감지 목록의 `error`는 F009 오류 형식 재사용.

### Figma 대조 결과 (예정, T00X)

`speckit-tasks` 이후 첫 FE 구현 전에 Figma Make `NotificationsScreen`·`VariableMonitorScreen`을 재조회하고 저장소 사본과의 차이, spec이 요구하나 Figma에 없는 상태 표현(빈·오류·로딩·대상 없음 안내), Figma에 있으나 F011이 쓰지 않는 요소를 표로 남긴다(F009 plan의 "Figma 대조 결과" 형식). 현재까지 확인한 항목:

| 요소 | Figma | F011 처리 | 근거 |
|---|---|---|---|
| 알림 목록 날짜 그룹 | `오늘`/`어제` 고정 | `오늘`/`어제`/`그 이전`(KST), 항목 `createdAt` 기준 | UI-001 |
| 안 읽음 표시 | `surfaceTint` 배경 + 파란 점 | 그대로 + `contentDescription`에 "안 읽음" | UI-002 |
| "모두 읽음" 헤더 버튼 | 있음(동작 미정의) | NOTI-003 호출 | FR-014 |
| 항목 탭 | Figma 목 mock은 탭 없음 | NOTI-002 읽음 + 유형별 화면 이동 | FR-018 |
| 빈·오류·로딩 상태 | 없음 | ui-guidelines 9절 형식 | UI-004 |
| "경로를 다시 계산하지 못했어요"·"내일 여행이 시작돼요" 예시 항목 | 있음 | 표시 안 함(F011 MVP 범위 아님) | spec Clarifications 2026-09-10 |
| `VariableMonitorScreen` "대체 장소 보기" | 있음 | `onOpenDetection(detectionId, tripId)` → `AlternativePlacesRoute` | UI-008 |
| `VariableMonitorScreen` 정렬 토글 | `시간순`/`위험순` | 그대로 | UI-008 |
| 감지 목록 변수 제외 항목 | `infoNote` 문구 | DETECT-002 값 그대로, 없는 값 안 지어냄 | UI-008 |
| 감지 목록 변수별 수준(`details`) | 변수명 + `아주 높음`/`경계` 등 3단계 색 | DETECT-001 항목에는 변수별 판정이 없어 항목마다 DETECT-002를 병렬 조회하고, 값은 계약 enum·수치(`혼잡`, `비 확률 80%`, `18:00 마감`) 그대로 표시. 위험 판정(`crowded`·`atRisk`·`closingSoon`)만 `error` 색. 상세 조회 실패 카드는 판정 줄 없이 안내 문구 | UI-008, T039 구현 결정(2026-09-10) |
| 감지 목록 정렬 기준 | `시간순`/`위험순` 라벨만 | 시간순 = `eta` 이른 순, 위험순 = `totalRiskScore` 높은 순(동점은 `eta`) | T039 구현 결정(2026-09-10) |
| 감지 목록 `여행 진행 화면으로`·뒤로 가기 | 둘 다 `onBack` | 둘 다 `popBackStack`(진입점이 진행 화면·알림이라 되돌아감). 카드 시각·`대체 장소 보기` 행은 `FlowRow`(360dp/2.0에서 글자 단위 꺾임 방지) | T040 구현 결정(2026-09-10) |

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 알림은 조언·통지이며 알림 생성·조회·읽음·기기 등록은 감지·전환·일정·경로 상태를 바꾸지 않는다(FR-025). 장소 변경 제안 알림은 사용자가 F012 설정으로 끌 수 있고 F011이 발송 시 존중한다(FR-003·FR-021). 도착·출발 확인 알림은 F006/F007의 수동 진행·되돌리기를 대체하지 않고 "앱 밖에서도 알린다"만 더한다. 푸시·기기 토큰 획득 실패가 앱의 다른 기능을 막지 않는다(FR-020). 포그라운드 heads-up을 강요하지 않는다(FR-029). | PASS |
| II. 계약 우선 SDD와 문서 동기화 | NOTI-001·NOTI-002·DEV-001·DEV-002는 `api-spec.md` 9절 경로를 유지하고 응답을 `type` enum·선택 식별자 필드로 구체화하며, **NOTI-003 모두 읽음**과 `notifications` 테이블·`notification_type` enum·`device_sessions` partial unique를 신설한다. **같은 PR에서** `api-spec.md` 2·9절, `er-schema.md` 1·9.1·10·12절, `requirements.md` NOTI-01·`functional-spec.md` 6절을 동기화한다(data-model.md 5절). F008 `_store_detection`·F006/F007 `DetectionService`·F001 `logout_device_session`의 hook 추가는 외부 동작·응답이 바뀌지 않으므로 상위 문서를 고치지 않되, `finalize_due_candidates`를 dispatch가 호출하는 것은 자동 확정 기록 시점을 앞당기므로 F006/F007 담당 review로 확정한다. | PASS WITH DOC-SYNC + REVIEW CONDITION |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | 알림 행은 감지·전환 transaction 안에서 만들어 감지/전환과 원자적이다(부분 성공 없음). `dedup_key` partial unique + `ON CONFLICT DO NOTHING`으로 한 대상당 한 건(재질문은 `prompt_seq`로 2건)만 남아 재평가·재시도·동시 요청에 멱등하다(FR-002). DEV-001은 `(user_id, client_device_id)` upsert로 멱등(FR-010), NOTI-002/003은 `read_at = coalesce(read_at, now)`로 멱등. `sent_at`은 종단 발송 시도 완료를 나타내며 되돌아가지 않는다. FCM 발송은 커밋 후라 도메인 전이를 롤백할 수 없다(FR-009). 알림 생성·전달마다 request id·유형·대상 id·기기 수·전달 결과 요약을 log하되 토큰 원문·정밀 위치·본문 개인정보는 남기지 않는다(FR-024). 시각은 서버·Asia/Seoul. | PASS |
| IV. 외부 의존성 실패 격리 | FCM 호출 실패는 도메인 처리와 분리(커밋 후 dispatch/BackgroundTasks)돼 전이를 막지 않는다(FR-009). 일시 오류는 0.5s→1.5s 백오프로 최대 2회 재시도(FR-027), 무효 토큰은 그 `device_sessions.fcm_token`만 `NULL`(FR-008), 한 토큰 실패가 다른 토큰·행 커밋을 막지 않는다. 대상 기기 0개는 실패가 아니라 `sent_at`만 찍고 목록으로 확인 가능(FR-007). `fcm_enabled=false`(자격 없음)면 발송을 건너뛰고 행은 정상 — 실패를 성공으로 숨기지 않고 `fcm_skipped`를 log한다. | PASS |
| V. 보안·소유권·최소 데이터 | NOTI-001/002/003은 `notification.user_id == principal`, DEV-001/002는 `device_session.user_id == principal AND client_device_id == deviceId`를 검증하고 타인 요청은 `403`/`404`(FR-023, SC-007). FCM payload와 `notifications.body`에 도메인 객체·후보 목록·정밀 위치·토큰을 넣지 않고 재조회 식별자와 짧은 문구만 담는다(FR-006, SC-004). FCM 서비스 계정 JSON은 `Settings` `SecretStr`/환경변수로 주입하고 log·저장소에 남기지 않는다. Android `google-services.json`은 `.gitignore`(G001에서 주입). 알림 보존 90일과 여행 논리 삭제 시 정리를 plan에서 정의했다(FR-030, constitution V "수집 전 보존·삭제 정책 정의"). | PASS |
| 교차 계약 review | **BE**: `api/app/services/detection/evaluator.py`(F008, 최초 INSERT 판별 `RETURNING` + `PLACE_CHANGE_SUGGESTION` hook, `evaluate_all_active` 반환 확장)와 `api/app/services/detection/__init__.py`(F006/F007, `register_event`·`_auto_confirm`·재질문 hook, dispatch의 `finalize_due_candidates` 호출)는 해당 Feature BE 담당 review; `api/app/services/auth.py::logout_device_session`(F001, `fcm_token=None` 추가)은 F001 담당 review; `migrations/env.py` 모델 import 목록 변경. **FE**: `trip/TripListScreen.kt`(F002, hs)·`progress/ActiveTravelScreen.kt`(F006, jy) 헤더에 알림 벨 추가, `auth/AuthRepository.kt`(F001) onSignedIn/refresh/logout hook, `MainActivity.kt` 푸시 딥링크, `alternative/AlternativeRepository.listDetections` 재사용(F009, jy)은 각 화면·모듈 원 담당자 review. NOTI·DEV DTO는 BE·FE 담당이 함께 확인한다. | PASS WITH REVIEW CONDITION |

**위반 없음.** `notification_dispatch` job은 새 스케줄러 프레임워크가 아니라 기존 `asyncio` 루프 패턴(`run_auth_cleanup`·`run_variable_detection`)의 세 번째 인스턴스이며, F011의 본질(푸시 전달)에 필요한 최소 구조다. Complexity Tracking 비움.

### Post-Design Re-check

Phase 1 산출물(data-model.md·contracts/notifications.openapi.yaml·quickstart.md) 작성 후 재평가함. 신규 위반 없음. `notifications`를 발송 큐로 재사용(`sent_at IS NULL`)하고 `notification_deliveries`를 만들지 않는 결정은 `er-schema.md` 14절과 일치하며 `sent_at` 의미 확장은 9.1절 설명 갱신으로 동기화한다(doc-sync). dispatch가 `finalize_due_candidates`를 호출해 자동 확정을 앞당기는 것은 되돌리기 창(`auto_finalize_at` 기준) 불변·사용자 유리이나 F006/F007 동작 시점 변경이므로 review 조건으로 남긴다. 자동 확정·재질문 알림이 dispatch tick(≤30초)에 의존하는 것은 F006/F007의 지연 설계에서 오는 상한이며 research R7에 기록했다.

## Project Structure

### Documentation (this feature)

```text
specs/011-notification/
├── spec.md
├── plan.md                              # 이 문서
├── research.md                          # Phase 0: R1~R15 결정
├── data-model.md                        # Phase 1: notifications 테이블·전이·DTO·Android 상태 모델
├── quickstart.md                        # Phase 1: BE 1~8, AND 1~7, 실서버 1~6 검증 절차
├── contracts/
│   └── notifications.openapi.yaml       # NOTI-001·002·003, DEV-001·002
├── checklists/requirements.md
└── tasks.md                             # Phase 2 ($speckit-tasks)
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── clients/
│   │   └── fcm.py                       # FcmClient: FCM HTTP v1 + pyjwt OAuth2, send() → FcmSendResult, FcmClientError
│   ├── models/
│   │   ├── __init__.py                  # + Notification 재노출
│   │   └── notification.py              # Notification 모델 (notifications 테이블)
│   ├── schemas/
│   │   ├── notification.py              # NotificationItem, ListData, MarkReadResult, MarkAllResult
│   │   └── device.py                    # FcmTokenRegisterRequest, FcmTokenRegisterResult
│   ├── services/
│   │   ├── notification/
│   │   │   ├── __init__.py              # NotificationService: create_place_change_suggestion / create_transition_check / create_transition_auto_confirmed / list / mark_read / mark_all_read
│   │   │   ├── dedup.py                 # dedup_key 구성 (유형별)
│   │   │   ├── messages.py              # 유형별 title·body 생성 (R14)
│   │   │   └── dispatch.py              # NotificationDispatchService: send_one (기기 토큰 조회·재시도·무효 토큰 null), tick
│   │   ├── detection/
│   │   │   ├── evaluator.py             # _store_detection: RETURNING(xmax=0) + 최초 INSERT 시 create_place_change_suggestion (F008 파일)
│   │   │   └── __init__.py              # register_event/_auto_confirm/decide_transition에 알림 hook (F006/F007 파일)
│   │   └── auth.py                      # logout_device_session: values(..., fcm_token=None) (F001 파일)
│   ├── api/v1/
│   │   ├── notifications.py             # NOTI-001·002·003 router
│   │   └── devices.py                   # DEV-001·002 router
│   ├── jobs/
│   │   ├── notification_dispatch.py     # run_notification_dispatch(session_factory, interval_seconds=30)
│   │   └── notification_cleanup.py      # run_notification_cleanup + cleanup_notifications(session, now=None) — 90일·논리 삭제 여행
│   ├── core/config.py                   # + fcm_enabled/fcm_project_id/fcm_service_account_json/fcm_request_timeout_seconds/notification_retention_days/notification_dispatch_interval_seconds/notification_cleanup_interval_seconds
│   └── main.py                          # notifications_router·devices_router 등록, dispatch·cleanup task 2개 lifespan 추가
├── migrations/
│   ├── env.py                           # 모델 import에 notification 추가
│   └── versions/011_create_notifications.py  # notifications 테이블·인덱스 4개·type CHECK, device_sessions fcm_token partial unique
├── .env.example                         # FCM_* 항목 추가
└── tests/
    ├── unit/test_notification_service.py
    ├── unit/test_fcm_client.py
    ├── unit/test_notification_dispatch.py
    ├── unit/test_notification_messages.py
    ├── contract/test_notification_contract.py
    ├── integration/test_notification_cleanup.py
    ├── integration/test_notification_migration.py
    └── fixtures/fcm/{send_ok,invalid_token,unavailable,oauth_token}.json

android/
├── build.gradle.kts                     # + com.google.gms.google-services 플러그인
├── app/
│   ├── build.gradle.kts                 # + firebase-bom, firebase-messaging; apply google-services
│   ├── google-services.json            # .gitignore (G001에서 주입)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # POST_NOTIFICATIONS, GilpickMessagingService (MESSAGING_EVENT)
│       │   └── java/com/gilpick/
│       │       ├── notification/
│       │       │   ├── NotificationApi.kt          # createNotificationRetrofit + NotificationService (NOTI-001/002/003, DEV-001/002) + DTO
│       │       │   ├── NotificationRepository.kt   # NotificationRepository(api, auth) + .default(context)
│       │       │   ├── NotificationUiState.kt      # Loading/Empty/Error/Content, NotifGroup, NotifItemUi, target
│       │       │   ├── NotificationListViewModel.kt
│       │       │   ├── NotificationListScreen.kt   # Figma NotificationsScreen + empty/error/loading
│       │       │   ├── NotificationRow.kt          # 행 composable (읽음/안 읽음)
│       │       │   ├── NotificationLabels.kt       # 날짜 그룹(KST)·상대 시각·유형→아이콘·목적지
│       │       │   ├── VariableMonitorScreen.kt    # Figma VariableMonitorScreen (US6)
│       │       │   ├── VariableMonitorViewModel.kt # AlternativeRepository.listDetections(status=ACTIVE) 재사용
│       │       │   ├── NotificationNavigation.kt   # NotificationListRoute, VariableMonitorRoute, notificationGraph(navController, onSessionExpired, onOpenDetection, onOpenProgress)
│       │       │   ├── GilpickMessagingService.kt  # onNewToken → FcmTokenSyncWorker; onMessageReceived → 채널 알림 + PendingIntent (백그라운드), 포그라운드 무표시
│       │       │   ├── FcmTokenSyncWorker.kt       # DEV-001 (durable, SessionRevocationWorker 패턴)
│       │       │   └── FcmTokenClearWorker.kt      # DEV-002 + deleteToken (durable)
│       │       ├── auth/AuthRepository.kt          # onSignedIn/performRefresh 성공 → FcmTokenSyncWorker.enqueue; logout() → FcmTokenClearWorker.enqueue (F001 파일)
│       │       ├── trip/TripListScreen.kt          # 헤더에 알림 벨 + onNotifications (F002 파일)
│       │       ├── progress/ActiveTravelScreen.kt  # 헤더에 알림 벨 + onNotifications (F006 파일)
│       │       └── MainActivity.kt                 # 알림 채널 생성, notification extras 파싱 → PendingNotificationTarget → NavHost 후 이동+재조회; notificationGraph 배선; TripRoute 헤더 콜백 배선
│       ├── test/java/com/gilpick/notification/
│       │   ├── NotificationListViewModelTest.kt
│       │   ├── NotificationRepositoryTest.kt
│       │   ├── VariableMonitorViewModelTest.kt
│       │   ├── FcmTokenSyncWorkerTest.kt
│       │   └── FakeNotificationService.kt
│       └── androidTest/java/com/gilpick/notification/
│           ├── NotificationListScreenTest.kt
│           ├── VariableMonitorScreenTest.kt
│           ├── NotificationNavigationTest.kt
│           ├── NotificationsScreenshotTest.kt
│           └── VariableMonitorScreenshotTest.kt
```

**Structure Decision**: 기존 `api/`(FastAPI 단일 레이아웃)와 `android/app` 구조를 그대로 쓴다. Backend는 F008 `services/detection/`처럼 `services/notification/` 하위 패키지에 생성 규칙·중복 키·문구·발송을 나누고 조정값은 `core/config.py` `Settings`에 모은다. 알림 생성 hook은 F008·F006/F007 서비스 파일에 최소 추가하고 발송은 새 `jobs/notification_dispatch.py` 루프로 분리한다(기존 `auth_cleanup`·`variable_detection` 루프와 동일 패턴). Android는 `progress`·`alternative` 패키지가 이미 커서 알림 목록·감지 목록·FCM 수신을 `com.gilpick.notification` 새 패키지로 모으고, 진입점(벨)과 딥링크만 기존 파일에 더한다. 감지 목록 화면은 F009 `AlternativeRepository`를 읽기로 재사용한다.

## Complexity Tracking

해당 없음(Constitution Check 위반 없음).
