---

description: "F008 여행 변수 감지 구현 task 목록"
---

# Tasks: 여행 변수 감지

**Input**: `specs/008-variable-detection/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/detections.openapi.yaml`, `quickstart.md`

**Organization**: Feature Owner는 `ts`(명세 산출물)다. Backend 구현 담당은 `jh`다(2026-09-08 합의). Frontend는 이 Feature에서 구현하지 않으며 DETECT 계약 review만 FE 담당(F011 담당 예정, 현재 미정)이 수행한다. `api/app/api/v1/progress.py`(F006 파일) 수정은 원 담당자 `jy` review를 받는다. 테스트는 명세의 독립 검증·성공 기준과 constitution 품질 게이트를 만족하도록 구현보다 먼저 배치한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 미완료 선행 작업이 없고 수정 파일이 다른 작업
- **[Story]**: 해당 User Story 추적 표기
- 보조 메타데이터의 선행·검증 조건까지 완료해야 task 완료로 본다.

## Path Conventions

- Backend: `api/app/`, `api/migrations/`, `api/tests/`
- 문서: `docs/design/`, `docs/planning/`, `specs/008-variable-detection/`

---

## Phase 1: Setup

**Purpose**: DETECT 계약을 공용 문서에 고정하고, 외부 연동 설정값과 조정 가능한 정책값을 한곳에 만든다.

- [ ] T001 F008 DETECT 계약·ERD·API 명세 대조와 교차 review in specs/008-variable-detection/contracts/detections.openapi.yaml, docs/design/api-spec.md, docs/design/er-schema.md
  - 영역: 통합
  - 담당: jh
  - 교차 확인: FE 담당(미정)
  - 선행: 없음
  - 검증: `detections.openapi.yaml`의 DETECT-001·002·003 경로가 `api-spec.md` §7과 일치, `status` enum이 `er-schema.md` §10(`ACTIVE`·`RESOLVED`·`DISMISSED`·`INVALIDATED`)과 일치, 상세 응답 확장 필드(`operatingHours.visitBlocked`·`tempClosed`, `weather.precipitationType`, `congestion.sensitivity`, 각 변수 `unavailableReason`)와 `api-spec.md` §7 예시의 `PENDING_DECISION` 교체 대상을 목록화. `er-schema.md` §8.1(`detections`)·§10은 변경 없이 그대로 구현함을 대조해 PR에 기록. 실제 `api-spec.md` 반영은 T029에서 수행
- [ ] T002 [P] 기상청·서울시·감지 주기 설정값 추가 in api/app/core/config.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: `kma_base_url`·`kma_service_key`(SecretStr), `seoul_citydata_base_url`·`seoul_citydata_api_key`(SecretStr), `detection_provider_timeout_seconds`(기본 5.0, gt=0), `detection_cycle_seconds`(기본 600, gt=0) 추가. 키가 비어도 `get_settings()`가 성공하고 앱이 기동되는지 `api/tests/unit/test_config.py`에서 확인(다른 선택 provider 키와 동일 취급)
- [ ] T003 [P] 서울시 혼잡 지원 지점 설정 파일과 로더 in api/app/services/detection/congestion_areas.json, api/app/services/detection/congestion_areas.py, api/app/services/detection/__init__.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: `{version, source, areas:[{name, areaCode, latitude, longitude}]}` 스키마를 로더가 검증. 장소 좌표에서 `ST_DWithin(location, area_point, 500)`으로 최근접 지원 지점 1곳을 찾고 없으면 `None`을 반환하는 것을 `api/tests/unit/test_congestion_areas.py`에서 fixture 좌표로 확인. 목록 자체는 G001에서 확정하므로 초기값은 검증용 소수 지점 + `version` 표기
- [ ] T004 [P] 감지 정책 모듈 in api/app/services/detection/policy.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: 날씨 임계값(`POP≥70`, `PCP≥1.0`, `PTY∈{1,2,3,4}`), 폐점 임박 창(30분), 혼잡 위험 경계(민감도 HIGH→`SLIGHTLY_CROWDED` 이상 / MEDIUM→`CROWDED`), 카테고리 민감도 매핑, 실내외 노출도 매핑(`NATURE`=OUTDOOR / `SHOPPING`·`CAFE`·`FOOD`=INDOOR / `HISTORY_CULTURE`·`OTHER`·미분류=UNKNOWN), 종합 점수 가중치(`OPERATING_HOURS 0.5`·`WEATHER 0.25`·`CONGESTION 0.25`)와 변수 내 심각도를 모두 이 모듈에서 읽는다. `api/tests/unit/test_detection_policy.py`에서 값 접근과 기본값 확인. 값 근거는 research.md §6·§7, 반영 요청은 T030

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 User Story가 공유하는 `detections` 저장 구조, 공개 스키마, 외부 데이터 client를 만든다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 User Story 구현을 시작하지 않는다.

