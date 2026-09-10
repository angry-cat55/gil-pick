# F010 일정 변경 검증 가이드

구현이 `spec.md`를 만족하는지 확인하는 실행 절차다. 세부 계약은 [contracts/replacements.openapi.yaml](contracts/replacements.openapi.yaml), 상태 규칙은 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 참고한다.

## 사전 조건

- Backend: `api/` 로컬 실행, PostgreSQL/PostGIS, migration 적용 완료
- Android: AVD `Pixel_9_Pro`(API 36)
- **F009 병합 완료**: `com.gilpick.alternative` 패키지와 `alternativeGraph(onSelectPlace)` 콜백이 있어야 한다
- 검증용 여행 하나: 진행 중인 오늘 날짜, 아직 방문하지 않은 장소가 2개 이상, 그중 한 장소에 `ACTIVE` 감지 결과 하나

```bash
cd api && uv run alembic upgrade head && uv run uvicorn app.main:app --reload
```

## Backend

### BE 1. 미리보기는 아무것도 바꾸지 않는다 (US1)

1. 대상 여행의 일정과 경로를 조회해 `scheduleVersion`, 항목별 `placeId`, `routes` 활성 행을 기록한다.
2. REPL-001로 미리보기를 만든다.
3. 응답에 `comparison`의 네 항목이 `before`/`after` 쌍으로 오는지 확인한다.
4. 1의 조회를 다시 해 **`scheduleVersion`과 장소·경로가 하나도 바뀌지 않았는지** 확인한다(FR-001, SC-002).
5. `detections` 상태가 `ACTIVE` 그대로인지 확인한다.

### BE 2. 후보 식별자 검증과 직접 검색 (US1)

1. F009 ALT-001로 후보를 조회해 `candidateId` 하나를 얻고, 그 값으로 REPL-001을 호출해 성공하는지 확인한다.
2. 다른 감지 결과의 `candidateId`로 호출해 `400 INVALID_CANDIDATE`인지 확인한다.
3. 만료된 `candidateId`(15분 경과)로 호출해 `400 INVALID_CANDIDATE`인지 확인한다.
4. `candidateId` 없이 `placeId`만으로 호출해 **1과 같은 형식**의 응답을 받는지 확인한다(FR-004).

### BE 3. 미리보기 무효화와 만료 (US1)

1. 같은 감지 결과로 미리보기를 두 번 만든다.
2. 첫 번째 `previewId`로 승인해 `409 PREVIEW_SUPERSEDED`인지 확인한다(FR-006).
3. `PREVIEW_TTL_MINUTES`를 짧게 바꾼 설정으로 미리보기를 만들고 만료 후 승인해 `409 PREVIEW_EXPIRED`인지 확인한다(FR-005).

### BE 4. 승인의 원자성 (US2)

1. 유효한 미리보기를 승인한다.
2. 다음을 **모두** 확인한다.
   - `itinerary_items.place_id`가 대체 장소로 바뀌었고 **`item_id`·`sequence`·`planned_stay_minutes`·`transport_mode_to_next`는 그대로**다(FR-009, research 3절)
   - `trip_days.schedule_version`이 1 올랐다
   - 새 version의 `routes` 행이 활성이고 이전 행은 `HISTORICAL`이다
   - `place_replacements` 행이 생겼고 `undo_expires_at`이 승인 시각 + 30초다
   - `detections.status`가 `RESOLVED`다
   - PROG-001 응답에서 그 감지 결과가 배너 대상에서 빠졌다(FR-012)
3. 응답의 `routeStatus`가 `READY`인지 확인한다. 미리보기에서 계산을 마쳤으므로 다른 값이 나올 수 없다.

### BE 5. 승인 재검증 (US2)

원인별로 **일정이 하나도 바뀌지 않는지**를 매번 함께 확인한다(FR-010, SC-007).

| 상황 | 기대 |
|---|---|
| 미리보기 생성 후 F004로 그 날짜 일정을 수정 | `409 VERSION_CONFLICT` |
| 미리보기 생성 후 대상 항목을 도착·건너뜀 처리 | `409 ITEM_ALREADY_VISITED` |
| 미리보기 생성 후 감지 결과를 F009로 거절 | `409 DETECTION_NOT_ACTIVE` |
| 대체 장소가 그 사이 영업 종료 | `409 ALTERNATIVE_UNAVAILABLE` |
| 이미 승인한 미리보기를 다른 키로 재승인 | `409 ALREADY_APPROVED` |

### BE 6. 승인 멱등성 (US2)

같은 `Idempotency-Key`로 승인을 두 번 보내고, `place_replacements` 행이 하나뿐이며 `schedule_version`이 한 번만 올랐고 두 응답이 같은지 확인한다(FR-011, SC-003).

