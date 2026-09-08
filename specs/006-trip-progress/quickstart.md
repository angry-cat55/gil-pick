# Quickstart: F006 여행 진행 검증

## 사전 조건

- PostgreSQL/PostGIS(`docker compose up -d postgres`)와 `alembic upgrade head`(migration 005 포함)
- test에서는 TMAP·ODsay를 F005 fixture/MockTransport로 대체한다. 실제 provider 호출은 live smoke test에서만 한다.
- Android: `gilpick_api36_play` AVD(화면 확인용), 위치는 `adb emu geo fix <lon> <lat>`로 주입한다.

## 자동 검증

```powershell
cd api
uv run pytest tests/unit tests/contract tests/integration -k progress

cd ..\android
.\gradlew.bat --offline -q :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.progress
```

## 필수 Backend 시나리오

1. 시작: 오늘 날짜·장소 3곳·READY 경로에서 `POST .../progress/start` → `IN_PROGRESS`, 첫 장소 `EN_ROUTE`, ETA 3개가 `시작+0`, `+체류+구간`, `+체류+구간` 규칙과 일치. `progressVersion` 1.
2. 시작 멱등: 같은 `Idempotency-Key` 재전송과 다른 key의 재시작 모두 같은 `actualStartedAt`. 동시 요청 2개도 시각 하나.
3. 시작 거부: 오늘이 기간 밖 `409 DAY_NOT_TODAY`, 장소 0곳 `422 DAY_EMPTY`, 타인·삭제 여행 `403/404`.
4. 시작 위치: 유효 위치(정확도 ≤100m, ≤2분)면 `start_location` 저장·도보 구간 계산·첫 ETA 반영. 정확도 150m 또는 5분 지난 위치는 무시하고 위치 없이 시작. provider 실패 시 시작은 성공, 첫 ETA null.
5. 도착: `PATCH .../status {ARRIVED}` → `actual_arrived_at` 저장, 이후 ETA가 `실제 도착+체류+구간`으로 갱신.
6. 출발: `{COMPLETED}` → 현재 `COMPLETED`·`actual_departed_at`, 다음 `EN_ROUTE`, 이후 ETA가 `실제 출발+구간` 기준.
7. 건너뛰기: `EN_ROUTE` 장소 `{SKIPPED}` → 다음 장소 `EN_ROUTE`, `progress_segments`에 `이전→다음` 구간(이전 장소 이동수단) 1행, ETA 반영. provider 실패면 행 없음·ETA null·전환은 성공.
8. 당일 완료: 마지막 남은 장소 `ARRIVED` → `dayStatus COMPLETED`, `completed_at`, `detection_active=false`. 마지막 남은 장소 `SKIPPED`도 같다.
9. 상태 수정: 완료 취소(`COMPLETED→ARRIVED`)로 뒤 장소 `PLANNED` 초기화·완료 날짜 `IN_PROGRESS` 복귀; 건너뛰기 취소(`SKIPPED→PLANNED`); 예정 장소 `도착으로 변경` 시 앞 `ARRIVED`가 `COMPLETED`, 앞 `EN_ROUTE`가 `PLANNED`, 뒤 전부 `PLANNED`.
10. 거부: 시작 전 날짜 전환 `409 DAY_NOT_STARTED`, `PLANNED→COMPLETED` 등 허용되지 않은 조합 `422 INVALID_STATUS_TRANSITION`, 오래된 `progressVersion` `409 VERSION_CONFLICT`, 같은 `Idempotency-Key` 재전송은 최초 결과 200.
11. 불변식: 어떤 전환 뒤에도 `EN_ROUTE ≤ 1`, `ARRIVED ≤ 1`. `progress_transitions`에 전환 1건과 `affected_items` 전부 기록.
12. F004 연동: 진행 중 날짜에 장소 추가·처리된 장소 체류 시간 변경 저장 → 이후 ETA만 갱신, 앞 장소 ETA 불변. F005 `retry`로 경로가 READY 되면 null이던 ETA가 채워짐.

## 필수 Android 시나리오