- [ ] T005 `detections` migration 007 in api/migrations/versions/007_create_detections_table.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: `er-schema.md` §8.1 컬럼 전체(`detection_id`·`trip_day_id`·`item_id`·`primary_type`·`status`·`eta`·`score numeric(7,6)`·`reason`·`evaluation_snapshot jsonb`·`fingerprint`·`detected_at`·`last_evaluated_at`·`read_at`·`resolved_at`), `UNIQUE INDEX uq_detections_active_fingerprint ON detections(fingerprint) WHERE status='ACTIVE'`, `INDEX ix_detections_day_status_detected(trip_day_id, status, detected_at DESC)`, `INDEX ix_detections_item(item_id)`, `CHECK` 3종(score 범위·primary_type·status), `trip_days`·`itinerary_items` `ON DELETE CASCADE`. upgrade·downgrade 왕복을 `api/tests/integration/test_detection_migration.py`에서 확인
- [ ] T006 [P] `Detection` ORM model in api/app/models/detection.py, api/app/models/__init__.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: model column·constraint가 migration과 일치, `evaluation_snapshot`은 `JSON().with_variant(JSONB, "postgresql")`, `trip_day`·`item` relationship, `fingerprint` 기본 생성 helper(`f"{trip_day_id}:{item_id}"`)를 `api/tests/unit/test_detection_model.py`에서 확인
- [ ] T007 [P] DETECT 공개 스키마·enum in api/app/schemas/detection.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: 계약의 `DetectionStatus`·`DetectionType`·`UnavailableReason`, `DetectionListEnvelope`/`DetectionListItem`, `DetectionDetailEnvelope`/`DetectionDetail`, `VariableVerdicts`와 세 변수 verdict(`CongestionVerdict`·`WeatherVerdict`·`OperatingHoursVerdict`), `DetectionReadEnvelope`, `PaginatedMeta`, `ErrorEnvelope`(code 6종)을 `api/tests/unit/test_detection_schema.py`에서 계약과 대조. 응답 전용 모델은 camelCase alias
- [ ] T008 [P] 기상청 단기예보 client in api/app/clients/kma.py
  - 영역: BE
  - 담당: jh
  - 선행: T002
  - 검증: 위경도 → 기상청 LCC(DFS) 격자(nx·ny) 변환, `getVilageFcst` 호출(`base_date`/`base_time` 최신 발표 선택), 응답에서 `POP`·`PCP`·`PTY`를 `fcstDate`+`fcstTime` 슬롯별로 파싱. 타임아웃 5초·시간 초과·일시 오류만 1회 재시도, 최종 실패·격자 변환 불가 시 `None`. `api/tests/unit/test_kma_client.py`에서 `httpx` mock transport와 계약 fixture로 확인. 실데이터 검증은 G001
- [ ] T009 [P] 서울시 실시간 도시데이터 client in api/app/clients/seoul_citydata.py
  - 영역: BE
  - 담당: jh
  - 선행: T002
  - 검증: `citydata_ppltn` 호출(JSON), `AREA_CONGEST_LVL`와 `FCST_PPLTN[].{FCST_TIME, FCST_CONGEST_LVL}` 파싱, 4단계(`여유`·`보통`·`약간 붐빔`·`붐빔`) → `RELAXED`·`NORMAL`·`SLIGHTLY_CROWDED`·`CROWDED` 매핑. 타임아웃 5초·1회 재시도, 최종 실패 시 `None`. `api/tests/unit/test_seoul_citydata_client.py`에서 mock transport와 fixture로 확인. 실데이터 검증은 G001
