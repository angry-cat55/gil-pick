# Quickstart: F009 대체 장소 추천 검증

구현이 끝난 뒤 이 순서로 확인한다. Backend는 자동 test(외부 provider는 `httpx` mock transport)로, Android는 unit·androidTest·screenshot으로 검증하고, 실제 TourAPI·Google·기상청·서울시 연동은 G001 공유 환경 또는 local `.env` 자격으로 마지막에 확인한다.

## 사전 조건

- F008 감지가 동작하는 상태: `docker compose up -d postgres` → `alembic upgrade head`(migration 007 포함) → `uvicorn app.main:app`(`JWT_SIGNING_SECRET` 환경변수 필수).
- 오늘 날짜(Asia/Seoul)에 장소가 2곳 이상이고 `오늘 여행 시작`을 끝낸 여행에서, 남은 장소 하나에 F008 `ACTIVE` 감지가 있어야 한다. 가장 쉬운 방법은 그 장소의 `estimated_arrival_at`을 폐점 시각 이후로 두고 `evaluate_all_active`를 1회 실행하는 것이다(F008 quickstart BE 1).
- `.env`에 `TOUR_API_KEY`, `GOOGLE_PLACES_API_KEY`가 있으면 실제 후보가 나온다. 없으면 자동 test의 mock만 동작한다.
- 계약: [contracts/alternatives.openapi.yaml](contracts/alternatives.openapi.yaml). 데이터 모델·상태 모델: [data-model.md](data-model.md). 결정 근거: [research.md](research.md).

## 자동 검증

```bash
# Backend (api/)
.venv/Scripts/python.exe -m pytest tests/unit/test_alternative_scoring.py tests/unit/test_alternative_candidates.py tests/unit/test_candidate_token.py tests/contract/test_alternatives_contract.py tests/contract/test_detections_contract.py
# Android (android/)
gradlew.bat --offline -q :app:testDebugUnitTest :app:assembleDebug
gradlew.bat --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.alternative
gradlew.bat --offline :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.gilpick.progress
```

`tests/contract/test_auth_contract.py`의 기존 9건 실패는 F009와 무관하다(origin/main 기준).

## Backend 시나리오

### BE 1. 후보 조회 기본 (US1 Scenario 1·4·6, FR-002·FR-003·FR-012, SC-001)

1. TourAPI `locationBasedList2` mock에 기존 장소 500m 안 같은 소분류 장소 12곳(`dist` 포함)을 둔다. Google Text Search mock은 각 후보에 확정 매칭 응답(`rating`·`userRatingCount`·`regularOpeningHours.periods` 운영 중)을 준다.
2. `GET /api/v1/detections/{detectionId}/alternatives`가 `200`, `searchRadiusMeters=500`, `categoryMatchLevel=SMALL`, `items.length=10`, `score` 내림차순, `rank` 1~10인지 확인한다.
3. 두 후보의 `score`를 같게 만든 fixture로 거리 짧은 → `adjustedRating` 높은 → `userRatingCount` 많은 순서가 지켜지는지 확인한다.
4. `displayScore`가 `round(score)`이고 정렬은 `score`로 되는지(예: 86.4 vs 86.6이 둘 다 표시 86이어도 순서 유지) 확인한다.

### BE 2. 반경 사다리와 분류 단계 (US1 Scenario 2·3, Clarifications)

1. 500m 안에는 후보가 없고 800m에 중분류만 같은 장소가 있으면 `searchRadiusMeters=1000`, `categoryMatchLevel=MIDDLE`인지 확인한다.
2. 2km 안에 같은 내부 카테고리 장소가 없으면 `200`, `items=[]`, `searchRadiusMeters=2000`, `categoryMatchLevel=NONE`이고 오류가 아닌지 확인한다.
3. 기존 장소가 Google 전용(`tour_content_id` 없음)이면 소·중분류 단계를 건너뛰고 `LARGE`로 비교하는지 확인한다.
4. 기존 장소 자신과 같은 날짜 일정의 다른 장소가 TourAPI 응답에 있어도 후보에서 빠지는지 확인한다(FR-005).

### BE 3. 운영 종료 제외와 확인 상한 (US1 Scenario 5, FR-004, Clarifications)

