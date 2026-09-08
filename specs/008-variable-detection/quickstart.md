# Quickstart: F008 여행 변수 감지 검증

구현이 끝난 뒤 이 순서로 확인한다. 모든 시나리오는 Backend 자동 test로 검증하며, 기상청·서울시 실제 연동은 G001 공유 환경에서 별도로 확인한다. 그 전에는 두 client를 `httpx` mock transport와 계약 fixture로 대체한다.

## 사전 준비

- `docker compose up -d db`(PostgreSQL/PostGIS), `alembic upgrade head`(migration 007 `detections` 포함), `uvicorn app.main:app --reload`
- `.env`에 `KMA_SERVICE_KEY`, `SEOUL_CITYDATA_API_KEY`, `GOOGLE_PLACES_API_KEY`. 값이 없으면 해당 변수는 항상 제외되고 그 경로는 skip으로 표시한다.
- F006이 먼저 동작해야 한다. 오늘 날짜(Asia/Seoul)에 장소가 2곳 이상이고 `오늘 여행 시작`까지 끝낸 여행을 만들어 `trip_days.detection_active=true`, 남은 장소의 `estimated_arrival_at`이 채워진 상태에서 시작한다.
- 시각 경계 검증은 서버 시각을 고정하거나 장소의 `estimated_arrival_at`·외부 예보 fixture 시각을 조작한다.
- 계약 스키마: [contracts/detections.openapi.yaml](contracts/detections.openapi.yaml). 데이터 모델·상태 전이: [data-model.md](data-model.md). 판정 규칙 근거: [research.md](research.md).

## 주기 평가·재평가 실행 방법

- 주기 작업은 `uvicorn` 기동 시 lifespan에서 자동으로 뜬다. test에서는 `evaluator.evaluate_all_active(session)` 또는 `evaluator.reevaluate_day(session_factory, trip_day_id)`를 직접 호출한다.
- 주기 간격은 `DETECTION_CYCLE_SECONDS`(기본 600)로 조정한다.

## Backend 검증

### BE 1. 운영시간 방문 불가 감지 생성·조회 (US1, FR-008·FR-012, SC-002)

1. 남은 장소 하나의 `estimated_arrival_at`을 그 장소 폐점 시각 이후로 둔다(Google `regularOpeningHours.periods` fixture 사용).
2. `evaluate_all_active`를 1회 실행한다.
3. `GET /api/v1/trips/{tripId}/detections`(DETECT-001)에 그 장소의 항목이 `status=ACTIVE`, `primaryType=OPERATING_HOURS`, `totalRiskScore>0`으로 나오는지 확인한다.
4. `GET /api/v1/detections/{detectionId}`(DETECT-002)에서 `variables.operatingHours.visitBlocked=true`, `variables.operatingHours.available=true`인지 확인한다.
5. 어느 변수도 위험하지 않은 다른 남은 장소에는 감지 결과가 **생성되지 않는지** 확인한다(US1 Scenario 5).

### BE 2. 혼잡·날씨 변수 판정 (US1 Scenario 2·3·4, FR-006·FR-007)

1. 500m 이내 지원 지점이 있는 장소에 ETA 시점 `FCST_CONGEST_LVL` fixture를 `붐빔`으로 둔다. `variables.congestion.available=true`, `level=CROWDED`, `crowded=true`인지 확인한다.
2. 500m 밖 장소는 `congestion.available=false`, `unavailableReason=NOT_IN_SUPPORT_AREA`인지 확인한다.
3. 실외 또는 미상 장소에 `POP=80` fixture를 두면 `weather.available=true`, `atRisk=true`인지 확인한다.
4. 실내로 분류되는 장소(예: `CAFE`)에 같은 `POP=80`을 두면 `weather.available=false`, `unavailableReason=INDOOR`이고 그 장소가 **날씨만을 이유로는 감지 결과를 만들지 않는지** 확인한다(SC-007).

### BE 3. ETA 변경 시 재평가 (US2, FR-003, SC-001)

