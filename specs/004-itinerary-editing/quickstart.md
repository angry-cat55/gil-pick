# Quickstart: 일정 구성 검증

## 사전 조건

- F002·F003 구현이 `main`에 병합되어 있고 로컬 API(docker compose postgres + uvicorn)가 뜬다. 절차는 F002 quickstart와 `specs/003-place-search/tasks.md` T032 실서버 기록을 따른다.
- `api/.venv`에 `alembic upgrade head`로 migration `003_create_itinerary_tables`까지 적용한다.
- Android는 `-PGILPICK_API_BASE_URL=http://127.0.0.1:8000/api/v1/` + `adb reverse tcp:8000 tcp:8000`으로 로컬 API에 붙인다.
- 계약은 [contracts/itinerary.openapi.yaml](contracts/itinerary.openapi.yaml), 데이터 규칙은 [data-model.md](data-model.md)를 기준으로 한다.

## Backend 자동 검증

```powershell
api\.venv\Scripts\python.exe -m pytest api/tests/contract/test_itinerary_contract.py api/tests/unit/test_itinerary_service.py api/tests/integration/test_itinerary_flow.py api/tests/contract/test_trip_contract.py -q
```

필수 시나리오:

1. 저장된 적 없는 날짜 조회는 `200`, `version 0`, 빈 `items`, `routeStatus NOT_CALCULATED`, `route null`.
2. `version 0` 저장은 `201`로 `trip_days`·`places`·`itinerary_items`를 만들고 `version 1`을 돌려준다. 같은 `Idempotency-Key`로 재전송하면 항목이 늘지 않고 version도 오르지 않는다.
3. 순서 빈틈·중복, 체류 시간 45분·0분·390분, 마지막 장소 이동 수단 있음, 중간 장소 이동 수단 없음, 11곳, 좌표 없는 새 장소는 각각 `422 INVALID_ITINERARY`와 `violations[]`.
4. 기간 밖 날짜는 `404`, 다른 사용자의 여행은 `403`, 삭제된 여행은 `404`.
5. 이전 version으로 저장하면 `409 VERSION_CONFLICT`. 결과 상태가 현재와 같은 저장은 version을 올리지 않는다.
6. 같은 `tourapi:` 장소를 두 날짜에 저장해도 `places` 행은 하나다. 같은 날짜에 같은 장소를 두 번 넣으면 항목은 둘이다.
7. fixture로 `status COMPLETED` 항목을 만든 뒤 장소 교체·이동 수단·순서 변경·삭제는 `409 ITINERARY_ITEM_LOCKED`, 체류 시간 변경은 `200`.
8. ITIN-003은 여행 기간의 모든 날짜를 순서대로 돌려주고 빈 날짜도 포함한다.
9. F002 PATCH: 3일차에 항목이 있는 여행을 2일로 줄이면 `409 CONFIRMATION_REQUIRED`·`deletedItemCount 1`, `confirmDeleteOutOfRangeItems true`면 `200`이고 3일차 `trip_days`·항목이 사라지며 1~2일차는 그대로다. 기간 밖 항목이 없으면 확인 없이 `200`.
10. `python -m compileall -q api/app api/tests`, `git diff --check`.

## Android 자동 검증

```powershell
android\gradlew.bat --offline -q :app:testDebugUnitTest --tests "com.gilpick.itinerary.*" --tests "com.gilpick.trip.*" :app:assembleDebug
android\gradlew.bat --offline -q :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.itinerary
```

필수 시나리오:

1. `ItineraryEditViewModel`: 검색 결과 추가 시 끝에 붙고 직전 항목 이동 수단이 시트 값으로 설정, 첫 항목이면 무시. 위·아래 이동, 삭제 후 순서 재부여, 체류 시간 30분 단위·범위, 10곳에서 추가 비활성.
2. 저장: `sequence` 1..N 부여, `Idempotency-Key` 1회 생성, 409 시 최신 version으로 최대 2회 재저장, 연속 실패 시 `Failed`와 초안 유지.
3. 회전·프로세스 재생성 뒤 초안 유지(`SavedStateHandle`).
4. Compose: 편집 화면 4상태, 취소 확인 대화상자(변경 없으면 바로 닫힘), 체류 시간 대화상자, 이동 수단 시트(체류 시간 조절 없음), 처리된 항목의 삭제·`변경` 숨김, `장소 추가` 비활성과 안내, 48dp 터치 영역.
5. 여행 상세: 날짜별 목록·빈 날짜 표시, 날짜 헤더 `장소 추가`가 그 날짜의 편집 화면으로 `openSearch`와 함께 이동, 일정 조회 실패가 여행 정보와 독립.
6. F002 수정 화면: 409 `CONFIRMATION_REQUIRED` 수신 시 `삭제될 장소 N곳` 대화상자, 동의 시 `confirmDeleteOutOfRangeItems true` 재요청, 취소 시 미저장.
7. Navigation: 편집 → 검색 → 상세 → `일정에 추가` → 편집(항목 추가됨), 시트 취소 → 편집(변화 없음).