1. 예비 점수 1위 후보의 Google 응답을 ETA 이후 폐점으로 두면 목록에서 빠지고 나머지로 10개가 채워지는지 확인한다.
2. `businessStatus=CLOSED_TEMPORARILY`인 후보도 빠지는지 확인한다.
3. Google 응답에 `regularOpeningHours`가 없는 후보는 빠지지 않고 `operatingStatus=UNKNOWN`, `closesAt=null`인지 확인한다(US4 Scenario 4).
4. 후보 30곳 중 상위 20곳까지 모두 폐점이면 Google mock 호출 수가 `OPERATING_CHECK_LIMIT`(20)에서 멈추고 `items=[]`이며 `searchRadiusMeters`는 그대로(반경 확장 없음)인지 확인한다.

### BE 4. 점수 변수 결손 (US4 Scenario 1·2, FR-011·FR-020, SC-004)

1. Google Text Search mock이 모두 실패(timeout 2회)하면 `200`으로 후보가 반환되고 `scoreBreakdown`에 `rating` 키가 없으며 `operatingStatus=UNKNOWN`인지 확인한다.
2. 혼잡 지원지역 밖 후보는 `scoreBreakdown.congestion`이 없고, 실내 카테고리 후보는 `scoreBreakdown.weather`가 없는지 확인한다.
3. 기상청 mock 실패 시 모든 후보에서 `weather`만 빠지고 응답은 성공인지 확인한다.
4. 가중치 재분배: `rating`·`weather` 제외 시 `score = 100 × (0.35·d + 0.20·c) / 0.55`인지 수치로 확인한다.

### BE 5. 추천 실패와 감지 상태 (US1 Scenario 7·8, US4 Scenario 3, FR-001·FR-021·FR-024, SC-005·SC-007)

1. TourAPI mock이 timeout 2회면 `504 TOUR_API_TIMEOUT`, 5xx면 `502 TOUR_API_FAILED`이고 빈 목록으로 위장하지 않는지 확인한다.
2. `DISMISSED`·`RESOLVED`·`INVALIDATED` 감지에 ALT-001·ALT-002를 호출하면 `409 DETECTION_NOT_ACTIVE`, `details.status`에 현재 상태인지 확인한다.
3. 다른 사용자 토큰으로 ALT-001·ALT-002·DETECT-004를 호출하면 `403`(또는 존재 은닉 정책에 따라 `404`)인지 확인한다.
4. ALT-001·ALT-002 호출 전후로 `itinerary_items`·`routes`·`detections` 행이 바뀌지 않는지 확인한다(SC-006).

### BE 6. 감지 거절 (US2 Scenario 3·4, FR-016·FR-017, SC-010)

1. `POST /api/v1/detections/{detectionId}/dismiss` → `200`, `status=DISMISSED`, `decidedAt` 존재, DB `resolved_at` 기록.
2. 같은 요청을 다시 보내면 `200`, 같은 `status`·`decidedAt`, `resolved_at` 불변.
3. `INVALIDATED` 감지에 보내면 `200`, `status=INVALIDATED`, `decidedAt=null`, 상태 불변.
4. 거절 직후 `GET /trips/{tripId}/detections?status=ACTIVE`에 그 감지가 없고, 필터 없이 조회하면 `DISMISSED`로 보이는지 확인한다.

### BE 7. 직접 검색 (US3, FR-018~FR-020)

1. `GET /api/v1/detections/{detectionId}/alternatives/search?query=카페`가 PLACE-001과 같은 `place` DTO에 `distanceMeters`·`operatingStatus`·`visitable`·`inSchedule`을 더해 `meta.pagination`과 함께 반환하는지 확인한다.
2. `query=카`(1글자)는 `400 INVALID_REQUEST`.
3. 기존 장소 자신이 결과에 있으면 `inSchedule=true`, `visitable=false`.
4. Google 병합 결과 `businessStatus=CLOSED_TEMPORARILY`면 결과에서 빠지지 않고 `operatingStatus=CLOSED`, `visitable=false`.
5. 페이지 처리(`cursor`)와 TourAPI 실패 시 오류 형식이 PLACE-001과 같은지 확인한다.

### BE 8. 후보 식별자 (FR-013·FR-014)

1. `candidateId`를 `verify_candidate_token`으로 검증하면 `detection_id`·`place_id`·`evaluated_at`이 응답과 일치한다.
2. 서명 1바이트 변조 → `None`. `evaluated_at`을 16분 전으로 발급한 토큰 → `None`.

### BE 9. DETECT-001 확장 (research R7)

1. `status=ACTIVE` 필터가 동작하고 필터 없는 기존 호출과 응답 형식이 호환되는지, 각 항목에 `eta`·`reason`이 있는지 `tests/contract/test_detections_contract.py`에서 확인한다.

## Android 시나리오

### AND 1. 대체 장소 화면 상태 (US2, UI-002·UI-003·UI-006, SC-009)

