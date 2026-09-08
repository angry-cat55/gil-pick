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