## 실제 앱 수동 검증 (AVD `gilpick_api36_play`, 로컬 API)

1. 여행 생성 → 상세 → `일정 편집` → `장소 추가` → 경복궁 검색 → `+` → 대중교통·90분 → 돌아와 항목 1개 → 저장 → 상세에 1일차 1곳.
2. 장소 2개 더 추가 → 순서 이동 버튼과 손잡이 끌기로 순서 변경 → 체류 시간 120분 → 이동 수단 도보 → 저장 → 재조회로 확인.
3. 두 번째 세션(다른 access token)으로 같은 날짜를 먼저 저장한 뒤 첫 세션에서 저장 → 안내 없이 저장 완료, 최종 상태가 첫 세션 초안과 같음(서버 로그에 409 → PUT 200 순서).
4. 10곳 채우면 `장소 추가` 비활성과 안내.
5. 기간 축소: 3일차에 항목이 있는 여행을 2일로 → 대화상자 → 동의 → 3일차 사라짐. 취소 → 기간 유지.
6. 360dp(`wm density 480`)·font scale 2.0에서 편집 화면·상세 일정 목록 잘림 없음. 스크린샷을 PR에 기록한다.

## 문서 동기화 확인

- `docs/design/api-spec.md` 5.1·ITIN-001·002에 `place` 스냅샷, `staySource`, `routeStatus NOT_CALCULATED`, ITIN-003, `422 INVALID_ITINERARY`·`409 ITINERARY_ITEM_LOCKED`, TRIP-004의 `deletedItemCount` 실제 계산을 반영한다.
- `docs/design/er-schema.md`는 변경하지 않는다(5.2~5.4 그대로 구현). 변경이 생기면 같은 PR에서 고친다.
- Figma: 이동 수단 시트의 체류 시간 조절 제거, 순서 변경 손잡이·버튼, 여행 상세 날짜 헤더 `장소 추가`, 여행 수정 삭제 확인 대화상자 반영 여부를 구현 전에 확인한다.

## Android 검증 기록 (T034, 2026-09-07, jy)