- [ ] T010 [P] Google Places 운영시간 조회 helper in api/app/services/detection/operating_hours_source.py
  - 영역: BE
  - 담당: jh
  - 선행: T002
  - 검증: 기존 `google_places` 설정을 재사용해 `places.get`을 `regularOpeningHours.periods`·`businessStatus`·`utcOffsetMinutes` field mask로 호출. `CLOSED_TEMPORARILY`·`CLOSED_PERMANENTLY` 식별, `periods`에서 특정 요일 `close` 시각 추출, `periods` 없음/24시간 → "미상". 타임아웃 5초·1회 재시도, 최종 실패·키 없음 → "미상". `api/tests/unit/test_operating_hours_source.py`에서 fixture로 확인

**Checkpoint**: `detections`를 저장·조회할 수 있고, 세 외부 데이터 원천을 격리된 방식으로 호출할 수 있다.

---

## Phase 3: User Story 1 - 위험해진 남은 장소를 시스템이 먼저 찾아낸다 (Priority: P1) 🎯 MVP

**Goal**: 진행 중인 당일 남은 장소를 10분마다 ETA 시점 기준으로 평가해, 혼잡·날씨·운영시간 중 하나라도 위험이면 `detections` 행을 만들고 사용자가 목록·상세·읽음 API로 확인할 수 있게 한다.

**Independent Test**: 진행을 시작한 당일 남은 장소 하나의 ETA를 폐점 시각 이후로 두고 주기 평가를 1회 실행하면, DETECT-001·002에서 그 장소에 `status=ACTIVE`·`primaryType=OPERATING_HOURS`·`variables.operatingHours.visitBlocked=true` 감지 결과가 보인다. 어느 변수도 위험하지 않은 장소에는 감지 결과가 생기지 않는다. F009·F010·F011 없이 검증한다.

### Tests for User Story 1

- [ ] T011 [P] [US1] DETECT-001·002·003 계약 test in api/tests/contract/test_detections_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T007
  - 검증: 세 endpoint의 응답 envelope·필드·enum이 `contracts/detections.openapi.yaml`과 일치, cursor 페이지네이션 meta(`nextCursor`·`hasNext`), 오류 code(`TRIP_FORBIDDEN`·`TRIP_NOT_FOUND`·`DETECTION_FORBIDDEN`·`DETECTION_NOT_FOUND`)
- [ ] T012 [P] [US1] 운영시간 방문 불가 감지 생성·조회 integration test in api/tests/integration/test_detection_operating_hours.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T007
  - 검증: quickstart BE 1(폐점 이후 ETA → `ACTIVE`·`OPERATING_HOURS`·`visitBlocked=true`가 목록·상세에 노출, 위험 없는 다른 장소는 미생성), quickstart BE 6(시작 전·완료 날짜·타 사용자·ETA null 제외), SC-002·SC-006
- [ ] T013 [P] [US1] 날씨·혼잡 변수 판정 integration test in api/tests/integration/test_detection_weather_congestion.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T007
  - 검증: quickstart BE 2(500m 지원지점 `붐빔`→`congestion.crowded=true`, 500m 밖→`NOT_IN_SUPPORT_AREA`, 실외/미상 `POP=80`→`weather.atRisk=true`, 실내 `CAFE` `POP=80`→`INDOOR`·날씨만으로 미생성), SC-007

### Implementation for User Story 1

- [ ] T014 [P] [US1] 날씨 평가기 in api/app/services/detection/weather.py
  - 영역: BE
  - 담당: jh
  - 선행: T004, T008
  - 검증: `kma` 예보 슬롯을 ETA에 맞춰 선택하고 `policy` 임계값으로 위험 판정. 실내외 노출도가 `INDOOR`면 `available=false`·`unavailableReason=INDOOR`, 예보 없음/실패면 `NO_FORECAST`/`TIMEOUT`. `WeatherVerdict` 반환. 단위 test 포함
