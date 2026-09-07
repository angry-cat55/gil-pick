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
| attribution | sheet에 `출처: 경로 정보 제공: TMAP · 대중교통 정보 제공: ODsay` 표시. Naver 지도 로고는 SDK 기본값 유지(숨기지 않음) | 문구는 통과, 지도 로고는 아래 미검증 |
| 지도 gesture·inset·polyline·marker | 실제 Naver 지도 렌더링 | **미검증**: 로컬에 NCP Key ID가 없어 지도 인증 불가. `~/.gradle/gradle.properties`에 `GILPICK_NAVER_MAPS_CLIENT_ID`를 넣고 `gilpick_api36_play`(창 있는 AVD)에서 이동·확대·축소, 마커 번호, polyline, sheet가 로고를 가리지 않는지(`setContentPadding`), 화면 재진입 시 overlay 중복 없음을 확인해야 한다 |

screenshot 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/` (`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 실행 후 `adb pull`).

발견 사항: 글자 배율 2.0에서 여행 상세 통계 `정보 없음`이 `정보 …`로 잘린다(F002 `Stat`, hs 소유, F005 범위 밖).
