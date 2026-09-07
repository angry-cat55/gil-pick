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
