# Quickstart: 사용자 설정 검증

## 1. 사전 조건

- PostgreSQL test DB와 API 환경 변수가 준비되어 있어야 한다.
- Android debug build에 승인된 개인정보처리방침·이용약관 HTTPS URL을 주입해야 한다.
- Figma 원본과 `docs/design/figma-make/src/screens/SettingsScreen.tsx`가 단일 장소 변경 제안 알림 토글로 동기화되어야 한다.
- Backend·Android 계약은 `specs/012-user-settings/contracts/preferences.openapi.yaml`을 기준으로 한다.

정책 URL과 Figma 동기화가 준비되지 않았다면 API 검증은 진행할 수 있지만 정책 문서와 최종 screenshot 종단간 검증은 완료 처리하지 않는다.

## 2. Backend 검증

```powershell
cd api
uv sync --frozen
uv run pytest tests/unit/test_preferences_schema.py tests/unit/test_preferences_service.py
uv run pytest tests/integration/test_preferences_flow.py
uv run pytest tests/unit/test_notification_service.py tests/integration/test_notification_detection_hook.py
```

확인 결과:

1. 인증 사용자의 GET은 기본값 `true` 또는 저장된 값을 반환한다.
2. PATCH `false`/`true`는 응답값과 다음 GET에 동일하게 반영된다.
3. body 누락, `null`, 문자열, 숫자, 알 수 없는 field는 `400` 공통 오류 envelope로 거절된다.
4. Token 누락·무효는 `401`이며 다른 사용자 설정을 지정할 경로가 없다.
5. 같은 값 재요청은 부수효과 없이 같은 값을 반환한다.
6. 겹친 변경 뒤 최종 GET은 DB가 마지막으로 성공 처리한 값을 반환한다.
7. 설정이 꺼졌을 때 새 장소 변경 제안 알림은 만들지 않지만 detection과 도착·출발 확인 알림은 계속 만든다. 다시 켜도 누락분을 소급 생성하지 않는다.

### 동시 변경·재조회 자동 검증 (2026-09-12, jy, #403 T029)

로컬 API(docker postgres + `alembic upgrade head`, uvicorn 127.0.0.1:8005, `main` 180bb38)에 `device_sessions`를 시드한 실제 session으로 10회 반복했다. 매회 PATCH 4건(`true`/`false` 교대, thread 4개 동시)을 보내고 모두 `200`인지 확인한 뒤 GET 값과 `users.replacement_suggestion_enabled`를 대조했다.

| 회차 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| PATCH 4건 상태 | 200×4 | 200×4 | 200×4 | 200×4 | 200×4 | 200×4 | 200×4 | 200×4 | 200×4 | 200×4 |
| GET = DB | 일치 | 일치 | 일치 | 일치 | 일치 | 일치 | 일치 | 일치 | 일치 | 일치 |

10회 모두 GET이 DB가 마지막으로 성공 처리한 값과 같았다(6번 항목). 겹친 요청 중 어느 것이 마지막인지는 서버 처리 순서라 앱이 보낸 순서와 다를 수 있으며, 그래서 앱은 응답값을 표시한다(FR-003).

## 3. Android 자동 검증