1. fake repository로 후보 4곳 → 상단 감지 요약(장소명·이유·변수 칩)과 `추천 후보 4곳`, 1위 `TOP` 강조, 각 행에 순위·이름·카테고리·거리·평점·운영 상태(마감 시각)·근거 문구가 보이는지 `AlternativePlacesScreenTest`로 확인한다.
2. 후보 0곳 → `2km 안에 추천할 장소가 없어요` + `기존 일정 그대로 진행`·`직접 검색해서 고르기`.
3. 추천 실패(502/504/네트워크) → 오류 상태 + `다시 시도하기` + 돌아가기, 재시도 시 재조회.
4. 409 → `Closed` 상태 문구와 진행 화면으로 돌아가기.
5. 1초 미만 로딩에는 대기 표시가 없고, 재조회 중에는 기존 목록이 유지되는지 확인한다.
6. 평점 없는 후보는 `★` 항목이 없고, `UNKNOWN`은 `운영시간 확인 불가`, `CLOSING_SOON`은 경고색+문구 병기인지 확인한다.

### AND 2. 선택 전달·직접 검색·거절 (US2 Scenario 2·3, US3, FR-015·FR-016)

1. 1위 `경로 비교`·다른 행 `비교` 탭 → `onSelectPlace(SelectedAlternative)`에 `detectionId`·`placeId`·`candidateId`·`name`·`distanceMeters`·`displayScore`가 전달된다(`AlternativeNavigationTest`).
2. `직접 검색` → `AlternativeSearchScreen`; 2글자 미만은 검색하지 않고 안내; 결과 행에 `기존 장소에서 N m`·`방문 불가`/`이미 일정에 있음` 표시와 비활성; 방문 가능 행 선택 시 `candidateId=null`로 같은 콜백에 전달.
3. `기존 일정 그대로 진행` → DETECT-004 호출, 성공 시 `onDismissed` 호출, 요청 중 버튼 비활성, 실패 시 오류 표시 후 화면 유지(`AlternativeViewModelTest`).

### AND 3. 진행 화면 배너 (UI-001, FR-028)

1. `ProgressViewModel`에 `ACTIVE` 감지 2건 fake → 오늘 날짜에서 ETA가 이른 감지가 배너에 `장소명 + 이유 / 도착 예정 시각 · N분 전 감지`로 보이고 `외 1곳` 표시; 탭 → `onOpenAlternatives(detectionId)`.
2. 감지 0건 또는 조회 실패 → 배너 없음, 나머지 진행 화면 정상(`ActiveTravelScreenTest`).
3. 다른 날짜를 보고 있을 때 배너가 숨겨지는지 확인한다.

### AND 4. Screenshot (UI-008·UI-009)

`AlternativeScreenshotTest`: 후보 있음·후보 없음·추천 실패·처리된 감지 × (360dp 기본, 360dp fontScale 2.0) = 8장, `ActiveTravelScreenshotTest`에 배너 있음 2장 추가. 360dp·2.0에서 잘림·가로 스크롤이 없고 터치 대상 48dp(`assertTouchHeightIsEqualTo`/`sizeIn`)를 확인한다. ATD `gilpick_api36`에서 `captureToImage`로 저장한다.

### AND 5. 실서버 연동 (UI-009, SC-008)

1. local API(:8000, `adb reverse`)와 실제 TourAPI·Google 키로 `ACTIVE` 감지가 있는 여행을 준비한다(F008 quickstart BE 1 절차, 시드는 F006/F008 검증 때의 `api.py` 재사용).
2. `gilpick_api36_play`에서 진행 화면 배너 → 대체 장소 화면 → 후보 목록 확인(3탭 이내 선택 가능) → `비교` 탭 시 콜백 도달 log 확인.
3. `기존 일정 그대로 진행` → 진행 화면 복귀 후 배너 소멸, DB `status=DISMISSED`.
4. 직접 검색으로 키워드 검색 → 거리·방문 가능 표시 확인.
5. 결과(screenshot 경로·요청 log·requestId)는 PR 본문과 이 문서 아래 "검증 기록" 절에 남긴다.

## 문서 동기화 확인

- `docs/design/api-spec.md` 2절·7절, `docs/design/er-schema.md` 8.1·13절, `docs/planning/requirements.md`·`functional-spec.md` 5절이 [data-model.md](data-model.md) 4절의 목록대로 갱신됐는지 확인한다.
- `docs/planning/mvp-features.md` F009 상태 전이(READY → IN_PROGRESS → VERIFY → DONE)를 각 PR에 포함한다.