`origin/main`(951c3be, #228 merge 후) 기준, branch `test/jy-itinerary-final-verification`.

| 항목 | 명령·방법 | 결과 |
|---|---|---|
| unit test·build | `android\gradlew.bat --offline -q :app:testDebugUnitTest :app:assembleDebug` | 통과 251건 (itinerary 37, trip 101, place 45, auth 68), build 성공 |
| itinerary UI·screenshot·navigation | `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.itinerary` (AVD `gilpick_api36`) | 통과 36건 (`ItineraryEditScreenTest` 19, `ItineraryEditScreenshotTest` 14, `ItineraryNavigationTest` 3) |
| trip UI·flow | `...package=com.gilpick.trip` | 통과 61건 |
| 필수 KDoc | `com.gilpick.itinerary` 최상위 public 선언 전수 확인(script) | 누락 없음 |
| 수동 항목 6 (360dp·글자 2.0) | screenshot 14장: 4상태(loading/empty/error/content), 10곳·긴 장소명, 처리된 항목 fixture, 체류 시간 대화상자, 이동 수단 시트, 360dp·글자 2.0 조합 | 잘림·가로 스크롤 없음. 글자 2.0에서 `변경` 칩이 두 줄(`변`/`경`)로 꺾이는 기존 발견 사항 유지 |
| 취소 확인 대화상자 | 별도 window라 `captureToImage`에 잡히지 않음. `ItineraryEditScreenTest`의 문구·행동 검증으로 대신 | 통과 |
| 수동 항목 1 (실서버·`gilpick_api36_play`) | 로컬 API(uvicorn 127.0.0.1:8000, `adb reverse`) + 주입 세션으로 실제 앱 구동. 상세 → `일정 편집` → `장소 추가` → TourAPI 실검색 `경복궁` → `+` → 대중교통·90분 → 저장 | 통과. PUT 201, 상세에 `1일차 · 1곳`·경복궁 표시 |
| 수동 항목 2 | 북촌한옥마을·인사동 추가 → 3번째를 `위로 이동` → 첫 항목 체류 120분·이동 수단 도보 → 저장 → 뒤로 → 재진입 | 통과. 상세 순서 `경복궁, 노블관광호텔 인사동, 북촌한옥마을`, `2시간`·`도보` 표시, 재조회 후 동일 |
| 수동 항목 3 | 앱이 편집 화면을 연 뒤 다른 세션(같은 사용자, 다른 기기 ID)이 같은 날짜를 먼저 PUT → 앱에서 첫 항목 삭제 후 저장 | 통과. 서버 로그 `PUT 200(다른 세션) → PUT 409 → GET → PUT 200(앱)`, 앱은 안내 없이 상세로 이동, 최종 상태 = 앱 초안(2곳) |
| 수동 항목 4 | 2일차에 실검색으로 10곳 추가 | 통과. `장소 추가` 비활성, `하루 최대 10곳` 안내, 저장 후 상세 `10곳` |
| 수동 항목 5 (#227 merge 후) | 3일차(10/7)에 항목 1개인 여행을 10/5~10/6으로 축소 → `변경 사항 저장` | 통과. `PATCH 409` → 대화상자 `일정 1곳이 삭제됩니다` → `취소`: 수정 화면 유지·기간 그대로 → 다시 저장 → `저장하기` → `PATCH 200`, 상세에서 3일차·남산서울타워 사라지고 2일 여행 |
| 수동 항목 6 (실제 앱) | `settings put system font_scale 2.0` 후 상세·편집 화면 | 통과. 잘림·가로 스크롤 없음(긴 장소명은 두 줄) |

실서버 구동 방법: `docker compose up -d postgres` → `alembic upgrade head` → `uvicorn app.main:app --host 127.0.0.1 --port 8000` → `adb reverse tcp:8000 tcp:8000` → `-PGILPICK_API_BASE_URL=http://127.0.0.1:8000/api/v1/`로 설치 → `users`·`device_sessions`에 세션을 심고(`client_device_id` = 앱 `AuthSessionStore.deviceId()`) 앱 저장소에 토큰 주입. 여행 생성은 F002가 검증한 화면 대신 API로 만들었다. 실서버 흐름은 임시 instrumented 스크립트(`ItineraryLiveE2E`, 커밋하지 않음)로 구동했고 screenshot 14장(`s1_*`~`s6_*`)을 남겼다. TMAP/ODsay 경로 계산은 이 검증에서 `FAILED`로 돌아왔으나(#211 T036 범위) F004 저장은 영향 없이 성공했다.

screenshot 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/` (`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` 후 `adb pull`).

## 통합 최종 검증 기록 (T035, 2026-09-08, ts)

- 환경: `origin/main` 822f687 기반, 로컬 PostgreSQL·uvicorn `127.0.0.1:8000`, `adb reverse tcp:8000 tcp:8000`, AVD `gilpick_api36_play`(Android 16, API 36).
- 수동 항목 1: 사람이 여행 상세 진입부터 경복궁 추가(대중교통·90분), 저장 완료 후 상세의 `1일차 · 1곳` 확인까지 직접 조작했으며 약 40초가 걸렸다. SC-001의 5분 이내 기준을 충족했다. 화면에는 경복궁·1시간 30분이 표시됐고 DB는 해당 날짜 `schedule_version 3`·항목 1개로 일치했다.
- 수동 항목 2~5: T034에 기록된 순서·체류 시간·이동 수단 저장과 재조회, 두 세션 충돌 자동 재저장, 10곳 상한, 기간 축소 동의·취소 증빙을 `main` 반영 상태와 대조했다. T035에서는 별도로 재실행하지 않았다.
- 자동 회귀 검증: Backend F004 contract·unit·integration 64건 통과, Android itinerary·trip unit test와 `assembleDebug` 통과, API 36 AVD의 itinerary·trip `connectedDebugAndroidTest` 통과.
- 계약 교차 확인: `contracts/itinerary.openapi.yaml`, Backend `api/app/schemas/itinerary.py`·`api/app/schemas/route.py`, Android `ItineraryApi.kt`를 대조했다. `TransportMode(WALK/TRANSIT/CAR)`, `ItemStatus`, `RouteStatus`, `StaySource`, nullable `itemId`·`place`·`transportModeToNext`·`route`, 오류 code, 신규 항목의 `place` 스냅샷 필드가 일치해 계약 수정은 필요하지 않았다.
- 참고: 첫 Backend 재실행은 `TEST_DATABASE_URL` 미설정으로 integration 12건이 setup error였으며, 로컬 compose DB URL을 지정한 재실행에서 전체 64건이 통과했다. 첫 장소 검색은 제한된 네트워크로 실행한 uvicorn에서 `502 TOUR_API_FAILED`였고, 외부 연동 가능한 로컬 프로세스로 재시작한 뒤 경복궁 검색 `200`·12건을 확인하고 수동 시간을 처음부터 다시 측정했다.