```powershell
cd android
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

최소 검증 항목:

- 초기 조회 성공·지연 loading·실패 재시도
- PATCH 성공 후 확정값 갱신, 실패 후 마지막 성공값 복원
- 빠른 연속 선택에서 동시 요청은 한 건이며 마지막 희망값이 최종 저장됨
- 늦은 이전 응답이 최신 상태를 덮지 않음
- 설정 저장 중 정책 문서와 로그아웃 행동 유지
- 인증 `401` 처리 시 F001 refresh/replay 규칙 재사용
- session 닉네임·프로필 누락 대체 표시와 `BuildConfig.VERSION_NAME` 표시

### 검증 기록 (2026-09-12, jy, #403 T029)

`main` 180bb38 + #403 변경. AVD `gilpick_api36`(ATD)과 `gilpick_api36_play`(API 36).

| 항목 | 결과 |
|---|---|
| `testDebugUnitTest` | 581 통과 |
| `connectedDebugAndroidTest` 전체 511개 | 두 AVD에 `am instrument -e numShards 2`로 나눠 실행. shard0(play) 272 실행 · 2 실패, shard1(ATD) 239 실행 · 6 실패. 실패는 아래 표 |
| `connectedDebugAndroidTest` `com.gilpick.settings` | 56 통과(play, 최종 코드로 재실행. `SettingsAdaptiveTest` 17 · `SettingsNavigationTest` 4 · 기존 35) |
| `lintDebug` | error 1(`local.properties`의 드라이브 문자 escape — 머신 로컬 파일, 저장소 밖) · warning 15(모두 기존: 의존성 버전·미사용 리소스·`TripFormScreen` Modifier 순서 등). 이번 변경 파일에서 0건 |

전체 실행에서 실패한 test와 처리:

| test | 원인 | 처리 |
|---|---|---|
| `AuthLoginTest`·`AuthLogoutIntegrationTest`·`AuthRefreshIntegrationTest`·`TripDeleteFlowTest`·`TripDetailScreenTest`의 `내 여행` 표시 assert 5개 | 하단 탭(T026)에도 `내 여행`이 생겨 `onNodeWithText`가 두 노드를 찾음 | `onAllNodesWithText(...).onFirst()`로 고쳐 통과 |
| `AuthLoginTest.앱이_인증_완료_host를_App_Link_domain으로_검증한다` | ATD AVD는 domain verification 결과가 `none`(0) | play AVD에서 `pm verify-app-links --re-verify` 뒤 `verified`, 재실행 통과 |
| `AlternativeNavigationTest`·`ProgressNavigationTest` 2개·`ItineraryEditScreenshotTest.편집_content_10곳_긴_이름`·`PlaceSearchScreenTest.키보드_검색_동작…` | 두 AVD 병렬 실행 중 2~5초 `waitUntil` timeout·키 입력 유실 | play AVD 단독 재실행 26+7 통과 |
| `TripEmptyStateTest` 2개(`빈_상태_아이콘은_TalkBack이_읽지_않는다`, `검색_결과_없음_…`) | 화면의 모든 contentDescription이 비어 있기를 기대하는데 F011 헤더 벨 `알림 보기`(2026-09-10, 7eab8ee)가 있음. 이 변경과 무관하게 `main`에서 실패 | **미해결.** F011/F002 소유라 이 PR에서 고치지 않고 기록만 남김 |

위 실패 외 505개는 첫 실행에서 통과했다.

## 4. 정책 문서 검증

1. 개인정보처리방침과 이용약관을 각각 선택한다.
2. 각 항목이 서로 다른 승인된 HTTPS 문서를 Custom Tab으로 여는지 확인한다.
3. 뒤로 가기로 설정 화면과 로그인 상태가 유지되는지 확인한다.
4. 빈 URL, HTTP URL, Custom Tab 실행 불가를 각각 자동 test로 재현한다.
5. 앱이 감지 가능한 실패에서는 설정 화면과 로그인 상태가 유지되고 원인과 재시도 행동이 보이는지 확인한다.
6. Custom Tab 실행 뒤 offline·HTTP·문서 로딩 오류는 브라우저가 표시하며 앱 오류 상태 검증 대상이 아님을 확인한다.
7. 정상 네트워크에서 각 정책 문서를 5회 열어 모두 선택 후 3초 이내에 표시되는지 수동 측정한다.

### 측정 기록 (2026-09-11, jy)

- 환경: emulator `gilpick_api36_play`(API 36, Chrome Custom Tab), `android/gradle.properties`의 `https://gilpick.pages.dev/privacy/`·`/terms/`, Wi-Fi 정상 네트워크.
- 방법: 정책 섹션에서 행을 탭한 시각부터 Custom Tab에 문서 본문이 그려진 시각까지(기기 내 `screencap` 폴링, 해상도 약 0.3초). 매회 뒤로 가기로 설정 화면 복귀를 확인했다.

