# Implementation Plan: 여행 관리

**Branch**: `002-trip-management` (Spec Kit 논리 식별자; 현재 Git branch `docs/hs-f002-trip-spec`, 구현 branch 미생성) | **Date**: 2026-08-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-trip-management/spec.md`

## Summary

인증된 사용자가 여행을 생성·조회·수정·삭제하고 목록을 검색·필터링한다. Backend는 `trips` 테이블 하나로 여행 기본정보를 관리하며, 상태(예정/여행 중/완료)는 저장하지 않고 KST 현재 날짜와 기간으로 매 요청 시점에 계산한다. 여행 수정은 `version` 기반 낙관적 동시성 제어를 사용하고, 완료된 여행은 이름만 수정 가능하며 기간 수정과 삭제는 잠근다. 기간 축소로 범위 밖 일정이 생기는 경우 사용자 확인 후에만 적용하지만, F002 시점에는 일정(`trip_days`/`itinerary_items`)이 아직 없어 실제 삭제 개수는 항상 0이며 F004(일정 구성)에서 확장한다. Android는 F001이 만들어 둔 빈 여행 목록 shell(`AuthenticatedHomeScreen`)을 실제 여행 목록·생성·상세·수정·삭제 화면으로 교체한다.

## Technical Context

**Language/Version**: Backend Python 3.13; Android Kotlin 2.4.10

**Primary Dependencies**: Backend는 F001과 동일한 FastAPI 0.141.1, SQLAlchemy 2.0.52 async, Alembic, asyncpg, PyJWT(기존 인증 dependency 재사용). Android는 F001과 동일한 `compileSdk 37`·`targetSdk 36`, Jetpack Compose BOM 2026.08.00, Lifecycle/ViewModel, Retrofit·OkHttp

**Storage**: PostgreSQL 18.6 (`trips` 테이블 신규 추가). `trip_days`/`itinerary_items`는 F004에서 추가한다([research.md](research.md) 2절)

**Testing**: Backend pytest, pytest-asyncio, HTTPX ASGI client, PostgreSQL integration/contract tests; Android JUnit, kotlinx-coroutines-test, MockWebServer, Compose UI tests

**Target Platform**: Docker 기반 AWS Linux Backend; Android 8.0 이상(`minSdk 26`, `targetSdk 36`, `compileSdk 37`)

**Project Type**: Android mobile app + REST API web service (F001과 동일 repository)

**Performance Goals**: 여행 생성 결과 확인 3초 이내(SC-001), 여행 100건 목록 최초 화면 2초 이내(SC-002), 검색·필터 결과 반영 1초 이내(SC-003)

**Constraints**: 여행명 2~30자(trim 후), 기간 최대 7일, `startDate <= endDate`; 완료 상태 여행은 기간 수정·삭제 불가(이름 수정만 허용); 수정은 `version` 낙관적 동시성 제어; 목록은 cursor 페이지네이션과 공통 envelope 사용; 모든 보호 API는 소유권 검증(FR-017)

**Scale/Scope**: MVP 단일 Backend 배포 단위; 여행 endpoint 5개(TRIP-001~005); 사용자당 여행 수 별도 상한 없음(성능 목표는 100건 기준)

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가 완료.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 여행 조회·목록은 위치·외부 데이터와 무관해 항상 제공한다. 기간 축소는 삭제 전 항상 사용자 확인을 거치고(FR-012, FR-013), 확인 전 어떤 데이터도 바뀌지 않는다. | PASS |
| II. 계약 우선 SDD와 문서 동기화 | `docs/design/api-spec.md`(TRIP-001~005)·`docs/design/er-schema.md`(`trips`)를 기준 계약으로 사용했다. clarify에서 나온 완료 상태 잠금(`409 TRIP_LOCKED`) 규칙은 이번 작업 범위에서 두 문서에 반영했다. | PASS |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | 여행 생성은 `Idempotency-Key`로 멱등 처리하고(FR-003), 수정은 `version` 낙관적 동시성으로 충돌을 감지하며(FR-011a), 삭제는 반복 요청에도 동일한 결과를 반환한다(FR-016). | PASS |
| IV. 외부 의존성 실패 격리 | F002는 외부 API를 호출하지 않는다(N/A). | N/A |
| V. 보안·소유권·최소 데이터 | 모든 endpoint는 Access Token의 사용자 ID로 소유권을 검증하고(FR-004, FR-017), 다른 사용자의 여행 정보를 어떤 응답에도 포함하지 않는다(SC-005). 여행 데이터에는 위치·민감정보가 없다. | PASS |
| 교차 계약 review | 여행 API·DB는 Backend·Android 모두에 영향을 주므로, Issue 분리와 구현 PR 전에 Backend(jh 또는 ts)와 Frontend Android(jy 또는 hs) 담당자가 `research.md`의 "열린 항목"(F002의 `trips` 단독 생성, `days[]` 생략)을 함께 확인해야 한다. | PASS WITH REVIEW CONDITION |

위반 예외는 없다. 교차 계약 review 조건은 설계 위반이 아니라 constitution에 따른 필수 품질 gate다.

### Post-Design Re-check

- `data-model.md`는 `trips` 단일 entity와 검증 규칙·인덱스만 정의하며 F004 범위(`trip_days`, `itinerary_items`)를 침범하지 않는다.
- `contracts/trips.openapi.yaml`은 공통 envelope, `Idempotency-Key`, `version` 기반 `409 VERSION_CONFLICT`, 완료 상태 잠금 `409 TRIP_LOCKED`를 명시한다.
- `quickstart.md`는 정상 흐름뿐 아니라 소유권 위반, 버전 충돌, 완료 상태 잠금, 삭제 멱등성, 삭제 확인 흐름을 검증한다.
- Phase 1 이후에도 모든 constitution gate는 PASS이며 새로운 예외는 없다.

## Project Structure

### Documentation (this feature)

```text
specs/002-trip-management/
├── plan.md              # 이 문서
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── trips.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md              # $speckit-tasks에서 생성
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── api/v1/trips.py          # TRIP-001~005 endpoint
│   ├── models/trip.py           # Trip ORM 모델
│   ├── schemas/trip.py          # 요청·응답 DTO
│   └── services/trip.py         # 생성·조회·수정·삭제, version 검증, idempotency
├── migrations/versions/
│   └── 002_create_trip_table.py
└── tests/
    ├── contract/test_trip_contract.py
    ├── integration/test_trip_flow.py
    └── unit/test_trip_service.py

