# Quickstart: F005 경로 계산 검증

## 사전 조건

- PostgreSQL/PostGIS와 API·Android 개발 환경
- test에서는 TMAP·ODsay fixture/MockTransport 사용
- live smoke test에서만 provider key와 Naver Maps client ID를 로컬 secret으로 주입

## 자동 검증

```powershell
cd api
uv run pytest tests/unit tests/contract tests/integration

cd ..\android
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest
```

## 필수 시나리오

1. 혼합 이동수단 4개 장소 저장: `READY`, 3개 구간 순서·수단·provider 일치, 합계와 GET 결과 일치.
2. 0개는 외부 호출 없이 `NOT_CALCULATED`, 1개는 READY/0초/0m.
3. timeout 뒤 성공: 한 번만 재시도하고 전체 10초 deadline 준수.
4. 한 구간 최종 실패: 일정 PUT은 성공하고 일정 보존, 경로 전체 `FAILED`, 안정적 code 표시.
5. 같은 version을 중복 재시도: 동일한 버전별 경로 행만 갱신되고 활성 경로 중복 생성 없음.
6. 계산 중 일정 수정: 이전 결과가 최신 경로로 활성화되지 않음.
7. 타인·삭제 여행·기간 밖 날짜: 기존 인증·소유권·404 정책으로 거부.

## Android UI 검증

- `Loading`: 1초를 넘는 조회/저장에 진행 표시, 편집 내용 유지
- `Empty`: 0개 장소에서 일정 추가 또는 돌아가기
- `Error`: 원인과 48dp 이상 `다시 시도`, 일정은 정상 표시
- `Content`: marker·polyline·목록 순서 일치, 이동수단 아이콘+문구, attribution 표시
- 정상 content에는 임의 재계산·대안 선택 버튼 없음
- 360dp, 일반 phone, 최대 font scale screenshot과 실제 지도 gesture/inset 확인

Live test는 quota를 소모하므로 대표 좌표만 사용한다. 응답·log·fixture에 key나 불필요한 정밀 좌표를 남기지 않는다.

## Backend 최종 검증 기록 (2026-09-06)

- 전체 자동 테스트: `python -m pytest -q` → `352 passed, 2 skipped`
  - `TEST_DATABASE_URL`은 로컬 PostgreSQL/PostGIS 테스트 DB를 사용했다.
  - 로컬 `.env`의 JWT secret 길이가 개발용 최소 조건을 충족하지 않아 테스트 명령에서만 32자 이상의 dummy 값을 환경변수로 주입했다.
  - 기본 실행에서 건너뛴 2건은 명시적 opt-in이 필요한 provider live smoke test다.
- 문법 검사: 별도 `PYTHONPYCACHEPREFIX`를 사용한 `python -m compileall -q app tests` 통과.
- deadline: mock clock 기준 모든 구간이 계산 시작 시각으로부터 동일한 10초 deadline을 공유하는 unit test 통과.
- 문서·설정 대조: ROUTE 조회/retry와 F006 `/recalculate`의 역할, route table 제약, 오류 code, 5초 provider timeout, 10초 전체 deadline, attribution을 구현과 대조했다.

### Provider live smoke test

실행 명령: `RUN_ROUTE_PROVIDER_SMOKE=1 python -m pytest tests/smoke/test_route_providers_live.py -q`

- TMAP 도보 대표 구간: 성공. `provider=TMAP`, attribution, 비음수 시간·거리, WGS84 geometry를 확인했다.
- ODsay 대중교통 대표 구간: 실패. ODsay 응답은 HTTP 200이었으나 내부 오류 `500 [ApiKeyAuthFailed]`를 반환했다.
- ODsay 후속 확인: 백엔드 호출용 Server key인지, 현재 호출 출발 IP가 ODsay LAB에 등록됐는지 확인한 뒤 같은 smoke test를 다시 실행해야 한다. key 원문은 테스트 출력과 문서에 기록하지 않았다.

## Android 검증 기록 (T034, 2026-09-07, jy)