1. 앞 장소를 늦게 `ARRIVED`/`COMPLETED` 처리해 뒤 장소의 `estimated_arrival_at`이 폐점 시각 이후로 밀리게 한다(PROG-006).
2. 주기(600초)를 기다리지 않고, 진행 전환 응답 직후 그 장소에 `OPERATING_HOURS visitBlocked=true` 감지 결과가 생기는지 확인한다.
3. 반대로 ETA가 당겨져 혼잡 예보가 더 이상 혼잡이 아니게 되면, 기존 `ACTIVE` 행의 `variables`와 `totalRiskScore`가 낮아진 값으로 갱신되고 `status`는 `ACTIVE`로 유지되는지 확인한다(US2 Scenario 2).
4. 여러 뒤 장소의 ETA가 함께 바뀌면 영향받은 남은 장소만 재평가되고 `COMPLETED`·`SKIPPED` 장소는 대상에서 빠지는지 확인한다(US2 Scenario 3).

### BE 4. 중복 억제와 종료 (US3, FR-013·FR-014·FR-016, SC-003·SC-005)

1. 위험이 지속되는 장소에 대해 `evaluate_all_active`를 연속 3회 실행한다. 그 장소의 `ACTIVE` 감지 결과가 **정확히 1행**이고 `last_evaluated_at`만 갱신되는지 확인한다(SC-003).
2. 같은 `trip_day_id`에 대해 두 평가를 동시에 실행해도 `uq_detections_active_fingerprint` 위반 없이 1행만 남는지 확인한다.
3. 그 장소를 `SKIPPED` 처리하면 감지 결과가 `INVALIDATED`, `resolved_at` 채워짐으로 바뀌고 목록에서 `ACTIVE`로 남지 않는지 확인한다(SC-005).
3a. 다른 장소에서 그 장소를 `ARRIVED`로 전환해도 그 장소의 `ACTIVE` 감지 결과가 `INVALIDATED`가 되는지 확인한다(FR-014).
4. 남은 마지막 장소 도착으로 그 날짜가 `COMPLETED`가 되면 그 날짜의 모든 `ACTIVE` 감지 결과가 `INVALIDATED`가 되고, 이후 그 날짜에 대한 평가가 실행되지 않는지 확인한다(US3 Scenario 3).
5. 상태 수정으로 `COMPLETED` 날짜가 다시 `IN_PROGRESS`(`detection_active=true`)가 되면 남은 장소에 대해 새 `ACTIVE` 감지 결과가 생길 수 있는지 확인한다(US3 Scenario 4).

### BE 5. 외부 데이터 결손 격리 (US4, FR-009·FR-010, SC-004·SC-009)

1. 서울시 client가 항상 타임아웃(>5초)하도록 두고 평가한다. 혼잡만 `available=false, unavailableReason=TIMEOUT`이고 날씨·운영시간으로 감지 결과가 정상 생성되는지, 1회 재시도 후 실패 처리되는지 확인한다.
2. 기상청 client가 예보를 반환하지 않게 두면 날씨만 제외되고 나머지로 평가가 계속되는지 확인한다.
3. 세 변수(서울시·기상청·Google Places)를 모두 실패시키면 그 장소에 대한 감지 결과가 **생성되지 않고 오류로도 처리되지 않는지** 확인한다(SC-009).
4. 한 장소의 외부 결손이 같은 날짜 다른 장소의 평가를 실패시키지 않는지 확인한다.

### BE 6. 대상·소유권 경계 (FR-001·FR-020, SC-006·SC-008)

1. 진행이 시작되지 않은 날짜, 이미 `COMPLETED`된 날짜, 다른 사용자의 여행에 대해 감지 결과가 생성되지 않는지 확인한다(SC-006).
2. ETA(`estimated_arrival_at`)가 `NULL`인 장소는 평가 대상에서 빠지는지 확인한다.
3. 다른 사용자의 access token으로 DETECT-001·002·003을 호출하면 각각 `403`(`TRIP_FORBIDDEN`/`DETECTION_FORBIDDEN`) 또는 `404`가 반환되는지 확인한다(SC-008).

### BE 7. 읽음 처리 (FR-019)

1. `PATCH /api/v1/detections/{detectionId}/read`(DETECT-003) 호출 후 `data.read=true`, 목록·상세의 `read=true`인지 확인한다.
2. 같은 요청을 다시 보내도 `200`이고 `read_at`이 최초 값 그대로인지 확인한다(멱등).

## 확인되지 않는 항목 (G001 이후)

- 기상청 격자 변환·`base_time` 선택·`PCP` 문자열 파싱의 실데이터 정확도
- 서울시 지원 지점 최종 목록·좌표와 `citydata_ppltn` 실제 응답 스키마
- Google Places `places.get`의 `regularOpeningHours.periods` 과금·쿼터 영향
