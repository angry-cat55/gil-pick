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

## 3. 여행 API 응답에서 일정 관련 필드 처리

**Decision**: `docs/design/api-spec.md`의 TRIP-001 응답 `dayCount`는 저장된 행이 아니라 `end_date - start_date + 1`로 계산해 반환한다. TRIP-003 응답의 `days[]`(날짜별 `itemCount`, `routeStatus`)는 `trip_days`/`itinerary_items`가 아직 없는 F002 시점에는 생략한다. TRIP-004 수정 성공 응답은 F002 계약의 `TripEnvelope`를 사용해 `deletedDayCount`·`deletedItemCount`를 생략하고, 기간 축소 확인 오류의 `details.deletedItemCount`만 항상 `0`으로 반환한다. F004(일정)·F005(경로) 완료 후 실제 일정 기반 필드가 필요하면 계약 version을 갱신해 추가한다.

**Rationale**: 기존 계약 문서(api-spec.md)는 모든 feature를 아우르는 최종 형태를 미리 그려둔 문서다. F001이 `AuthenticatedHomeScreen`을 F002 전까지 빈 shell로 유지했던 것과 같은 방식으로, F002도 아직 존재하지 않는 하위 feature 데이터를 임의로 만들어내지 않는다.

**Alternatives considered**: `itemCount: 0`, `routeStatus` 임시 enum 값을 지금 확정: 아직 F004·F005에서 합의되지 않은 값을 F002가 선점하게 되어 이후 계약 변경 위험이 커진다.

## 4. 동시 수정 제어

**Decision**: `trips.version` 정수 컬럼과 낙관적 동시성 제어를 사용한다. `PATCH /api/v1/trips/{tripId}` 요청은 `version`을 필수로 받고, 저장된 값과 다르면 `409 VERSION_CONFLICT`를 반환한다.

**Rationale**: `docs/design/er-schema.md`와 `docs/design/api-spec.md`에 이미 설계되어 있으며 `docs/decisions/tech-stack.md` 4절의 "일정 저장은 optimistic version을 사용해 동시 편집 충돌을 막는다"는 기존 아키텍처 원칙과도 일치한다. (clarify 단계에서 처음 검토했던 last-write-wins안은 이 기존 설계와 충돌해 철회했다.)

**Alternatives considered**: last-write-wins — 기존 문서·아키텍처와 충돌해 제외.

## 5. 사용자별 여행 기간 중복 방지

**Decision**: 같은 사용자의 `deleted_at IS NULL` 여행 기간은 PostgreSQL `btree_gist` 확장과 partial GiST exclusion constraint로 겹치지 않게 강제한다. 범위는 `daterange(start_date, end_date, '[]')`로 양 끝 날짜를 포함한다. 생성·기간 수정에서 제약 위반은 공통 `409 TRIP_PERIOD_CONFLICT`로 매핑한다.

**Rationale**: 서비스에서 충돌 여부를 먼저 조회하는 방식만으로는 서로 다른 요청이 동시에 검사한 뒤 모두 저장되는 경쟁 상태를 막지 못한다. DB exclusion constraint는 사용자별·삭제되지 않은 행만 대상으로 동시 요청까지 하나의 불변식으로 보장하며 별도 range 컬럼도 필요 없다. 생성 멱등 재요청은 기존 deterministic `trip_id` 결과를 먼저 확인하거나 자기 행을 충돌 검사에서 제외해 같은 요청이 자기 자신과 충돌하지 않게 한다.

Migration은 기존 활성 여행의 겹침을 self join으로 사전 검사한다. 충돌이 있으면 여행을 임의 삭제·축소하지 않고 명시적으로 실패시켜 운영자가 정리한 뒤 다시 실행한다. `btree_gist` 확장 생성 권한도 배포 환경에서 사전 확인한다.

**Alternatives considered**:

- 애플리케이션 `SELECT`만 사용: 동시 생성·수정 경쟁을 막지 못해 제외한다.
- 사용자별 advisory transaction lock과 충돌 조회: 모든 쓰기 경로가 잠금 규약을 따라야 해 DB 자체 불변식보다 취약하고 코드가 늘어나 제외한다.
- 충돌 여행의 자동 삭제·기간 축소: 사용자 데이터를 임의 변경하므로 제외한다.

## 6. 열린 항목 (Backend 담당자 확인 필요)

- 2절의 "F002는 `trips`만 생성" 결정과 3절의 일정 관련 필드 생략은 2026-08-28 Backend 구현 과정에서 확정했으며, `docs/design/api-spec.md`에도 F002 현재 계약과 F004 이후 확장 시점을 동기화했다.