- [ ] T015 [P] [US1] 혼잡 평가기 in api/app/services/detection/congestion.py
  - 영역: BE
  - 담당: jh
  - 선행: T003, T004, T009
  - 검증: 500m 지원 지점 매핑 → `seoul_citydata` ETA 슬롯 혼잡 수준 → 카테고리 민감도로 위험 판정(거리 감쇠 없음). 지원지역 아님 → `NOT_IN_SUPPORT_AREA`, 실패 → `TIMEOUT`. `CongestionVerdict`(`level`·`sensitivity`·`crowded`) 반환. 단위 test 포함
- [ ] T016 [P] [US1] 운영시간 평가기 in api/app/services/detection/operating_hours.py
  - 영역: BE
  - 담당: jh
  - 선행: T004, T010
  - 검증: `operating_hours_source` 결과로 ETA ≥ 폐점 → `visitBlocked=true`, 폐점 30분 전 이내 → `closingSoon=true`, 임시·영구 휴업 → `visitBlocked=true`·`tempClosed`. 미상 → `available=false`·`unavailableReason=HOURS_UNKNOWN`(운영 중 가정). `OperatingHoursVerdict` 반환. 단위 test 포함
- [ ] T017 [US1] 종합 위험 점수 산출 in api/app/services/detection/scoring.py
  - 영역: BE
  - 담당: jh
  - 선행: T004
  - 검증: `available=false` 변수를 빼고 `policy` 가중치를 비례 재정규화 후 심각도 가중합(0~1). `round(score*100)`이 표시용 `totalRiskScore`. `primary_type`은 기여 최대 변수(동점 시 `OPERATING_HOURS`>`WEATHER`>`CONGESTION`). `api/tests/unit/test_detection_scoring.py`에서 재정규화·반올림·동점 규칙 확인
- [ ] T018 [US1] 평가 orchestrator: 대상 선별·평가·`ACTIVE` upsert in api/app/services/detection/evaluator.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T014, T015, T016, T017
  - 검증: 대상 = `detection_active=true` AND `trip_days.status='IN_PROGRESS'` AND `visit_date=오늘(KST)` 날짜의 `itinerary_items.status IN ('PLANNED','EN_ROUTE')` AND `estimated_arrival_at IS NOT NULL`. 세 변수 평가 후 위험 ≥ 1이면 `INSERT ... ON CONFLICT (fingerprint) WHERE status='ACTIVE' DO UPDATE`로 `eta`·`evaluation_snapshot`·`score`·`reason`·`primary_type`·`last_evaluated_at` 저장. 위험 0이면 생성 안 함. 세 변수 모두 `available=false`면 생성 안 함(오류 아님). `evaluation_snapshot`에 변수별 원값·가용성·가중치·판정·`unavailable_reason`과 사용 ETA 기록, 좌표·이동 경로 미기록. `evaluate_all_active(session)` 공개. quickstart BE 1·2로 검증
- [ ] T019 [US1] 10분 주기 작업 in api/app/jobs/variable_detection.py
  - 영역: BE
  - 담당: jh
  - 선행: T018
  - 검증: `run_variable_detection(session_factory, *, interval_seconds=600)`가 `run_auth_cleanup` 패턴(`while True: try / logger.exception / await asyncio.sleep`)으로 `evaluate_all_active`를 호출. 실패는 log만 남기고 다음 주기 진행. `interval_seconds`는 `detection_cycle_seconds`에서 주입. `api/tests/unit/test_variable_detection_job.py`에서 1회 tick 동작과 예외 격리 확인