- 여행 상세: 오늘이 기간 밖이면 `오늘 여행 시작` 비활성 + `오늘은 여행 날짜가 아닙니다`; 장소 0곳이면 `장소 추가` 안내; 시작 후 `여행 진행 화면으로`.
- 시작 흐름: 권한 없음 → 앱 사용 중 권한 요청 → 거부해도 위치 없이 시작 성공. 허용 시 1회 위치 취득(10초 timeout) 후 시작.
- 진행 화면 `content`: 상단 `여행 중`·`N일차 · x/y 완료`, 다음 장소 카드(장소명, 예상 도착, `N분 남았어요`/`N분 지났어요`, 이전 장소에서 이동수단·시간·거리), `도착했어요`·`건너뛰기`; `ARRIVED`면 `다음 장소로 출발`; 지도 marker·`경로 보기`; 일정 목록 상태 문구+아이콘; `장소 추가`.
- 상태 수정 시트: 장소 행 탭 → 상태별 행동만 표시(UI-003 표). 요청 중 버튼 비활성, 실패 시 상태 유지·원인·`다시 시도`.
- 당일 완료: 완료 표시, 출발 행동 없음, 시트·다른 날짜 조회는 가능.
- 다른 날짜: `N일차 · 지난/예정 일정`, `오늘로 돌아가기`, 카드·행동 없음.
- `loading`(1초 초과만), `empty`(장소 0곳 + `장소 추가`), `error`(원인 + `다시 시도`).
- 앱 재시작 후 서버 상태와 동일하게 복원.
- 360dp·최대 글자 배율 screenshot: 시작 전·이동 중·도착·완료·건너뜀 포함·당일 완료·다른 날짜. 위치 권한 거부 기기에서 전체 수동 흐름 1회.

## 문서 동기화 확인

- `docs/design/er-schema.md`·`docs/design/api-spec.md`가 `contracts/progress.openapi.yaml`·`data-model.md`와 일치하는지 diff로 확인한다.
- Figma `ActiveTravelScreen`에 도착 상태 카드·당일 완료·상태 수정 시트가 추가되었는지 확인하고 저장소 사본을 갱신한다.

## Backend 최종 검증 기록 (#240, 2026-09-08, jh)

Windows 로컬 PostgreSQL에 `api/.env`를 프로세스 환경으로만 주입하고 아래 명령을 실행했다. 로컬 환경에는 `uv` 실행 파일과 Docker CLI가 없어 Issue에 적힌 `uv run pytest ...`는 시작하지 못했으며, 저장소의 Python 3.13 가상환경으로 같은 pytest 대상을 실행했다.

```powershell
cd api
$envFile = '.env'
Get-Content -Encoding utf8 $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $name, $value = $line -split '=', 2
        [Environment]::SetEnvironmentVariable(
            $name.Trim(), $value.Trim().Trim('"').Trim("'"), 'Process'
        )
    }
}
.\.venv\Scripts\python.exe -m pytest -p no:cacheprovider tests/unit tests/contract tests/integration
```

- 결과: `414 passed in 11.72s`
- migration 검증을 포함한 unit·contract·integration 전체가 통과했다.
- `uv run pytest tests/unit tests/contract tests/integration`: 미실행(`uv`가 PATH에 없음).
- Docker 기반 신규 DB에서의 별도 재검증: 미실행(Docker CLI가 설치되어 있지 않음). 연결된 로컬 PostgreSQL에서는 migration test가 통과했다.

SC-001의 Backend 처리 구간은 PostgreSQL integration test의 call 시간을 `pytest --durations=0`으로 측정했다. 아래 값은 service 호출과 test call의 나머지 검증을 포함하지만 HTTP 직렬화·네트워크·Android 상태 갱신·화면 렌더링은 포함하지 않는다. 외부 경로 provider는 `_Calculator` fixture로 대체했으며 실제 TMAP·ODsay 네트워크 지연도 측정하지 않았다. 따라서 Backend 기준 충족 근거로만 사용하고, 사용자 조작부터 화면 표시까지의 SC-001 최종 판정은 T040 실기기 E2E에서 별도로 확인한다.

| 기준 | 측정 시나리오 | call 시간 | 결과 |
|---|---|---:|---|
| 위치 없는 시작 2초 이내 | `test_start_commits_once_and_same_key_returns_stored_result`(최초 시작+멱등 재시도) | 0.13초 | Backend 통과, 사용자 가시 E2E는 T040 |
| 구간 계산 포함 시작 10초 이내 | `test_valid_start_location_persists_computed_segment_and_eta`(계산+멱등 재조회) | 0.09초 | Backend 통과, 실제 provider·사용자 가시 E2E는 미측정 |
| 구간 계산 없는 전환 2초 이내 | `test_arrive_depart_and_skip_update_versions_and_complete_day`(전환 4회) | 0.11초 | Backend 통과, 사용자 가시 E2E는 T040 |

