# Quickstart: F007 위치 기반 감지 검증

구현이 끝난 뒤 이 순서로 확인한다. Backend 시나리오는 자동 test로, Android 시나리오는 계측 test와 실제 AVD로 검증한다.

## 사전 준비

- Backend: `docker compose up -d db`(PostgreSQL/PostGIS), `alembic upgrade head`(migration 006 포함), `uvicorn app.main:app --reload`
- Android: AVD `Pixel_9_Pro`(API 36). 로컬 API를 쓰려면 `adb reverse tcp:8000 tcp:8000`
- 위치 주입: `adb emu geo fix <경도> <위도>` 또는 Extended Controls의 Location
- F006이 먼저 동작해야 한다. 오늘 날짜에 장소가 2곳 이상 저장된 여행을 만들고 `오늘 여행 시작`까지 끝낸 상태에서 시작한다.

## Backend 검증

### BE 1. 도착 후보 생성과 확인

1. 진행 중인 날짜의 `EN_ROUTE` 항목에 대해 `DWELL` 이벤트를 등록한다(PROG-003).
2. 응답의 `accepted=true`, `candidate.type=ARRIVAL`, `candidate.autoFinalizeAt`이 생성 시각 + 무응답 대기 시간인지 확인한다.
3. `POST /progress/transitions/{id}/decisions`에 `CONFIRM`을 보낸다(PROG-004).
4. 그 항목이 `ARRIVED`가 되고 뒤 항목의 ETA가 다시 계산되는지, `undoDeadline`이 **null**인지 확인한다(FR-020).

### BE 2. 거절과 재질문 상한

1. 도착 후보에 `NOT_ARRIVED`를 보낸다. transition이 `CANCELLED`가 되고 항목은 `EN_ROUTE`로 남는지, `nextPromptAt`이 재질문 간격 뒤인지 확인한다.
2. `nextPromptAt` 이전에 같은 `DWELL`을 다시 보내면 `accepted=false`, `rejectionReason=DETECTION_PAUSED`인지 확인한다.
3. `nextPromptAt` 이후에 보내면 두 번째 후보가 생기는지 확인한다.
4. 두 번째 후보도 거절한 뒤 다시 보내면 `rejectionReason=PROMPT_LIMIT_REACHED`이고 후보가 생기지 않는지 확인한다(FR-008).

### BE 3. 무응답 자동 확정과 되돌리기

1. 도착 후보를 만든 뒤 응답하지 않고 `auto_finalize_at`을 지난 시각으로 만든다(테스트에서는 서버 시각을 고정하거나 후보의 시각을 조작한다).
2. 진행 조회(PROG-001)를 호출한다. 지연 확정으로 항목이 `ARRIVED`가 되고 transition이 `AUTO_CONFIRMED`, `undoDeadline`이 확정 시각 + 되돌리기 시간인지 확인한다.
3. `POST /progress/transitions/{id}/undo`로 되돌린다(PROG-005). `restoredItems`가 확정 직전 상태로 복원되고 ETA가 되돌아가는지, `detectionResumeAt`이 있는지 확인한다.
4. `undoDeadline`을 지난 뒤 되돌리기를 호출하면 `409 UNDO_WINDOW_EXPIRED`인지 확인한다(FR-018).

### BE 4. 되돌린 뒤 감지 재개

1. BE 3에서 되돌린 항목에 같은 `DWELL`을 즉시 보낸다. `accepted=false`, `rejectionReason=DETECTION_PAUSED`인지 확인한다.
2. `detectionResumeAt` 이후에 보내면 후보가 다시 생기고, 그 질문도 장소별 상한에 포함되는지 확인한다(FR-017a).

### BE 5. 출발 감지와 재진입·오판 대응