### BE 7. 되돌리기 (US3)

1. 승인 직후 REPL-004를 호출한다.
2. `place_id`가 원래 장소로 돌아왔고, `schedule_version`이 또 1 올랐고, 승인 전 경로가 다시 활성인지 확인한다(FR-016, SC-005).
3. 같은 요청을 한 번 더 보내 `200`이고 일정이 또 바뀌지 않는지 확인한다(FR-017).

### BE 8. 되돌리기 거절 (US3)

| 상황 | 기대 |
|---|---|
| 승인 후 30초 초과 | `409 UNDO_EXPIRED` |
| 승인 후 F004로 그 날짜 일정 수정 | `409 FOLLOW_UP_CHANGE_EXISTS` |

두 경우 모두 일정이 바뀌지 않아야 한다(SC-006).

### BE 9. 되돌리기의 감지 결과 복귀 (US3)

`research.md` 5절의 두 갈래를 모두 확인한다.

1. **충돌 없음**: 승인 → 되돌리기 → 그 감지 결과가 `ACTIVE`이고 `resolved_at`이 비었는지, PROG-001 배너에 다시 나타나는지 확인한다(FR-018).
2. **충돌 있음**: 승인 → F008 재평가로 같은 항목에 새 `ACTIVE` 감지 결과 생성 → 되돌리기. 되돌린 쪽이 `INVALIDATED`이고 새 쪽이 `ACTIVE`로 남으며, **`uq_detections_active_fingerprint` 위반 없이 되돌리기가 성공**하는지 확인한다. 응답의 `detectionRestored`가 `false`다.

### BE 10. 소유권 (constitution V)

다른 사용자의 token으로 REPL-001~004를 호출해 모두 `403 TRIP_FORBIDDEN`인지, 대상 데이터가 바뀌지 않았는지 확인한다(FR-021).

```bash
cd api && uv run pytest tests/contract/test_replacement_contract.py tests/integration/test_replacement_flow.py
```

## Android

### FE 1. F009에서 이어받기 (US1)

1. 진행 화면의 변수 경고 배너 → 대체 장소 화면 → 후보 하나 선택.
2. **미리보기 화면으로 넘어가고** 선택한 장소로 비교가 만들어지는지 확인한다.
3. F009 직접 검색으로 고른 장소에서도 같은 화면이 열리는지 확인한다.

### FE 2. 비교 표시 (US1, UI-001·UI-002)

- 지도에 기존 경로와 변경 경로가 함께 그려지고 **어느 쪽이 기존인지 색이 아니라 문구로도** 구분되는지
- 어떤 장소가 어떤 장소로 바뀌는지, 감지 이유가 보이는지
- 네 비교 항목이 이전/이후 값으로 나란히 보이고, 나아짐/나빠짐이 색만이 아니라 문구나 기호로도 구분되는지
- `closesAt`이 `null`인 미리보기에서 `정보 없음`으로 표시되고 값을 지어내지 않는지

### FE 3. 미리보기 상태 (UI-004)

- `loading`: 1초 전에는 표시하지 않고 이후 진행 표시(ui-guidelines 9절)
- `error`: 경로 계산 실패(`502`/`504`) 시 원인과 `다시 시도`·`다른 후보 보기`, **기존 일정이 그대로임을 알리는 문구**
- `empty` 상태가 없는 이유는 후보 없음을 F009가 처리하기 때문이다

### FE 4. 승인 (US2, UI-005)

1. `변경 승인`을 누르는 동안 행동이 잠기고 진행이 보이는지 확인한다.
2. 아래 원인별로 **서로 다른 안내와 다음 행동**이 나오는지 확인한다(SC-007).

| 원인 | 다음 행동 |
|---|---|
| `VERSION_CONFLICT` | 다시 만들기 |
| `PREVIEW_EXPIRED` | 다시 만들기 |
| `ITEM_ALREADY_VISITED` | 후보 목록으로 |
| `ALTERNATIVE_UNAVAILABLE` | 후보 목록으로 |
| 통신 실패 | 다시 시도 |

### FE 5. 되돌리기와 토스트 우선순위 (US3, UI-006·UI-006a)

1. 승인 성공 → 진행 화면으로 돌아가 바뀐 장소가 목록에 있는지 확인한다(SC-009).
2. 되돌리기 안내가 **F007 자동 확정 토스트와 같은 자리·같은 형식**으로 뜨고 남은 시간이 보이는지 확인한다.
3. 되돌리기를 눌러 원래 장소로 돌아오는지 확인한다.
4. **우선순위**: 자동 확정 되돌리기가 살아 있는 상태에서 장소 변경을 승인한다. 두 안내가 **겹쳐 보이지 않고** 장소 변경 쪽이 먼저 보이는지, 30초가 지난 뒤 자동 확정 토스트가 남은 시간만큼 이어서 보이는지 확인한다(SC-008).
5. 30초가 지나면 되돌리기 행동이 사라지고 무엇이 바뀌었는지는 계속 보이는지 확인한다.
6. 되돌리기 실패(`UNDO_EXPIRED`·`FOLLOW_UP_CHANGE_EXISTS`) 시 원인과 **일정 편집으로 바꿀 수 있다는 안내**가 나오는지 확인한다(FR-019, UI-007).