| 문서 | 1 | 2 | 3 | 4 | 5 | 판정 |
|---|---|---|---|---|---|---|
| 개인정보처리방침 | 1.32s | 1.59s | 0.93s | 0.88s | 1.34s | 3초 이내 |
| 이용약관 | 1.58s | 0.87s | 0.87s | 1.75s | 0.92s | 3초 이내 |

- 두 문서가 서로 다른 제목(`길픽 개인정보처리방침`, `길픽 이용약관`)으로 열리고, 복귀 후 정책 항목이 그대로 남는다.
- 자동 test(빈 URL·HTTP URL·실행 실패·재시도)는 `SettingsPolicyTest` 10개로 별도 검증한다.
- 2026-09-12(#403 T029, `gilpick_api36_play`, 로컬 API): 하단 탭 `설정` → 개인정보처리방침 4.6s·이용약관 2.3s에 Custom Tab 본문 표시, 각각 뒤로 가기로 설정 화면 복귀·저장값(꺼짐) 유지 확인. screenshot `policy_privacy.png`·`policy_terms.png`.

## 5. UI·접근성·adaptive 검증

동기화된 Figma `SettingsScreen`을 기준으로 screenshot을 비교한다.

| 시나리오 | 기대 결과 |
|---|---|
| content / 알림 ON·OFF | 단일 장소 변경 제안 토글만 존재하고 상태를 색 이외 방식으로도 구분 |
| 조회 1초 초과 | 설정 영역만 loading, 정책·로그아웃은 사용 가능 |
| 조회·저장 error | 원인과 재시도 표시, 저장 실패 시 마지막 성공값 유지 |
| 360dp | 가로 scroll·잘림·겹침 없음 |
| phone/tablet 세로·가로 | 모든 항목을 세로 scroll로 접근 가능 |
| 시스템 최대 글자 크기 | 핵심 문구와 상태가 잘리지 않음 |
| system/navigation bar | 고정 탐색과 system 영역이 내용을 가리지 않음 |
| touch target | 모든 대상 48×48dp 이상, 대상 간 8dp 이상 |

### 검증 기록 (2026-09-12, jy, #403 T027)

`SettingsAdaptiveTest`(assert 4 + screenshot 13, `/sdcard/Android/data/com.gilpick/files/screenshots/settings_*.png`)와 `gilpick_api36_play` 실기기 화면으로 확인했다.

| 시나리오 | 결과 |
|---|---|
| content / 알림 ON·OFF | `settings_content_on`·`settings_content_off`·`settings_saving`. 토글 하나뿐이고 `저장 중` 배지가 문구로 상태를 알린다 |
| 조회 1초 초과 | `settings_loading`. 설정 영역에만 대기 표시, 정책·로그아웃은 그대로 |
| 조회·저장 error | `settings_error_load`·`settings_error_save`(마지막 성공값 켜짐 유지)·`settings_policy_error`·`settings_account_unknown` |
| 360dp | `settings_content_360dp_fontscale2`(+`_bottom`)·`settings_error_load_360dp_fontscale2`. 네 영역 오른쪽 경계 ≤360dp assert, 가로 scroll·잘림·겹침 없음 |
| phone/tablet 세로·가로 | play AVD 세로 `live_settings_tab_final`, 가로(`user_rotation 1`) `live_settings_phone_landscape`(+`_scrolled`: 스크롤로 로그아웃 도달), `wm size 1600x2560`·`density 320`(800×1280dp) 세로·가로 `live_settings_tablet_*`. 모두 세로 scroll로 로그아웃까지 접근 |
| 시스템 최대 글자 크기 | 2.0 배율에서 제목·설명·정책·로그아웃 문구 잘림 없음(설명은 4줄로 접힘). assert로 핵심 문구 표시 확인 |
| system/navigation bar | 실기기에서 계정 헤더가 status bar 아래 겹치고(`live_settings_tab`) status bar 아이콘이 흰색이라 흰 헤더에서 보이지 않던 것을 발견. `AccountSection`에 `statusBarsPadding()`, `Theme.Gilpick`에 `windowLightStatusBar=true`를 더해 해결(`live_settings_tab_final`). 하단 탭은 `Scaffold` padding으로 로그아웃과 겹치지 않음(`settings_tab_bar_logout`, assert) |
| touch target | 토글·정책 2행·로그아웃 `assertHeightIsAtLeast(48.dp)`, 하단 탭은 M3 `NavigationBarItem` 기본 48dp 이상 |

Figma `SettingsScreen`과 content screenshot: 계정 헤더(아바타·닉네임·카카오 연동) → 알림 설정(단일 토글) → 앱 정보(버전·정책 2행) → 로그아웃 순서·색·간격이 일치한다. 하단 탐색은 Figma `App.tsx`의 3탭 중 `여행 중` 없이 2탭이다(T026 기록, F002 정책 의존).

설정 변경 수동 5회(SC, play AVD, 로컬 API): 탭 후 토글 확정까지 모두 4.8s 이내(uiautomator dump 폴링 해상도 약 2.4s라 실제는 그보다 짧다). 30초 기준 충족. 최상위 탭 왕복(설정 → 내 여행 → 설정) 뒤 서버 GET과 화면 토글이 같은 값(꺼짐)이었다(UI-002).

## 6. 로그아웃 종단간 검증

1. 같은 계정을 두 기기에 로그인한다.
2. 한 기기의 설정 화면에서 로그아웃한다.
3. 해당 기기가 즉시 로그인 화면으로 이동하는지 확인한다.
4. offline에서도 local session이 즉시 제거되고 서버 폐기 재시도가 예약되는지 확인한다.
5. 다른 기기의 session과 저장된 알림 설정은 유지되는지 확인한다.

### 검증 기록 (2026-09-12, jy, #403 T029)

로컬 API 하나에 같은 사용자(`F012검증`)의 두 session을 시드했다. A = `gilpick_api36_play`(API 36, adb reverse로 서버 연결), B = `gilpick_api26`(API 26).

1. A 설정 화면 `로그아웃` → A는 2.1s(uiautomator 폴링 해상도 ~2s) 안에 로그인 화면으로 전환(`logout_emulator-5554_before/after.png`).
2. 서버 로그 `POST /api/v1/auth/logout 204`, `device_sessions.revoked_at`이 약 5초 안에 기록됐다(WorkManager `SessionRevocationWorker`).
3. B는 그대로 로그인 상태이고 설정 화면 토글이 서버 값(켜짐)을 보였다(`logout_5556_other_device_settings.png`). B의 session은 `revoked_at IS NULL`, `users.replacement_suggestion_enabled`도 변하지 않았다.
4. offline 경로: B에서 서버로 가는 경로 없이 로그아웃했을 때도 2.5s 안에 로그인 화면으로 바뀌었고, logcat에서 `SessionRevocationWorker`가 `RETRY`로 30s·60s 지수 backoff 재시도를 예약하는 것을 확인했다(A에서도 같은 log). 서버 폐기 규칙 자체는 F001 `AuthLogoutIntegrationTest`가 검증한다.

첫 시도에서 `POST /auth/logout 401` 한 건이 보였는데, 검증용으로 같은 기기의 refresh token을 DB에서 다시 시드해 앱이 가진 이전 token이 무효가 된 harness 문제이지 앱 결함이 아니다. `pm clear` 뒤 깨끗한 session으로 다시 했을 때 204였다.

## 7. 계약·정적 검증

```powershell
git diff --check
cd api
uv run ruff check app tests
uv run mypy app
```

OpenAPI YAML 파싱, 공통 envelope, `docs/design/api-spec.md`의 PREF-001/PREF-002와 field 이름 일치도 함께 확인한다.

## 8. 미실행 항목 (#403, 2026-09-12)

- **팀원 2명의 첫 시도 위치 찾기(T029)**: 구현 담당자를 제외한 팀원이 직접 해야 하는 항목이라 실행하지 못했다. 하단 탭 `설정`이 생겼으니 hs·jh가 첫 진입 위치를 찾는지 PR review 때 확인해 주면 된다.
- **`TripEmptyStateTest` 2개**: 3절 표대로 F011 벨 추가 이후 `main`에서 실패한다. 이 PR 범위 밖이라 고치지 않았다.
