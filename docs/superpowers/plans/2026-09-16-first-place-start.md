# 첫 장소 시작 방식 분리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PROG-002에서 현재 위치에서 첫 장소로 이동하는 시작과 첫 장소에서 바로 시작하는 동작을 명시적으로 분리한다.

**Architecture:** `StartDayProgressRequest`가 `startMode`와 조건부 `transportMode`를 받는다. 서비스는 `MOVE_TO_FIRST`에서만 유효 위치와 선택 이동수단으로 시작 구간을 계산하고, `AT_FIRST_PLACE`에서는 provider 호출 없이 첫 장소를 도착 처리한다. 기존 idempotency, version, 별도 구간 저장 및 ETA 재계산 구조는 유지한다.

**Tech Stack:** Python 3.13, FastAPI, Pydantic v2, SQLAlchemy async, PostgreSQL/PostGIS, pytest, OpenAPI 3.1

**Spec:** `specs/006-trip-progress/spec.md`

## Global Constraints

- API와 프로젝트 문서는 한글로 작성한다.
- 실제 secret이 담긴 `.env`, `.env.*`는 열람하지 않는다.
- 경로 provider 실패와 유효 위치 부재는 여행 시작을 막지 않는다.
- 같은 `Idempotency-Key` 재요청은 최초 처리 결과를 유지한다.
- 시작 위치 구간과 F004 장소 간 구간의 의미를 섞지 않는다.

---

### Task 1: 시작 요청 계약

**Files:**
- Modify: `api/app/schemas/progress.py`
- Test: `api/tests/unit/test_progress_schema.py`
- Test: `api/tests/contract/test_progress_contract.py`

**Interfaces:**
- Consumes: `progressVersion`, 선택적 `currentLocation`
- Produces: `startMode: MOVE_TO_FIRST | AT_FIRST_PLACE`, `transportMode: WALK | TRANSIT | CAR | null`

- [x] **Step 1: 실패 테스트 작성**

  `MOVE_TO_FIRST`에는 `transportMode`가 필수이고 `AT_FIRST_PLACE`에는 금지되며, API 요청 JSON이 두 시작 방식을 수용하는 테스트를 추가한다.

- [x] **Step 2: 실패 확인**

  Run: `python -m pytest tests/unit/test_progress_schema.py tests/contract/test_progress_contract.py -q`
  Expected: 새 enum·조건부 검증이 없어 실패

- [x] **Step 3: 최소 구현**

  `StartMode` enum과 Pydantic `model_validator`를 추가해 두 필드의 조합을 검증한다.

- [x] **Step 4: 통과 확인**

  Run: `python -m pytest tests/unit/test_progress_schema.py tests/contract/test_progress_contract.py -q`
  Expected: PASS

### Task 2: 시작 상태 전환과 경로 계산

**Files:**
- Modify: `api/app/services/progress.py`
- Test: `api/tests/integration/test_progress_flow.py`

**Interfaces:**
- Consumes: `StartDayProgressRequest.start_mode`, `transport_mode`, 유효한 `current_location`
- Produces: 이동 시작 시 첫 장소 `EN_ROUTE`; 현장 시작 시 첫 장소 `ARRIVED` 및 실제 도착 시각; 이동 시작 성공 시 선택 수단의 `ProgressSegment`

- [x] **Step 1: 실패 테스트 작성**

  이동 시작이 WALK/TRANSIT/CAR 선택값을 calculator와 저장 구간에 전달하고, 위치 없음·provider 실패에도 시작하며, 현장 시작은 provider를 호출하지 않고 첫 장소를 ARRIVED로 만드는 통합 테스트를 추가한다. 장소 한 곳인 현장 시작은 기존 완료 규칙대로 당일 완료됨을 검증한다.

- [x] **Step 2: 실패 확인**

  Run: `python -m pytest tests/integration/test_progress_flow.py -k "start" -q`
  Expected: 고정 WALK 또는 현장 시작 미지원으로 실패

- [x] **Step 3: 최소 구현**

  시작 분기를 요청 모드별로 나누고 transition 영향 목록, ETA 기준, provider 호출과 `ProgressSegment.transport_mode`를 선택값에 맞춘다.

- [x] **Step 4: 통과 확인**

  Run: `python -m pytest tests/integration/test_progress_flow.py -k "start" -q`
  Expected: PASS

### Task 3: 명세와 전체 검증

**Files:**
- Modify: `specs/006-trip-progress/spec.md`
- Modify: `specs/006-trip-progress/plan.md`
- Modify: `specs/006-trip-progress/data-model.md`
- Modify: `specs/006-trip-progress/contracts/progress.openapi.yaml`
- Modify: `docs/design/api-spec.md`
- Modify: `docs/design/er-schema.md`
- Modify: `docs/planning/user-flow.md`
- Modify: `docs/planning/functional-spec.md`
- Modify: `docs/planning/requirements.md`

**Interfaces:**
- Consumes: Task 1·2에서 확정한 요청/상태/저장 계약
- Produces: 구현과 일치하는 F006 및 공통 문서

- [x] **Step 1: 문서 동기화**

  도보 고정 설명을 제거하고 두 시작 모드, 이동수단 조건, 위치/provider 실패 정책, 현장 시작의 상태 규칙을 기록한다.

- [x] **Step 2: 전체 검증**

  Run: `python -m pytest tests/unit/test_progress_schema.py tests/unit/test_progress_service.py tests/contract/test_progress_contract.py tests/integration/test_progress_flow.py -q`
  Expected: PASS

- [x] **Step 3: 정적 검증**

  Run: `python -m compileall -q app`
  Expected: PASS (`ruff`는 프로젝트 dev dependency에 없어 미실행)

- [x] **Step 4: 커밋**

  `git commit -m "feat: 첫 장소 시작 방식 분리"`
