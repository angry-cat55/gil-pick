# Data Model: 여행 관리

## Overview

F002는 `trips` 하나의 entity를 사용한다. `docs/design/er-schema.md` 5.1절과 동일하며 F002 범위에서 새로 정의하지 않는다.

```text
users 1 ------------------------------- N trips
```

## 1. Trip

사용자가 만드는 여행 단위. 소유자, 이름, 기간, 논리 삭제 여부와 동시 수정 감지를 위한 버전을 가진다.

| Field | Type | Required | Validation / Meaning |
|---|---|---:|---|
| `trip_id` | UUID | Y | PK |
| `user_id` | UUID | Y | FK → `users.user_id` |
| `name` | string(30) | Y | trim 후 2~30자, 동일 사용자 내 중복 허용 |
| `start_date` | date | Y | 여행 시작일 (KST 기준) |
| `end_date` | date | Y | 여행 종료일, `start_date` 이상, `end_date - start_date`는 0~6 |
| `timezone` | string(40) | Y | 기본 `Asia/Seoul` |
| `version` | integer | Y | 기본 1, 수정마다 +1. `PATCH` 요청의 낙관적 동시성 제어에 사용 |
| `created_at` | timestamptz | Y | 생성 시각 |
| `updated_at` | timestamptz | Y | 수정 시각 |
| `deleted_at` | timestamptz | N | 논리 삭제 시각, null이면 활성 |

### 파생 값 (저장하지 않음)

- **상태**: KST 현재 날짜와 `start_date`/`end_date`를 비교해 매 요청 시점에 계산한다.
  - `start_date` 이전 → `UPCOMING`(예정)
  - `start_date`~`end_date` → `IN_PROGRESS`(여행 중)
  - `end_date` 이후 → `COMPLETED`(완료)
- **`dayCount`**: `end_date - start_date + 1`.

### 검증 규칙 (spec.md 대응)

| 규칙 | 근거 |
|---|---|
| `trim(name)` 길이 2~30자 | FR-001, FR-001b |
| `end_date >= start_date` | FR-001a |
| `end_date - start_date <= 6` | FR-001, FR-011 |
| 이름 중복 허용, unique 제약 없음 | FR-002 |
| `status == COMPLETED`이면 `start_date`/`end_date` 수정 거부, 논리 삭제는 허용 | FR-010a, FR-014 |
| `status == COMPLETED`가 아니어도 `name` 수정은 항상 허용 | FR-010 |
| `PATCH` 요청은 조회 시점 `version`을 함께 받아 저장된 값과 다르면 거부 | FR-011a |
| 삭제는 `deleted_at` 설정(soft delete), 이미 삭제된 행에 대한 재요청은 동일 결과(204) | FR-014, FR-016 |

### 상태 전이 (파생 상태 기준)

```text
UPCOMING --start_date 도달--> IN_PROGRESS --end_date 경과--> COMPLETED
```

- 위 전이는 저장된 컬럼 변경이 아니라 조회 시점 계산 결과다. 별도 background job이 필요 없다.
- `COMPLETED` 도달 후에는 기간 수정만 잠기며(FR-010a), 이름 수정·상세 조회·논리 삭제는 허용된다(FR-014).

### Indexes

- PK `(trip_id)`
- `(user_id, deleted_at)` — 목록 조회, 소유권 검증
- `(user_id, lower(name))` where `deleted_at IS NULL` — `docs/design/er-schema.md` 5절 인덱스와 동일, 이름 검색 보조

### 생성 요청 멱등성 (FR-003)

`POST /api/v1/trips`는 `Idempotency-Key` 헤더(`docs/design/api-spec.md` TRIP-002)를 필수로 받는다. 동일 사용자·동일 키의 재요청은 새 행을 만들지 않고 최초 생성 결과를 그대로 반환한다.

## 범위 밖

- `trip_days`, `itinerary_items`는 F002에서 생성하지 않는다 ([research.md](research.md) 2절). F002의 `PATCH` 기간 축소 로직은 이 두 테이블이 아직 없으므로 항상 "삭제될 일정 0건"으로 동작하며, F004가 `trip_days`/`itinerary_items`를 도입하면 실제 개수를 계산하도록 확장한다.
