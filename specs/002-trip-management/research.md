# Phase 0 Research: 여행 관리

## 1. 기술 스택과 버전

**Decision**: Backend는 F001과 동일하게 Python 3.13, FastAPI 0.141.1, SQLAlchemy 2.0.52 async, Alembic, PostgreSQL 18.6/PostGIS 3.6.x를 그대로 사용한다. Android는 Kotlin 2.4.10, `compileSdk 37`, `targetSdk 36`, `minSdk 26`, Compose BOM 2026.08.00을 그대로 사용한다.

**Rationale**: `docs/decisions/tech-stack.md`와 F001 `research.md`에서 이미 확정한 조합이며, F002는 인증 위에서 동작하는 다음 feature이므로 새로운 런타임·버전 결정이 필요 없다.

**Alternatives considered**: 해당 없음 (F001에서 이미 결정).

## 2. `trips` 테이블 소유 범위

**Decision**: F002 migration은 `docs/design/er-schema.md` 5.1절의 `trips` 테이블만 생성한다. `trip_days`, `itinerary_items`는 생성하지 않는다.

**Rationale**: `er-schema.md` 1절은 "여행은 `trips`, 날짜별 진행은 `trip_days`, 방문 장소는 `itinerary_items`로 분리한다"고 명시하고, `trip_days`의 역할은 "날짜별 진행 상태·시작시각·일정 버전"으로 F004(일정 구성)·F006(여행 진행)의 실제 데이터에 종속된다. F001이 인증에 필요한 테이블만 만들었던 선례를 따라, F002도 여행 CRUD(TRIP-001~005)에 필요한 `trips`만 만들고 `trip_days`/`itinerary_items`는 해당 데이터를 실제로 사용하는 F004에서 만든다.

**Alternatives considered**:

- `trips`와 `trip_days`를 F002에서 함께 생성: `trip_days`의 대부분 컬럼(`schedule_version`, `actual_started_at`, `start_location`, `detection_active` 등)이 F004·F006 전용이라 F002 범위를 벗어나는 컬럼을 미리 만들게 된다.
- `trip_days`를 F002에서 "날짜 스켈레톤"만 최소 컬럼으로 만들고 F004에서 `ALTER TABLE`로 나머지 컬럼 추가: 한 논리 테이블을 두 migration에 걸쳐 나눠 만들면 스키마 변경 이력이 흩어지고 F004 시작 시 매번 확인이 필요하다.

이 결정은 Backend 담당자(jh)의 확인이 필요한 항목으로 별도 표시한다(아래 5절).

## 3. 여행 목록·상세 응답에서 일정 관련 필드 처리

**Decision**: `docs/design/api-spec.md`의 TRIP-001 응답 `dayCount`는 저장된 행이 아니라 `end_date - start_date + 1`로 계산해 반환한다. TRIP-003 응답의 `days[]`(날짜별 `itemCount`, `routeStatus`)는 `trip_days`/`itinerary_items`가 아직 없는 F002 시점에는 채울 데이터가 없으므로, F002 구현에서는 이 필드를 생략하거나 빈 배열로 반환하고 F004(일정)·F005(경로) 완료 후 실제 값을 채운다.

**Rationale**: 기존 계약 문서(api-spec.md)는 모든 feature를 아우르는 최종 형태를 미리 그려둔 문서다. F001이 `AuthenticatedHomeScreen`을 F002 전까지 빈 shell로 유지했던 것과 같은 방식으로, F002도 아직 존재하지 않는 하위 feature 데이터를 임의로 만들어내지 않는다.

**Alternatives considered**: `itemCount: 0`, `routeStatus` 임시 enum 값을 지금 확정: 아직 F004·F005에서 합의되지 않은 값을 F002가 선점하게 되어 이후 계약 변경 위험이 커진다.

## 4. 동시 수정 제어

**Decision**: `trips.version` 정수 컬럼과 낙관적 동시성 제어를 사용한다. `PATCH /api/v1/trips/{tripId}` 요청은 `version`을 필수로 받고, 저장된 값과 다르면 `409 VERSION_CONFLICT`를 반환한다.

**Rationale**: `docs/design/er-schema.md`와 `docs/design/api-spec.md`에 이미 설계되어 있으며 `docs/decisions/tech-stack.md` 4절의 "일정 저장은 optimistic version을 사용해 동시 편집 충돌을 막는다"는 기존 아키텍처 원칙과도 일치한다. (clarify 단계에서 처음 검토했던 last-write-wins안은 이 기존 설계와 충돌해 철회했다.)

**Alternatives considered**: last-write-wins — 기존 문서·아키텍처와 충돌해 제외.

## 5. 열린 항목 (Backend 담당자 확인 필요)

- 2절의 "F002는 `trips`만 생성" 결정과 3절의 "F002는 `days[]`를 생략/빈 배열로 반환" 결정은 `docs/design/api-spec.md`·`docs/design/er-schema.md`를 새로 수정하지 않고 F002 구현 범위로만 적용한 해석이다. Backend 담당자가 다른 판단을 하면 `spec.md`가 아니라 이 문서와 `docs/design/api-spec.md`/`er-schema.md` 동기화 여부를 먼저 조정해야 한다.