자동 검증은 branch `test/jy-route-final-verification`(#226 → #229 → #230 위) 기준이다.

| 항목 | 명령·방법 | 결과 |
|---|---|---|
| route unit test | `android\gradlew.bat --offline -q :app:testDebugUnitTest --tests 'com.gilpick.route.*'` | 통과 30건 (`RouteApiTest` 7, `RouteRepositoryTest` 8, `RouteViewModelTest` 15) |
| 전체 unit test·build | `android\gradlew.bat --offline -q :app:testDebugUnitTest :app:assembleDebug` | 통과 290건, build 성공(Naver map-sdk 3.23.3 merge 확인) |
| route UI·screenshot·navigation test | `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.route` (AVD `gilpick_api36`, API 36 ATD) | 통과 28건 (`DayRouteScreenTest` 12, `DayRouteScreenshotTest` 12, `RouteNavigationTest` 2, `TripDetailRouteScreenshotTest` 2) |
| 여행 상세 회귀 | `...package=com.gilpick.trip` | 통과 65건 |
| 네 상태 | `DayRouteScreenTest`: loading 1초 지연, empty(`장소 추가`·`돌아가기`), error(조회 실패·계산 실패 code별 문구·`다시 시도` 48dp·세션 만료 `다시 로그인`), content | 통과 |
| 혼합 이동수단 | 도보(TMAP)·대중교통(ODsay)·자동차(TMAP) 세 구간이 아이콘+문구·시간·거리로 구분, 목록 순서 = 마커 순서 | 통과 (`혼합_이동수단_구간은_각각_아이콘과_문구로_구분된다`, screenshot `route_content_mixed`) |
| 1곳 | 구간 없음, 총 이동 0분·0m, 장소 한 행, attribution 없음 | 통과 |
| 정상 경로 재계산 없음 | content에 `다시 시도`·`다시 계산`·`다른 경로` 없음 | 통과 |
| 360dp·일반 phone·최대 글자 배율 | screenshot 14장(`route_*` 12, `trip_detail_route_states*` 2): 일반 phone(411dp), 360dp, 글자 2.0, 360dp+2.0. 가로 스크롤·잘림 없음, 장소명은 단어 중간 줄바꿈 | 통과 (사람 확인) |
| attribution | sheet에 `출처: …` 표시(실서버에서는 `출처: TMAP`). Naver 지도 로고는 SDK 기본값 유지 | 통과 (로고 실제 지도에서 확인) |
| 지도 gesture·inset·polyline·marker (T026) | Naver NCP key를 `~/.gradle/gradle.properties`에 넣고 `gilpick_api36_play`에서 실서버(TMAP 도보 2구간, 3곳 READY) 경로 화면 확인 | 통과. 야간 지도 타일 렌더링, 순서 번호 마커 1·2·3 + 장소명 caption, 구간 polyline, `fitBounds`로 세 마커가 sheet 위 영역에 들어옴, 드래그 이동·더블탭 확대·`+/-` 컨트롤 동작, `NAVER` 로고·축척이 sheet에 가리지 않음, 뒤로 가기 후 재진입 시 마커·선 중복 없음. 발견·수정: `OverlayImage.fromView`가 뷰를 다시 재서 마커가 타원으로 찌그러짐 → 마커 크기 28dp 고정(커밋 6571c92) |

screenshot 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/` (`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 실행 후 `adb pull`).

발견 사항: 글자 배율 2.0에서 여행 상세 통계 `정보 없음`이 `정보 …`로 잘린다(F002 `Stat`, hs 소유, F005 범위 밖).

## 종단간 계약 교차 검증 기록 (T036, 2026-09-07, jh)

검증 환경은 로컬 PostgreSQL 18, `127.0.0.1:8000`의 FastAPI, ADB reverse, AVD `Medium_Phone`(API 37), 실제 TMAP·ODsay와 Naver Maps다. API는 LAN에 노출하지 않고 `adb reverse tcp:8000 tcp:8000`으로 AVD에만 연결했다. 앱은 검증 빌드에서만 `GILPICK_API_BASE_URL=http://127.0.0.1:8000/api/v1/`과 로컬 Naver Maps client ID를 주입했다.

### 수행 결과