android/
├── app/src/main/java/com/gilpick/trip/
│   ├── TripApi.kt               # Retrofit 인터페이스, DTO
│   ├── TripRepository.kt        # API 호출, 상태 매핑
│   ├── TripListViewModel.kt     # 목록·검색·필터·무한 스크롤 상태
│   ├── TripDetailViewModel.kt   # 상세·수정·삭제 상태
│   ├── TripListScreen.kt
│   ├── TripDetailScreen.kt
│   └── TripFormScreen.kt        # 생성·수정 공용 입력 화면
├── app/src/main/java/com/gilpick/MainActivity.kt   # AuthenticatedHomeScreen → TripListScreen 진입점 교체
├── app/src/test/java/com/gilpick/trip/
└── app/src/androidTest/java/com/gilpick/trip/
```

**Structure Decision**: F001과 동일하게 `api/`, `android/` 두 디렉터리를 유지한다. Backend는 `trips.py`를 얇은 transport 계층으로, `services/trip.py`가 트랜잭션 경계(생성 멱등성, 버전 검증, 완료 상태 잠금)를 소유하는 F001 `services/auth.py`와 동일한 패턴을 따른다. Android는 `com.gilpick.auth`와 분리된 `com.gilpick.trip` 패키지를 새로 만들고, `MainActivity`의 인증 성공 이후 진입점을 F001이 임시로 둔 `AuthenticatedHomeScreen`에서 `TripListScreen`으로 교체한다. `trip_days`/`itinerary_items`, 지도 표시, 이동수단은 F004~F005 범위이므로 이번 구조에 포함하지 않는다.

## Phase 0 Research Decisions

결정 근거와 대안은 [research.md](research.md)에 기록했다. 기술 스택 관련 `NEEDS CLARIFICATION`은 없다. 다만 "F002가 `trips`만 생성하고 `days[]`를 생략한다"는 결정은 Backend 담당자 확인이 필요한 항목으로 표시해 두었다(research.md 5절).

## Phase 1 Design Outputs

- 데이터와 검증 규칙: [data-model.md](data-model.md)
- REST 계약: [contracts/trips.openapi.yaml](contracts/trips.openapi.yaml)
- 종단간 검증 절차: [quickstart.md](quickstart.md)

## Complexity Tracking

Constitution 위반이나 정당화가 필요한 추가 복잡성은 없다. `trip_days`/`itinerary_items`를 F002에서 미리 만들지 않기로 한 결정에 따라, 기간 축소 시 "삭제될 일정 수" 안내(FR-012)는 F002 구현 시점에는 실제 일정 데이터 없이 항상 0건으로 동작한다. 이 잔여 범위는 F004(일정 구성) plan에서 `trip_days`/`itinerary_items`를 도입하며 함께 채운다.