### FE 6. 자동 감지 연동 (SC-009)

승인 후 PROG-001의 감지 대상이 바뀐 일정을 따르고, 없어진 장소의 지오펜스가 해제되는지 확인한다(FR-013).

### FE 7. 접근성과 적응형 (UI-008·UI-009·UI-010)

1. 미리보기·승인·되돌리기의 모든 행동이 터치 영역 48dp 이상이고 8dp 이상 떨어져 있는지 확인한다.
2. 360dp 폭과 시스템 글자 최대 배율에서 비교 항목의 항목명·이전 값·이후 값과 행동 문구가 잘리지 않는지 확인한다.

```bash
adb shell wm size 1080x2400 && adb shell wm density 480
adb shell settings put system font_scale 2.0
# 확인 후 원복
adb shell wm size reset && adb shell wm density reset
adb shell settings put system font_scale 1.0
```

3. 미리보기 `content`, 미리보기 `error`, 승인 실패, 승인 직후 되돌리기 가능, 되돌리기 만료 상태를 screenshot으로 남긴다(UI-010).

```bash
cd android && ./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.replacement
```

## 종단간 확인

1. 로컬 API + AVD로 오늘 날짜 진행을 시작하고, F008 감지 결과가 하나 생기게 한다.
2. 진행 화면 배너 → 대체 장소 화면 → 후보 선택 → 미리보기에서 비교 확인.
3. `변경 승인` → 진행 화면에 바뀐 장소와 되돌리기 안내가 보이는지 확인.
4. 되돌리기를 눌러 원래 장소와 배너가 함께 돌아오는지 확인.
5. 다시 승인하고 30초를 기다려 되돌리기가 사라지는지, 일정 편집으로 바꿀 수 있음이 안내되는지 확인.

## Android 검증 기록 (#353 T036)

2026-09-10, `main` 096a6b3, jy 수행(원 담당 hs). AVD `gilpick_api36`(ATD, 계측·screenshot)과 `gilpick_api36_play`(API 36, 실서버). 로컬 API: docker postgres + `alembic upgrade head`(011) + uvicorn 127.0.0.1:8005 → `adb reverse tcp:8000 tcp:8005`, 실제 TourAPI·Google·TMAP 키.

| 항목 | 결과 |
|---|---|
| `testDebugUnitTest` | 539 통과 |
| `connectedDebugAndroidTest` `com.gilpick.replacement` | 32 통과 (ReplacementNavigationTest 4 · RoutePreviewScreenTest 9 · RoutePreviewApproveTest 10 · RoutePreviewScreenshotTest 9) |
| `connectedDebugAndroidTest` `com.gilpick.progress` | 128 통과 (ActiveTravelScreenshotTest 50 · ReplacementUndoTest 11 · UndoToastTest 8 외) |
| FE 7-1 48dp | `RoutePreviewScreenTest`·`RoutePreviewApproveTest`·`ReplacementUndoTest`의 `assertHeightIsAtLeast(48.dp)`(뒤로·승인·다른 후보 보기·다시 시도·다시 로그인·실패 다음 행동·되돌리기) |
| FE 7-2 360dp + 2.0 | `replacement_*_360dp_fontscale2.png` 7장. 항목명은 두 줄로 접히고 이전·이후 값과 `변경 승인`·`다른 후보 보기`·`되돌리기`·실패 문구 모두 잘림 없음. 실패 블록과 행동은 첫 화면 아래라 스크롤해 찍음 |
| FE 7-3 screenshot 5장 | `replacement_preview_content`·`replacement_preview_error`·`replacement_approve_failed`·`replacement_undo_toast`·`replacement_undo_expired`(+`_360dp_fontscale2`, `no_closes_at`, `approving`, `approve_failed_unavailable`, `preview_actions`) — `/sdcard/Android/data/com.gilpick/files/screenshots/` |

FE 2·3·4·5(표시·상태·승인 실패 5원인·되돌리기·우선순위·실패 안내)는 위 계측 test로 확인했다. 실서버는 다음과 같다.

