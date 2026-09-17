# Transit Step Geometry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kakao 대중교통 단계의 geometry를 보존하고 좌표 공백을 TMAP WALK로 보완하며, 보완 실패 시 날짜 경로 전체를 실패 처리한다.

**Architecture:** Kakao adapter는 provider-neutral step별 geometry만 정규화하고, `RouteCalculationService`가 장소와 단계 경계의 연속성을 검사해 TMAP WALK 보완을 조율한다. 공개 schema는 nullable step geometry로 legacy JSONB를 읽되, 신규 Kakao 계산은 연속된 geometry를 필수로 만든다.

**Tech Stack:** Python 3.13, FastAPI, Pydantic v2, asyncio, pytest, Kotlin Serialization

**Spec:** `docs/superpowers/specs/2026-09-17-transit-step-geometry-design.md`

## Global Constraints

- 전체 경로 계산 deadline은 기존 10초 설정을 공유한다.
- 보완 실패는 숨기지 않고 기존 `RouteProviderError`와 `FAILED` 계약으로 전파한다.
- 시간·거리 합계는 Kakao 값을 유지하고 TMAP은 geometry만 보완한다.
- 실제 좌표 두 점을 보행 경로로 가장하는 fallback을 만들지 않는다.
- 기존 `steps=[]`와 step `geometry` 누락 JSONB를 조회할 수 있어야 한다.

---

### Task 1: 단계별 geometry 계약

**Files:**
- Modify: `api/app/clients/route_provider.py`
- Modify: `api/app/clients/kakao_transit.py`
- Modify: `api/app/schemas/route.py`
- Test: `api/tests/unit/test_transit_steps.py`
- Test: `api/tests/unit/test_route_schema.py`

**Interfaces:**
- Produces: `NormalizedTransitStep.geometry: list[Coordinate] | None`
- Produces: `RouteStep.geometry: RouteGeometry | None`

- [ ] Kakao 각 step의 `path.points`가 해당 정규화 step geometry로 남고 API `geometry`로 직렬화되는 실패 테스트를 작성한다.
- [ ] step geometry가 없는 legacy API payload가 `geometry=None`으로 읽히는 실패 테스트를 작성한다.
- [ ] 두 테스트를 실행해 신규 필드 부재 때문에 실패하는지 확인한다.
- [ ] `NormalizedTransitStep`과 `RouteStep`에 nullable geometry를 추가하고 `_steps()`가 메타데이터와 같은 raw step의 points를 변환하게 한다.
- [ ] 테스트를 다시 실행해 통과시킨다.

### Task 2: 대중교통 보행 공백 보완

**Files:**
- Modify: `api/app/services/route.py`
- Test: `api/tests/unit/test_transit_geometry_enrichment.py`

**Interfaces:**
- Consumes: `NormalizedRoute.steps[*].geometry`
- Produces: `_enrich_transit_geometry(route, origin, destination, deadline) -> NormalizedRoute`

- [ ] 장소→첫 WALK, WALK→BUS, BUS→WALK, 마지막 WALK→장소의 공백을 TMAP 결과로 보완하고 Kakao 합계를 유지하는 실패 테스트를 작성한다.
- [ ] 3m 이하 공백은 TMAP 호출 없이 endpoint가 이어지는 실패 테스트를 작성한다.
- [ ] WALK가 인접하지 않은 공백과 TMAP provider 실패가 `RouteProviderError`로 전파되는 실패 테스트를 작성한다.
- [ ] 테스트를 실행해 enrichment 함수 부재로 실패하는지 확인한다.
- [ ] haversine 거리, endpoint 정렬, TMAP WALK 호출, WALK geometry 병합, 전체 geometry 재조합을 최소 구현한다.
- [ ] `_calculate_segment()`가 TRANSIT 결과에 enrichment를 적용하고 기존 retry·deadline 경계 안에서 실패를 전파하게 한다.
- [ ] 테스트를 다시 실행해 통과시킨다.

### Task 3: 저장·공개 계약·Android 호환

**Files:**
- Modify: `api/tests/unit/test_transit_steps.py`
- Modify: `api/tests/contract/test_route_contract.py`
- Modify: `android/app/src/main/java/com/gilpick/route/RouteApi.kt`
- Modify: `android/app/src/main/java/com/gilpick/route/RouteMap.kt`
- Modify: `android/app/src/test/java/com/gilpick/route/RouteApiTest.kt`
- Modify: `android/app/src/test/java/com/gilpick/route/RouteMapTest.kt`

**Interfaces:**
- Produces: `RouteStepDto.geometry: RouteGeometryDto? = null`
- Produces: `segmentPath()`가 Backend segment geometry를 그대로 반환

- [ ] step geometry JSONB round-trip과 legacy geometry 누락 조회 실패 테스트를 작성한다.
- [ ] Android 신규·legacy JSON 역직렬화와 marker 직선 삽입 제거 실패 테스트를 작성한다.
- [ ] Backend·Android 테스트를 실행해 기대한 이유로 실패하는지 확인한다.
- [ ] DTO와 `segmentPath()`를 최소 수정하고 API route mapping이 geometry를 보존하게 한다.
- [ ] 관련 Backend·Android 테스트를 다시 실행해 통과시킨다.

### Task 4: 명세 동기화와 최종 검증

**Files:**
- Modify: `specs/005-route-calculation/spec.md`
- Modify: `specs/005-route-calculation/plan.md`
- Modify: `specs/005-route-calculation/tasks.md`
- Modify: `specs/005-route-calculation/data-model.md`
- Modify: `specs/005-route-calculation/research.md`
- Modify: `specs/005-route-calculation/contracts/route.openapi.yaml`
- Modify: `docs/design/api-spec.md`
- Modify: `specs/005-route-calculation/quickstart.md`

**Interfaces:**
- Documents: nullable legacy geometry, strict 신규 계산, TMAP 보완, 전체 실패, attribution

- [ ] 승인된 정책과 `#686` task를 F005 산출물 및 OpenAPI에 반영한다.
- [ ] Backend unit·contract·integration test를 디렉터리별로 실행한다.
- [ ] Android route unit test와 compile task를 실행한다.
- [ ] `speckit-analyze`로 spec·plan·tasks 일관성을 검사하고 발견된 충돌을 수정한다.
- [ ] 실제 credential이 필요한 Kakao→TMAP 종단 검증은 secret을 읽지 않고 실행 가능 여부만 판단해 quickstart와 PR에 사실대로 기록한다.
- [ ] 변경사항을 commit·push하고 PR을 생성한다.