- [ ] T020 [US1] DETECT-001·002·003 endpoint와 소유권 검증 in api/app/api/v1/detections.py
  - 영역: BE
  - 담당: jh
  - 선행: T007, T018
  - 검증: `GET /api/v1/trips/{tripId}/detections`(cursor·limit, 정렬 `detected_at DESC, detection_id`, 불투명 cursor), `GET /api/v1/detections/{detectionId}`, `PATCH /api/v1/detections/{detectionId}/read`(멱등, 이미 읽음이면 `read_at` 유지). DETECT-001은 `trip→user`, DETECT-002·003은 `detection→trip_day→trip→user` 소유권 검증 후 타인 요청 `403`/`404`. quickstart BE 6·7, SC-008
- [ ] T021 [US1] lifespan 작업 등록과 router include in api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T019, T020
  - 검증: lifespan에서 `asyncio.create_task(run_variable_detection(create_session_factory()))` 등록·취소(`auth_cleanup`와 동일 구조), `detections` router `include_router(..., prefix="/api/v1")`. `uvicorn --workers >1` 시 작업이 worker마다 뜨는 점과 단일 worker 권장을 주석·PR에 기록. `api/tests/smoke`로 기동 확인

**Checkpoint**: 주기 평가가 돌고, 위험 장소에 `ACTIVE` 감지 결과가 1행 생기며, 세 DETECT endpoint로 조회·읽음이 된다. US1만으로 배포 가능(MVP).

---

## Phase 4: User Story 2 - 도착 예정 시각이 바뀌면 평가도 따라간다 (Priority: P2)

**Goal**: F006이 남은 장소의 ETA를 다시 계산하면 다음 정기 주기를 기다리지 않고 그 날짜의 남은 장소를 재평가한다.

**Independent Test**: 진행 중 날짜에서 앞 장소를 늦게 도착 처리해 뒤 장소 ETA를 폐점 시각 이후로 민 뒤, 주기(600초)를 기다리지 않고 그 장소에 운영시간 "방문 불가" 감지 결과가 생기는지 확인한다.

### Tests for User Story 2

- [ ] T022 [P] [US2] ETA 변경 재평가 integration test in api/tests/integration/test_detection_reevaluation.py
  - 영역: BE
  - 담당: jh
  - 선행: T018
  - 검증: quickstart BE 3(앞 장소 지연 → 뒤 장소 재평가로 `visitBlocked` 감지 생성; ETA 당겨져 혼잡 아님 → 기존 `ACTIVE` 행의 `variables`·`totalRiskScore` 갱신·`status` 유지; 여러 뒤 장소 동시 변경 → 영향 장소만 재평가, `COMPLETED`·`SKIPPED` 제외), SC-001

### Implementation for User Story 2

- [ ] T023 [US2] `reevaluate_day` 함수 in api/app/services/detection/evaluator.py
  - 영역: BE
  - 담당: jh
  - 선행: T018
  - 검증: `reevaluate_day(session_factory, trip_day_id)`가 그 날짜의 US1 대상 장소 전체를 다시 평가해 `ACTIVE` 행을 upsert·갱신. 실패는 예외를 삼키고 log만 남긴다. `evaluate_all_active`와 평가 로직을 공유(중복 구현 금지)
- [ ] T024 [US2] 진행 전환 commit 이후 재평가 훅 in api/app/api/v1/progress.py
  - 영역: BE
  - 담당: jh
  - 교차 확인: jy (F006 원 담당자 review)
  - 선행: T023
  - 검증: PROG-002(시작)·PROG-006(수동 상태 전환) 처리의 **transaction commit 이후**(PROG-002가 도보 구간 계산을 transaction 밖에서 하는 패턴과 동일) `reevaluate_day`를 호출. 진행 응답 반환·상태·성능에 영향 없음, 재평가 실패가 진행 요청을 실패시키지 않음. F007 자동 확정(PROG-004) 연결은 이번 범위가 아니며 T031에서 요청으로 기록

**Checkpoint**: US1 + ETA 변경 시 즉시 재평가가 동작한다.

---

## Phase 5: User Story 3 - 같은 장소를 두 번 경고하지 않는다 (Priority: P3)