계약·ERD·설정·log 대조 결과:

- 공통 오류 정책과 runtime에 맞춰 path·header·body 형식 오류를 `400 INVALID_REQUEST`, 장소 없음 도메인 오류를 `422 DAY_EMPTY`로 F006 OpenAPI·router·`api-spec.md`에서 일치시켰다. 오류 envelope의 `details`도 source OpenAPI에 명시했다.
- `trip_days.progress_version`, `progress_transitions`, `progress_segments`의 F006 컬럼·제약·FK는 migration 005·006, ORM, `data-model.md`, `er-schema.md`와 일치한다.
- `api/.env.example`의 DB 비밀번호와 provider key는 모두 placeholder이며 실제 credential은 없다.
- `gilpick.progress`·`gilpick.route`·`gilpick.api` log는 request ID와 식별자·결과 code만 기록한다. integration test가 시작·PATCH `Idempotency-Key`와 provider 실패 위치의 위도·경도를 log에 남기지 않음을 검증하고, API key는 공통 `SensitiveDataFilter` unit test가 마스킹을 검증한다.
- F007이 먼저 필수 응답으로 확장한 `detectionTargets`·`pendingCandidate`·`undoable`은 F007 완료 전에도 계약 형태를 지키도록 각각 `[]`·`null`·`null`을 반환한다. 이는 자동 감지가 아직 비활성인 상태를 나타내는 호환 placeholder다. generated OpenAPI의 타입 안정성을 위해 네 응답 DTO(`DetectionTarget`·`CandidateEvidence`·`PendingCandidate`·`UndoableTransition`)만 #240에서 먼저 제공했으며, F007 #259(T006)은 이를 재사용하고 나머지 감지 요청·결과 enum/schema를 구현한다. 실제 대상·후보 산출은 #261(T016), 되돌리기 산출은 #263(T024)이 구현한다. `progress_events` FK와 감지 조회 인덱스도 #259(T004) 소유이므로 #240에서 선행 구현하지 않는다.

## Android 실서버 검증 기록 (#242·#244, 2026-09-08, jy)