1. `ARRIVED` 항목에 `EXIT` 이벤트를 보낸다. `candidate.type=DEPARTURE`이고 `allowedDecisions`가 `CONFIRM`·`STILL_HERE`인지 확인한다.
2. `REENTER`를 보낸다. 응답의 `cancelledTransitionId`가 그 후보이고 상태가 그대로인지 확인한다(FR-012).
3. 다시 `EXIT` → `STILL_HERE`로 응답한다. 이후 같은 항목의 `EXIT`가 `rejectionReason=DEPARTURE_DETECTION_STOPPED`로 거부되는지 확인한다(FR-013).
4. 다른 항목에서 `EXIT` 후 무응답으로 자동 확정하면 현재 항목이 `COMPLETED`, 다음이 `EN_ROUTE`가 되고 남은 ETA가 재계산되는지 확인한다(FR-016).
5. 같은 항목의 자동 출발 확정을 두 번 되돌린 뒤 `EXIT`를 보내면 그 날짜 동안 후보가 생기지 않는지 확인한다(FR-017a).

### BE 6. 가까운 다음 장소

1. 이전 항목이 `ARRIVED`인 상태에서 `EXIT` 없이 다음 항목의 `DWELL`을 보낸다.
2. 확정 시 `transition_type=COMPOSITE`이고 `affectedItems`에 이전 항목 `ARRIVED→COMPLETED`, 다음 항목 `EN_ROUTE→ARRIVED`가 함께 담기는지 확인한다(FR-009).
3. 되돌리면 두 항목이 함께 복원되는지 확인한다(US2 Acceptance 4).

### BE 7. 이벤트 검증과 멱등

1. `accuracyMeters`를 기준보다 크게 보낸다. `accepted=false`, `rejectionReason=LOW_ACCURACY`이고 행은 저장되는지 확인한다.
2. `occurredAt`을 유효 시간보다 오래된 값으로 보낸다. `rejectionReason=STALE`인지 확인한다.
3. 같은 `eventId`로 두 번 보낸다. 두 번째도 `200`이고 최초와 같은 결과이며 후보가 하나만 생기는지 확인한다(FR-004).
4. 시작 전·완료된 날짜에 이벤트를 보내면 `rejectionReason=DAY_NOT_IN_PROGRESS`인지 확인한다(FR-001·FR-022).
5. 다른 사용자의 여행에 보내면 `403`인지 확인한다.
6. 확인 응답·되돌리기를 같은 `Idempotency-Key`로 재전송하면 한 번만 적용되는지 확인한다.

### BE 8. 후보 무효화

1. 도착 후보가 살아 있는 동안 F006 수동 전환으로 그 항목을 `ARRIVED`로 바꾼다. 후보가 `CANCELLED`가 되고 자동 확정이 일어나지 않는지 확인한다(FR-010).
2. 후보가 살아 있는 동안 F004에서 그 장소를 일정에서 삭제한다. 후보가 함께 사라지는지 확인한다.
3. 당일 완료 후 진행 조회의 `detectionTargets`가 빈 배열인지 확인한다(FR-022).

### BE 9. 감지 대상 목록

1. 진행 조회 응답의 `detectionTargets`에 `EN_ROUTE` 항목의 `ARRIVAL` 하나와 `ARRIVED` 항목의 `DEPARTURE` 하나만 있는지 확인한다.
2. `STILL_HERE`·상한 도달·되돌리기 쉬는 시간 중인 대상이 목록에서 빠지는지 확인한다.
3. 상태 전환 후 목록이 새 대상으로 바뀌는지 확인한다.

## Android 검증

### FE 1. 확인 시트

1. 진행 화면에서 서버가 `pendingCandidate`를 내려주는 상태를 만들고 도착 확인 시트가 뜨는지 확인한다.
2. 시트에 장소명, 감지 근거, 자동 확정까지 남은 시간, `도착했어요`·`아직이에요`가 있는지 확인한다(UI-001).
3. 응답을 보내는 동안 두 버튼이 잠기고 진행 표시가 뜨는지, 실패하면 원인과 다시 시도가 나오며 후보가 유지되는지 확인한다(UI-006).
4. 출발 후보에서는 `출발했어요`·`아직 머무는 중`이 나오는지 확인한다(UI-002).
5. 시트를 닫고 F006 수동 진행 행동을 그대로 쓸 수 있는지 확인한다(UI-007).

### FE 2. 되돌리기 토스트

1. 서버가 `undoableTransition`을 내려주는 상태에서 토스트가 뜨고 바뀐 내용·남은 시간·`되돌리기`가 보이는지 확인한다(UI-003).
2. `되돌리기`를 누르면 상태가 복원되고 토스트가 사라지는지 확인한다.
3. 남은 시간이 0이 되면 토스트가 사라지고, 그 뒤 누를 수 없는지 확인한다.