**Goal**: 한 장소에 pending(`ACTIVE`) 감지 결과가 있으면 새로 만들지 않고 갱신만 하며, 장소가 `도착`·`완료`·`건너뜀`되거나 일정에서 빠지거나 당일이 완료되면 종료한다.

**Independent Test**: 위험이 지속되는 장소에 주기 평가를 여러 번 지나가게 해 `ACTIVE` 감지 결과가 1행만 유지되는지, 그 장소를 건너뛰기하면 `INVALIDATED`가 되는지 확인한다.

### Tests for User Story 3

- [ ] T025 [P] [US3] 중복 억제·종료·재개 integration test in api/tests/integration/test_detection_lifecycle.py
  - 영역: BE
  - 담당: jh
  - 선행: T018
  - 검증: quickstart BE 4(연속 3회 평가에도 `ACTIVE` 1행·`last_evaluated_at`만 갱신; 같은 `trip_day_id` 동시 평가에도 `uq_detections_active_fingerprint` 위반 없이 1행; `SKIPPED`·`도착`·`COMPLETED` 전환 → `INVALIDATED`·`resolved_at`; 당일 완료 → 그 날짜 모든 `ACTIVE` 종료·이후 평가 없음; 날짜 `IN_PROGRESS` 복귀 → 새 `ACTIVE` 허용), SC-003·SC-005

### Implementation for User Story 3

- [ ] T026 [US3] 종료·재개 생명주기 in api/app/services/detection/evaluator.py
  - 영역: BE
  - 담당: jh
  - 선행: T018
  - 검증: 평가 진입 시 대상에서 빠진 항목(`status IN ('ARRIVED','COMPLETED','SKIPPED')`·일정에서 제거·`trip_days.status='COMPLETED'`)의 `ACTIVE` 감지 결과를 `INVALIDATED`(`resolved_at=now`)로 전환. `RESOLVED`·`DISMISSED` 행이 있는 장소에는 새 `ACTIVE`를 만들지 않음(FR-013). 모든 위험이 사라져도 `ACTIVE`는 유지하고 `score`만 낮춘다(US2 Scenario 2와 구분). `INVALIDATED` 행은 재사용하지 않고, 날짜 복귀 시 새 행으로 생성
- [ ] T027 [US3] 종료 처리를 주기·재평가 진입점에 연결 in api/app/services/detection/evaluator.py, api/app/jobs/variable_detection.py
  - 영역: BE
  - 담당: jh
  - 선행: T026
  - 검증: `evaluate_all_active`와 `reevaluate_day` 모두 평가 전에 T026 종료 로직을 거치고, 당일 완료된 날짜는 이후 주기에서 대상에서 제외됨을 `test_detection_lifecycle.py`로 확인

**Checkpoint**: US1 + US2 + 중복 억제·종료·재개가 동작한다.

---

## Phase 6: User Story 4 - 외부 데이터가 빠져도 감지는 계속된다 (Priority: P4)

**Goal**: 기상청·서울시·Google Places 중 일부를 못 받아도 받은 변수만으로 평가를 계속하고, 세 변수 모두 못 받으면 감지 결과를 만들지 않으며 오류로도 처리하지 않는다.

**Independent Test**: 서울시 조회가 실패하도록 두고 운영시간·날씨만으로 감지 결과가 생성되는지, 상세에 혼잡이 "사용 불가"로 표시되는지, 세 변수 모두 실패 시 감지 결과가 생성되지 않는지 확인한다.

### Tests for User Story 4

- [ ] T028 [P] [US4] 외부 데이터 결손 격리 integration test in api/tests/integration/test_detection_degradation.py
  - 영역: BE
  - 담당: jh
  - 선행: T018
  - 검증: quickstart BE 5(서울시 타임아웃 → 혼잡만 `TIMEOUT`·나머지로 생성·1회 재시도 후 실패; 기상청 무응답 → 날씨만 제외; 세 원천 모두 실패 → 미생성·오류 아님; 한 장소 결손이 같은 날짜 다른 장소 평가를 실패시키지 않음), SC-004·SC-009

### Implementation for User Story 4