- **BE 버그 #394**: REPL-001이 `500 INTERNAL_ERROR`(`InvalidRequestError: Can't operate on closed transaction`, `replacement.py`의 `session.rollback()` × 3). 앱은 `비교를 만들 수 없어요 · 잠시 후 다시 시도해 주세요 · 기존 일정은 그대로예요 · 다시 시도하기 · 다른 후보 보기`로 정상 처리(FE 3 `error`). 아래 실서버 항목은 로컬에서 그 세 줄만 비활성화한 서버로 진행했다(커밋 안 함).
- FE 1: 진행 화면 배너(`명동 카페거리 카페 도착 예정 시각에 영업이 끝나요 + 매우 혼잡`) → 대체 장소 후보 2곳 → `경로 비교` → 미리보기. REPL-001 200(1.0초). `다시 시도하기`로 REPL-003 폐기 후 재생성도 확인.
- FE 2 실서버: Naver 지도에 기존(회색)·변경(파랑) 경로 + `기존`·`변경` 범례, `명동 카페거리 카페 → 더 스팟 패뷸러스`, 감지 이유, 이동 시간 `26분 → 29분 ↑`(접근성 문구 `…나빠짐`), 이동 거리 `1.9km → 2.1km ↑`, 도착 예정 `17:45 → 17:49`, 마감 시간 `정보 없음 → 정보 없음`(closesAt null).
- FE 4·5-1: `변경 승인` → REPL-002 200 → 진행 화면. 일정 2번 항목이 같은 `itemId`로 `더 스팟 패뷸러스`, 배너에서 그 감지가 빠짐, `다음 장소`가 새 장소.
- FE 5-2·5-3: 토스트 `장소가 더 스팟 패뷸러스(으)로 변경되었습니다 · N초 · 되돌리기`(F007 토스트와 같은 자리·형식) → `되돌리기` → REPL-004 200 → 원래 장소·배너(`ACTIVE`) 복귀, DB `undone_at` 기록.
- FE 5-5·5-6: 재승인 후 서버 만료(30초) 뒤 `되돌리기` → `409 UNDO_EXPIRED` → `되돌릴 수 있는 시간이 지났어요. 일정 편집에서 바꿀 수 있어요.`, 일정 그대로. 재조회 후 PROG-001 `undoableReplacement=null`이라 토스트 본문은 사라진다.
- FE 6: 1번 도착·출발 처리로 2번을 감지 대상으로 만든 뒤 승인 → PROG-001 `detectionTargets`가 같은 `geofenceId`(`{itemId}:ARRIVAL`)로 새 좌표(37.5624, 126.9826)를 주고 앱이 승인 직후 진행을 재조회. 지오펜스 실제 재등록은 `GeofenceManager.sync`(값이 바뀐 대상 재등록)와 unit test로 갈음.

### 관찰 (수정 안 함)

- 토스트 남은 시간은 기기 시계로 계산한다. AVD 시계가 호스트보다 37초 느려 `65초`부터 시작했고, 서버가 만료한 뒤에도 `10초`가 남아 있었다(그 덕에 위 `UNDO_EXPIRED`를 실서버로 볼 수 있었다). 응답 시각 기준 보정은 hs 판단.
- 360dp·2.0에서 감지 이유·토스트 본문이 음절 단위로 접힌다(`매/우`, `창/덕궁(으)로`). F007 토스트와 같은 현상, 잘림은 아님.
- 실서버 미리보기 `closesAt`은 TourAPI 장소라 양쪽 `정보 없음`. `마감 시간` 실제 값은 Google 장소 후보가 있어야 보인다.

### 미실행

- FE 5-4(자동 확정 되돌리기와 동시 표시)는 실서버에서 5분 무응답 자동 확정을 함께 만들지 않아 `ReplacementUndoTest`·screenshot(`replacement_undo_toast`는 두 되돌리기가 동시에 살아 있는 fixture)으로만 확인.
- 승인 실패 5원인 실서버 유발(`VERSION_CONFLICT` 등)은 T037(#354) 범위로 두고 계측 test로만 확인.
- `Pixel_9_Pro` AVD가 없어 같은 API 36 image로 수행. 실기기·TalkBack 낭독은 하지 않았다.

실행 명령(요지):

```
android\gradlew.bat --offline -q :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5556 android\gradlew.bat --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.replacement
ANDROID_SERIAL=emulator-5556 android\gradlew.bat --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.progress
adb -s emulator-5556 shell am instrument -w -r -e class com.gilpick.replacement.RoutePreviewScreenshotTest,com.gilpick.progress.ActiveTravelScreenshotTest com.gilpick.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 pull /sdcard/Android/data/com.gilpick/files/screenshots
android\gradlew.bat --offline -q -PGILPICK_API_BASE_URL=http://127.0.0.1:8000/api/v1/ :app:assembleDebug
adb -s emulator-5554 reverse tcp:8000 tcp:8005
```