### FE 3. 자동 전환 표시

1. 자동으로 확정된 항목의 목록 행에 자동 처리됐음이 문구로 드러나는지, 색만으로 구분하지 않는지 확인한다(UI-004).

### FE 4. 지오펜스 등록

1. 진행 조회 응답의 `detectionTargets`가 바뀔 때만 등록·해제가 일어나는지 확인한다.
2. 당일 완료 후 등록된 지오펜스가 모두 해제되는지 확인한다.
3. 앱을 종료한 뒤 `adb emu geo fix`로 대상 장소 좌표를 주입해 지오펜스가 발화하고 이벤트가 서버에 도달하는지 확인한다.

### FE 5. 권한과 축소 동작

1. 백그라운드 위치 권한을 거부한 상태에서 진행을 시작한다. 자동 감지가 꺼지고 수동 진행이 모두 동작하는지 확인한다(US4).
2. 진행 화면에 자동 감지가 꺼진 원인과 켜는 방법이 안내되는지, 무시하고 진행할 수 있는지 확인한다(UI-005).
3. 진행 도중 권한을 회수한다. 자동 감지만 멈추고 이미 확정된 상태는 그대로인지 확인한다.

### FE 6. 접근성과 적응형

1. 확인 시트와 되돌리기 토스트의 터치 영역이 48dp 이상이고 8dp 이상 떨어져 있는지 확인한다(UI-008).
2. 360dp 폭과 시스템 글자 최대 배율에서 시트의 질문·근거·남은 시간·두 행동, 토스트의 문구·남은 시간·행동이 잘리지 않는지 확인한다(UI-009).
3. 도착 확인·출발 확인·자동 확정 후 되돌리기·되돌리기 만료·자동 감지 꺼짐 상태를 screenshot으로 남긴다(UI-010).

## 종단간 확인

1. 로컬 API + AVD로 오늘 날짜 진행을 시작한다.
2. 첫 장소 좌표를 주입해 도착 확인 시트가 뜨는 것까지 확인하고 `도착했어요`로 확정한다.
3. 장소 밖 좌표를 주입해 출발 확인 시트를 띄우고 응답하지 않은 채 대기해 자동 출발과 되돌리기 토스트를 확인한다.
4. 되돌린 뒤 같은 자리에서 쉬는 시간 동안 재확정이 없고, 그 시간이 지나면 다시 물어보는지 확인한다.
5. 마지막 장소 도착을 확정해 당일 완료와 감지 종료를 확인한다.

## Android 검증 기록 (#269 T036, 2026-09-09, jy)