- [ ] T029 [US4] client 재시도·타임아웃 확정과 평가기 격리 보장 in api/app/clients/kma.py, api/app/clients/seoul_citydata.py, api/app/services/detection/operating_hours_source.py, api/app/services/detection/evaluator.py
  - 영역: BE
  - 담당: jh
  - 선행: T008, T009, T010, T018
  - 검증: 세 원천 모두 `requirements.md` §3(각 5초·1회 재시도·최종 실패 시 해당 변수 제외)을 따르고, 타임아웃도 결손과 동일 처리. `evaluator`가 한 변수 예외를 잡아 그 변수만 `available=false`로 두고 나머지 평가를 완료. 한 장소 평가 실패가 다른 장소·다른 여행 평가로 전파되지 않도록 장소 단위 예외 격리. `test_detection_degradation.py` 통과

**Checkpoint**: 4개 User Story 모두 독립적으로 동작한다.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T030 [P] `api-spec.md` §7 동기화 in docs/design/api-spec.md
  - 영역: 문서
  - 담당: jh
  - 교차 확인: FE 담당(미정)
  - 선행: T001, T020
  - 검증: DETECT-001·002 예시의 `"status": "PENDING_DECISION"`를 `ACTIVE`로 교체하고 `status` 4값 표 추가, 상세 `variables`에 `operatingHours.visitBlocked`·`tempClosed`·`weather.precipitationType`·`congestion.sensitivity`·각 변수 `unavailableReason` 반영, §7.1의 "임계값 초과 시 장소 변경 제안 알림을 1회 생성"을 F011 알림 조건으로 읽히도록 문구 정리. 구현 PR과 같은 PR
- [ ] T031 [P] 정책값·계약 추가 요청 기록 in docs/planning/requirements.md, docs/planning/functional-spec.md
  - 영역: 문서
  - 담당: jh
  - 선행: T004, T017
  - 검증: 혼잡 위험 경계·종합 점수 가중치·변수 심각도·실내외 매핑을 `functional-spec.md` §4와 `requirements.md` DET 항목에 반영(값 출처 = `policy.py`). F003 장소 상세에 `regularOpeningHours.periods`·`businessStatus` 구조화 노출 요청, F007 자동 확정 후 `reevaluate_day` 연결 요청을 팀 공유사항으로 명시
- [ ] T032 [P] 파생 로직 단위 test 보강 in api/tests/unit/test_kma_grid.py, api/tests/unit/test_operating_hours_parse.py
  - 영역: BE
  - 담당: jh
  - 선행: T008, T016, T017
  - 검증: 기상청 격자 변환 known-value, `PCP` 범주 문자열 파싱("강수없음"·"1.0mm"·"30.0~50.0mm"), 폐점 시각 요일 매칭·자정 넘김, 점수 비례 재정규화 경계
- [ ] T033 quickstart 검증 실행 in specs/008-variable-detection/quickstart.md
  - 영역: 통합
  - 담당: jh
  - 선행: T021, T024, T027, T029
  - 검증: BE 1~7 전 시나리오를 실행하고 결과를 PR에 기록. 외부 client는 mock/계약 fixture. 미검증 항목(G001 의존)을 명시
- [ ] T034 [P] Feature 상태 갱신 지점 정리 in docs/planning/mvp-features.md
  - 영역: 문서
  - 담당: jh
  - 선행: 없음
  - 검증: F008 행 상태를 검증된 tasks 문서 PR에서 `READY`, 첫 구현 Issue PR에서 `IN_PROGRESS`, 전체 구현·검증 PR에서 `VERIFY`, `main` 병합 후 `DONE`으로 갱신(AGENTS.md 6절). 이 task는 각 전이를 일으킨 PR에 포함

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음. T001~T004 대부분 병렬
- **Foundational (Phase 2)**: T001 완료 후. 모든 User Story를 차단
- **User Stories (Phase 3~6)**: Foundational 완료 후 시작
  - US1(P1)은 단독. US2·US3·US4는 US1의 `evaluator.py`(T018)에 의존하며 같은 파일을 수정하므로 **US1 완료 후 순차** 진행 권장(병렬 시 T023·T026·T027·T029가 `evaluator.py`를 동시 수정하므로 소유권 조율 필요)