| 시나리오 | 결과 |
|---|---|
| 혼합 일정 저장→자동 계산→상세→경로 화면 | 4곳을 `WALK → TRANSIT → CAR`로 저장하자 HTTP 일정 PUT이 0.349초에 `READY`를 반환했다. 구간 provider는 `TMAP → ODSAY → TMAP`이었고 GET 재조회와 합계가 일치했다. AVD 상세에서 4곳·총 34분과 구간별 시간·거리를 확인했고 경로 화면에서 지도, 순서 1~4, 세 구간 목록, `출처: TMAP · ODsay`를 확인했다. |
| provider 최종 실패→일정 보존→retry | ODsay 경계에 `ROUTE_PROVIDER_UNAVAILABLE`을 주입하자 날짜 전체가 `FAILED`가 되었고 네 일정 항목의 순서·수단은 전후 동일했다. AVD 상세에는 일정과 독립된 실패 문구와 `다시 시도`가 표시됐다. 버튼을 누르자 로컬 API가 실제 provider로 다시 계산해 같은 화면이 `READY`·총 34분·6.1km로 복구됐다. |
| 중복 retry·version 충돌 | 실제 PostgreSQL을 사용하는 `test_concurrent_retries_keep_one_ready_route_row`, `test_retry_rejects_stale_version_and_ready_route`, `test_retry_reports_version_conflict_when_schedule_changes_after_snapshot`가 통과했다. 중복 요청은 버전별 한 행만 유지하고 stale version은 `409 VERSION_CONFLICT`, READY retry는 `409 ROUTE_NOT_FAILED`로 거부했다. |
| AVD route 회귀 | API 37에서 route UI·screenshot·navigation 28건이 통과했다. Compose test의 전이 Espresso 3.5.0이 제거된 `InputManager.getInstance()`를 호출해 최초 28건이 공통 실패했으며, 공식 AndroidX Test 3.7.0을 androidTest 의존성으로 명시한 뒤 28건 모두 통과했다. |

### 계약 교차 확인

`route.openapi.yaml`, Backend Pydantic schema와 Android DTO를 대조했다. 계약 변경이 필요한 차이는 없었다.

| 항목 | 교차 확인 결과 |
|---|---|
| enum | `RouteStatus`는 `NOT_CALCULATED/READY/FAILED`, 이동수단은 `WALK/TRANSIT/CAR`, provider는 `TMAP/ODSAY`로 일치한다. |
| nullable | `READY`는 `route`만, `FAILED`는 `failure`만 값이 있고 `NOT_CALCULATED`는 둘 다 `null`이다. |
| 단위·형상 | 시간은 초, 거리는 미터, geometry는 GeoJSON `LineString`과 `[경도, 위도]` 순서다. |
| 오류 code | 실패 data의 provider code 5종과 HTTP error의 `VERSION_CONFLICT`, `ROUTE_NOT_FAILED`를 포함한 6종이 일치한다. |
| 순서·합계 | marker와 segment 순서, 인접 item ID, 구간 합계, 중복 제거된 attribution 순서를 Backend validation과 Android 표시에서 확인했다. |

### Success Criteria 추적

| 기준 | 결과 | 근거 |
|---|---|---|
| SC-001 | 충족 | 실제 혼합 경로 자동 계산 0.349초, mock clock의 공용 10초 deadline test 통과 |
| SC-002 | 충족 | 실제 provider의 세 구간 순서·수단과 AVD 지도 밖 목록이 저장 입력과 일치 |
| SC-003 | 충족 | provider 실패 전후 일정 동일, 실패 원인과 AVD `다시 시도` 확인 |
| SC-004 | 충족 | 동시 retry integration test에서 현재 경로 한 행 유지 |
| SC-005 | 충족 | 360dp·글자 배율 2.0 screenshot/UI test와 AVD 회귀 통과 |
| SC-006 | 충족 | 지도 밖 목록만으로 장소 순서 1~4와 세 구간의 수단·시간·거리 확인 |

### 실행 명령과 결과

- Backend F005 핵심: route unit·contract·PostgreSQL integration `67 passed`
- Provider live smoke: `RUN_ROUTE_PROVIDER_SMOKE=1 ...test_route_providers_live.py` → `2 passed`; TMAP·ODsay 모두 성공
- Android unit·build: `:app:testDebugUnitTest :app:assembleDebug` → 성공
- Android route 계측: `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.route` → `28 tests, 0 failures`
- ADB: `emulator-5554 device`, AVD API 37

테스트용 login ticket과 정밀 좌표는 출력·문서화하지 않았다. 로컬 API와 ADB reverse는 검증 종료 후 중지·해제한다.