환경: `main` ca924f3(BE #300~#304, FE #296~#306 병합 후), AVD `gilpick_api36_play`(API 36, 1080x2400/420dpi, Play Services 포함; `Pixel_9_Pro` 대신 같은 API 36 image). 로컬 API는 docker postgres + `alembic upgrade head`(007·008) + uvicorn 127.0.0.1:8001 → `adb reverse tcp:8000 tcp:8001`.

### 자동 test·build

- `testDebugUnitTest` 392 통과, `assembleDebug`·`assembleDebugAndroidTest` 성공.
- `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.progress` 100 통과: ActiveTravelScreenTest 21 · ActiveTravelScreenshotTest 44 · ConfirmSheetTest 14 · UndoToastTest 8 · DetectionPermissionTest 10 · ProgressNavigationTest 3.
- quickstart Android 항목 대응: FE 1(ConfirmSheetTest), FE 2·FE 3(UndoToastTest, ActiveTravelScreenTest `자동 처리`), FE 4-1·4-2(unit `GeofenceManagerTest` 차이 반영·빈 목록 해제), FE 5(DetectionPermissionTest), FE 6-1(`assertHeightIsAtLeast(48.dp)` 시트 2·토스트 1·안내 2).

### FE 6 screenshot (UI-009·UI-010)

`ActiveTravelScreenshotTest`에 12건 추가(총 44건): 도착 확인 시트·출발 확인 시트·응답 실패 시트·자동 확정 후 되돌리기·되돌리기 만료·자동 감지 꺼짐 × (기본, 360dp + 글자 배율 2.0). 확인 시트는 별도 window라 `ConfirmSheetContent`를 inline으로 그렸다. PNG는 `/sdcard/Android/data/com.gilpick/files/screenshots/detection_*.png`에서 pull했다.

- 360dp·2.0에서 시트의 질문·근거·남은 시간·두 행동, 토스트의 문구·남은 시간·`되돌리기`, 꺼짐 안내의 원인·방법·두 행동이 모두 보인다(잘림 없음).
- 남은 관찰(수정 안 함): 360dp·2.0에서 되돌리기 토스트 문구가 `북촌한옥 / 마을 도착 / 으로 자동 / 처리했어 / 요`처럼 음절 단위 5줄로 접힌다. `240초`와 `되돌리기`가 한 줄 폭의 절반 가까이 차지하기 때문이다. 시트 근거 `오 / 후 2:33`, 제목 `도착 / 하셨나요?`도 음절 단위로 접힌다. 읽기에는 지장 없음.

### FE 4-3 앱 종료 상태의 지오펜스 발화

여행 `F007 지오펜스`(9/9, 도보 3곳: 경복궁 → 북촌한옥마을 → 노블관광호텔 인사동)를 API로 만들고 시작(startLocation 37.57, 126.97). `pm grant`로 FINE·COARSE·BACKGROUND_LOCATION 허용.

| 단계 | 확인 내용 | 결과 |
|---|---|---|
| 등록 | 진행 화면 진입 → PROG-001 `detectionTargets` = 경복궁 `ARRIVAL`(300m, dwell 5분) 1건 → `gilpick_detection_session` prefs에 tripId·date 저장(등록 성공 뒤에만 저장됨), 꺼짐 안내 없음 | 통과 |
| 앱 종료 | HOME → `am kill com.gilpick` → `pidof` 없음, `stopped=false`(force-stop 아님, PendingIntent 유지) | 통과 |
| 발화 | `adb emu geo fix 126.9770 37.5796`를 15초마다 주입(13:21:36부터) → 13:26:07 `ActivityManager: Start proc … for broadcast {com.gilpick/.progress.GeofenceReceiver}` → 13:26:11 `POST …/progress/events` 200 → PROG-001 `pendingCandidate` ARRIVAL(dwellMinutes 5, accuracyMeters 100.0, autoFinalizeAt +5분) | 통과 (주입 후 4분 31초) |
| 시트(FE 1 실서버) | 앱 재실행 → `경복궁에 도착하셨나요?` · `이 근처에서 5분 머무는 중 · 오후 1:26 감지` · `1분 뒤 자동으로 도착 처리돼요` · `네, 도착했어요`·`아직이에요` | 통과 |
| 자동 확정·토스트(FE 2·3 실서버) | 응답하지 않고 대기 → 13:31:10 AUTO_CONFIRMED → 카드 `현재 장소` 경복궁, 토스트 `경복궁 도착으로 자동 처리했어요 · 250초 · 되돌리기`, 1번 행 `자동 처리` 표시, 서버 targets가 `DEPARTURE`(400m)로 교체 | 통과 |
| 되돌리기 | `되돌리기` → 서버 v3, 1번 `EN_ROUTE`·ETA 복원, 토스트 사라짐, `detectionTargets` `[]`(재개 대기) → prefs 비워짐(등록 해제) | 통과 |

- 관찰: emulator `geo fix` 위치의 accuracy가 정확히 100.0m로 올라와 `MAX_ACCURACY_METERS`(100, 초과 시 거부) 경계에 걸린다. 실기기와 무관한 emulator 특성이지만 정확도 기준을 낮추면 emulator 검증이 막힌다.
- 미실행: 출발 후보(EXIT)·재진입·`STILL_HERE`·당일 완료 후 전체 해제의 실서버 흐름은 T037(#270) 종단간 검증 범위라 여기서 하지 않았다(UI는 ConfirmSheetTest·unit test로 확인). FE 5-3(진행 도중 권한 회수)은 `DetectionPermissionTest`와 `ProgressViewModelTest`(권한 판정 → `detectionOff`)로 대신했다. 실기기·`Pixel_9_Pro` AVD는 없어 같은 API 36 image `gilpick_api36_play`로 수행했다.
