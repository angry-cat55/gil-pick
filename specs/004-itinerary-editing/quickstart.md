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
7. fixture로 `status COMPLETED` 항목을 만든 뒤 장소 교체·이동 수단 변경·삭제는 `409 ITINERARY_ITEM_LOCKED`, 체류 시간·순서 변경은 `200`.
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