로컬 API(`main` 2c31a1b, BE #237·#238 병합 후) + `gilpick_api36_play`(headless, `adb emu geo fix 126.9770 37.5796` = 경복궁)로 실제 앱을 구동했다. 임시 instrumented 스크립트(`ProgressLiveE2E`, 커밋하지 않음)로 화면을 조작하고 uvicorn 로그·PROG-001 응답으로 서버 상태를 대조했다. 오늘 날짜 여행 `F006 검증`(9/8~9/9, 하루 3곳 도보)을 API로 만들었다.

| 시나리오 | 확인 내용 | 결과 |
|---|---|---|
| 상세 시작 분기 (T018) | 오늘 날짜 PROG-001 `NOT_STARTED` → `오늘 여행 시작` 활성. 기간 밖 여행(9/21) → 비활성 + `오늘은 여행 날짜가 아닙니다`. 오늘 장소 0곳(일정을 저장했다 비운 날짜) → `오늘 일정에 장소가 없어요…` + `장소 추가` | 통과 |
| 권한 허용 시작 (T017·T018) | 권한 허용 → 1회 위치 취득 → `POST …/progress/start` 200 → 진행 화면 이동. 서버 `startLocation` = (37.5796, 126.977), 1번 장소 `출발 위치에서 도보 0분 · 0m`, `예상 도착 오후 12:54` | 통과 |
| 권한 거부 시작 (T017·T018) | `pm revoke` + `user-fixed` 상태에서 시작 → 시스템 권한 화면이 거부로 즉시 닫힘 → 위치 null로 start 200 → 진행 화면. 서버 `startLocation=null`, 카드 `이동 정보 없음` | 통과 |
| 시작 후 상세 | 돌아오면 `여행 진행 화면으로` + `여행 중` 배지, 탭하면 진행 화면 재진입 | 통과 |
| 전환 (T029·T030) | `도착했어요` → `현재 장소` 카드·`오후 12:59 도착`·`체류 예정 90분`·`다음 장소로 출발`. 출발 → 1번 `완료`, 2번 `이동 중` ETA 재계산(오후 1:12). 시작 5분 뒤 카드에 `· 5분 지났어요` | 통과 |
| 409 재조회 (T029) | host가 API로 2번을 먼저 `SKIPPED`(v3→4)한 뒤 앱에서 `건너뛰기` → 서버 409 `VERSION_CONFLICT` → `진행 상태가 다른 곳에서 바뀌었어요…` 안내 + 자동 재조회로 2번 `건너뜀`·3번 `이동 중` 반영 | 통과 |
| 당일 완료 (T030) | 마지막 3번 `도착했어요` → 서버 `COMPLETED`(v5) → `오늘 일정을 모두 마쳤어요`·`2곳 방문 · 마지막 도착 오후 12:59`, 출발·도착 행동 없음, 상단 `1일차 · 2/3 완료` | 통과 |
| 경로 화면 진행 상태 (T031) | 시작된 9/8 `1일차 경로`: Naver 지도에 `현위치` pill, ✓(완료·도착), ✕(건너뜀) marker, 구간 목록 `경복궁 완료 → 북촌한옥마을 건너뜀`. 시작 전 9/9: 상태 문구·`현위치` 없이 기존 계획 표시 | 통과 |
| 진행 화면 지도 | RouteMap에 같은 marker, `경로 보기` → 오늘 경로 화면 | 통과 |

- 요청 중 버튼 비활성(`pendingAction`)은 로컬 서버 응답이 수백 ms라 실서버에서는 눈으로 잡히지 않았다. `ProgressViewModelTest`·`ActiveTravelScreenTest`가 대신 검증한다.
- Backend 발견(jh, #237 범위): 일정을 한 번도 저장하지 않은 여행 날짜에 `GET …/days/{date}/progress`가 `404 TRIP_NOT_FOUND`를 돌려준다(`trip_days` 행이 없어서). 계약(`progress.openapi.yaml` GET 설명·`DAY_EMPTY`)대로라면 기간 안 날짜는 `items: []`인 200이어야 하고, 앱은 그 응답으로 `장소 추가`를 안내한다. 지금은 이 경우 `지금은 여행을 시작할 수 없습니다`가 뜬다. 일정을 저장했다 비운 날짜는 200 `items: []`로 정상이다.
- 지도 marker는 Naver overlay라 Compose semantics에 잡히지 않아 screenshot(`r1_route_today.png`·`t3_after_conflict.png`)으로 확인했다. 시작 위치를 1번 장소와 같게 두어 `현위치` pill이 1번 marker와 겹친 것은 검증 데이터 탓이다.
- 검증 절차는 F004 quickstart 기록과 같다(postgres → `alembic upgrade head` → uvicorn 127.0.0.1:8000 → `adb reverse` → `-PGILPICK_API_BASE_URL` 빌드 → 세션 주입). TRANSIT 구간은 ODsay 키가 없어 `FAILED`가 되므로 도보만 썼다.

## Android 최종 검증 기록 (#246 T039·T040, 2026-09-08, jy)

### T039 접근성·시각·screenshot

- `ActiveTravelScreenshotTest` 32건(`gilpick_api36_play`, API 36): 기존 20건 + 360dp·글자 배율 2.0 조합 12건(시작 전·이동 중·지연·도착·당일 완료(건너뜀 포함)·지난 일정·예정 일정·전환 실패·loading·empty·error·시트). PNG는 `/sdcard/Android/data/com.gilpick/files/screenshots/`에서 pull해 대조했다.
- 48dp: 카드 행동 2개·`장소 추가`·`다시 시도`·`닫기`·`경로 보기`·`오늘로 돌아가기`·날짜 점·시트 행동·`취소`는 `ActiveTravelScreenTest`가 `assertHeightIsAtLeast(48.dp)`로 검증한다. 8dp: 배너·카드·목록 여백은 `LocalGilpickSpacing`(space2 이상)만 쓴다. 색 단독 금지: 상태는 chip 문구(`완료`·`건너뜀`·`도착`·`이동 중`·`예정`) + 아이콘(✓·✕·번호)으로, 지연은 `· N분 지났어요` 문구로, 목록 행 contentDescription에도 상태 문구가 들어간다.
- 수정: 360dp·2.0에서 `오늘로 돌아가기`가 `돌 / 아가기`로 접혔다 → 배너 라벨에 `weight(1f)`를 줘 버튼이 먼저 제 너비를 갖게 했다. 경로 없는 날짜의 지도 자리 문구는 가운데 정렬로 바꿨다.
- 남은 관찰(수정 안 함): 2.0에서 전환 실패 안내 `다시 시도 / 해 주세요`와 지도 자리 문구 `못했어 / 요`가 음절 단위로 접힌다(읽기에는 지장 없음). 시작 전 목록의 이동수단 아이콘은 글자 배율을 따라 커지지 않는다.
- 자동 검증: unit 353 통과, connected `com.gilpick.progress` 56 통과(ActiveTravelScreenTest 21 + ActiveTravelScreenshotTest 32 + ProgressNavigationTest 3).

### T040 종단간 검증

로컬 API(`main` 55ffd15, BE #240·#278 병합 후) + `gilpick_api36_play`(headless, `adb reverse tcp:8000`, `adb emu geo fix 126.9770 37.5796`). 임시 instrumented 스크립트(`ProgressFinalE2E`, 커밋하지 않음)로 실제 앱을 조작하고 PROG-001 응답과 대조했다. 검증용 여행: `F006 최종`(9/8~9/9, 9/8 도보 3곳), `F006 거부2`(9/8 2곳), `F006 미저장`(일정 저장 안 함).

| 시나리오 | 확인 내용 | 결과 |
|---|---|---|
| 시작(위치 있음) | 권한 허용 → 1회 위치 → start 200 → 진행 화면(클릭부터 10.8초, FLP 응답 대기 포함). 서버 `startLocation` (37.5796, 126.977), 1번 `출발 위치에서 도보 0분 · 0m`, ETA 3개 `시작+0`·`+체류+구간` 규칙과 일치 | 통과 |
| 시작(권한 거부) | `pm revoke` + `user-fixed` → 시스템 권한 화면이 거부로 닫힘 → 위치 null로 start 200(클릭부터 9.1초, 대부분 emulator의 권한 화면 왕복). 카드 `이동 정보 없음`, 서버 `startLocation=null` | 통과 |
| 미저장 날짜(#278) | 일정을 저장한 적 없는 오늘 날짜 → 200 `items: []` → 상세에 `오늘 일정에 장소가 없어요` + `장소 추가`, 오류 문구 없음 | 통과 |
| 도착 | 1번 `도착했어요` → `현재 장소` 카드·`오후 6:05 도착`·`체류 예정 90분`, 뒤 ETA가 실제 도착 기준으로 갱신(7:48·9:34) | 통과 |
| 출발 | `다음 장소로 출발` → 1번 `완료 · 방문 완료`, 2번 `이동 중` ETA 6:18(실제 출발+구간) | 통과 |
| 건너뛰기 | 카드 `건너뛰기` → 2번 `건너뜀`, 3번 `이동 중` ETA 6:24(1→3 구간) | 통과 |
| 상태 수정(완료 취소) | 1번 행 → 시트 `완료 취소`·`건너뛰기`만 표시 → `완료 취소` → 1번 `도착`, 2·3번 `예정`으로 초기화, 카드가 `현재 장소`로 복귀 | 통과 |
| 상태 수정(시트 건너뛰기) | 재출발 뒤 2번 행 → 시트 `도착으로 변경`·`건너뛰기` → `건너뛰기` → 2번 `건너뜀`, 3번 `이동 중` | 통과 |
| 당일 완료 | 3번 `도착했어요` → 서버 `COMPLETED` → `오늘 일정을 모두 마쳤어요`·`2곳 방문 · 마지막 도착 오후 6:05`, 도착·출발 행동 없음, 상단 `1일차 · 2/3 완료` | 통과 |
| 완료 뒤 시트·건너뛰기 취소 | 2번 행 → `건너뛰기 취소`만 표시 → 2번 `예정`, 날짜 `IN_PROGRESS` 복귀(카드 `현재 장소` 3번 + `다음 장소로 출발`) → 다시 `건너뛰기` → 당일 완료 복귀 | 통과 |
| 앱 재시작 복원 | `am force-stop` 뒤 재실행 → 상세 `여행 진행 화면으로` → 당일 완료 화면·목록 상태가 서버(`COMPLETED` v10, 완료/건너뜀/도착)와 동일 | 통과 |
| 다른 날짜 | `2일차 9월 9일` 점 → `2일차 · 예정 일정`·`오늘로 돌아가기`, 카드·행동 없음 → `오늘로 돌아가기`로 복귀 | 통과 |

- 요청 중 버튼 비활성과 409 재조회는 #242·#244 기록과 `ProgressViewModelTest`가 담당한다(로컬 응답이 수백 ms라 눈으로 잡히지 않음).
- 전환 응답 시간: 도착·출발·건너뛰기·상태 수정 모두 uvicorn 기준 1초 미만(도보 구간 재계산 포함).
- 절차는 #242·#244 기록과 같다. 검증 뒤 임시 여행 3개는 로컬 DB에 남겨 두었다.