- **Polish (Phase 7)**: 관련 User Story 완료 후

### User Story Dependencies

- **US1 (P1)**: Foundational 후 시작, 다른 story 의존 없음 → MVP
- **US2 (P2)**: T018 필요. `evaluator.py`·`progress.py` 수정
- **US3 (P3)**: T018 필요. `evaluator.py`·`variable_detection.py` 수정
- **US4 (P4)**: T018 필요. client 3종·`evaluator.py` 수정
- US2·US3·US4는 서로 독립 검증 가능하나 `evaluator.py`를 공통 수정하므로 한 담당(`jh`)이 순차로 처리

### Within Each User Story

- 계약·integration test를 먼저 작성해 실패를 확인한 뒤 구현
- client·평가기(모델·서비스) → orchestrator → job·endpoint → lifespan 연결
- story 완료(테스트·검증 포함) 후 다음 우선순위로 이동

### Parallel Opportunities

- Phase 1: T002·T003·T004 병렬(T001은 계약 대조라 먼저 착수 권장)
- Phase 2: T006·T007·T008·T009·T010 병렬(T005 완료 후 T006)
- Phase 3: 테스트 T011·T012·T013 병렬 / 평가기 T014·T015·T016 병렬 → T017 → T018 → (T019·T020) → T021
- Phase 7: T030·T031·T032·T034 병렬

---

## Parallel Example: User Story 1 평가기

```bash
# 세 변수 평가기는 서로 다른 파일이라 병렬:
Task: "날씨 평가기 in api/app/services/detection/weather.py"
Task: "혼잡 평가기 in api/app/services/detection/congestion.py"
Task: "운영시간 평가기 in api/app/services/detection/operating_hours.py"

# US1 테스트도 병렬:
Task: "DETECT 계약 test in api/tests/contract/test_detections_contract.py"
Task: "운영시간 방문 불가 integration test in api/tests/integration/test_detection_operating_hours.py"
Task: "날씨·혼잡 판정 integration test in api/tests/integration/test_detection_weather_congestion.py"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료(마이그레이션·모델·스키마·client 3종)
3. Phase 3 US1 완료 → 주기 평가 + 감지 생성 + DETECT 조회
4. **중단하고 검증**: quickstart BE 1·2·6·7로 US1 독립 검증
5. 준비되면 배포/데모

### Incremental Delivery

1. Setup + Foundational → 기반 완료
2. US1 → 독립 검증 → 배포(MVP)
3. US2(ETA 재평가) → 독립 검증
4. US3(중복 억제·종료) → 독립 검증
5. US4(결손 격리) → 독립 검증
6. Phase 7 Polish(문서 동기화·검증 실행)

### 담당 전략

- Backend 구현 전부 `jh` 단독. US1 완료 후 US2·US3·US4를 우선순위대로 순차 진행(`evaluator.py` 단일 소유로 충돌 방지)
- `api/app/api/v1/progress.py` 수정(T024)은 `jy` review 필수
- DETECT 계약(T001)과 `api-spec.md` 동기화(T030)는 FE 담당(F011 담당 예정)이 계약 사용성 review

---

## Notes

- `[P]`는 다른 파일·미완료 선행 없음을 뜻한다. 같은 파일(특히 `evaluator.py`)을 수정하는 task는 병렬 금지
- `[Story]` 표기로 task–User Story 추적
- 구현 전 관련 테스트가 실패하는지 확인
- 각 task 완료는 코드 + 명시된 테스트·검증 + 필요한 문서 동기화까지 포함
- 외부 실데이터(기상청·서울시·Google Places 운영시간) 검증은 G001 Gate에 의존하며, 그 전에는 mock·계약 fixture로 대체하고 미검증 항목을 PR에 남긴다
- 좌표·이동 경로를 `detections`·log에 남기지 않는다(constitution V)
