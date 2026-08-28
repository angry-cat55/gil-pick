# Quickstart: 여행 관리 검증

이 문서는 F002 구현이 끝난 뒤 `spec.md`의 완료 조건을 종단간으로 확인하는 절차다. 전체 구현 코드는 여기 포함하지 않는다.

## 사전 준비

- F001(카카오 인증) 기준 발급된 유효한 Access Token 1개(사용자 A), 다른 사용자(사용자 B)의 Access Token 1개
- Backend: `uv run --project api alembic -c api/alembic.ini upgrade head`로 F002 migration까지 적용
- Backend 실행: `uv run --project api uvicorn app.main:app --reload`

## Backend 검증

### 1. 여행 생성과 검증 (FR-001, FR-001a, FR-001b, FR-002, FR-003)

```bash
curl -X POST https://localhost/api/v1/trips \
  -H "Authorization: Bearer <A_ACCESS_TOKEN>" \
  -H "Idempotency-Key: <uuid>" \
  -H "Content-Type: application/json" \
  -d '{"name":"서울 여행","startDate":"2026-09-01","endDate":"2026-09-03"}'
```

- 기대 결과: `201`, `dayCount=3`, `version=1`
- 동일 `Idempotency-Key`로 재요청 → 새 행 생성 없이 동일 결과 재반환
- `endDate < startDate` → `422 INVALID_TRIP_PERIOD`
- 기간 8일(`end - start > 6`) → `422 INVALID_TRIP_PERIOD`
- 이름 공백만("   ") → `422` (trim 후 2자 미만)
- 동일 이름으로 재생성 → `201` 성공(중복 허용)

### 2. 목록·검색·필터 (FR-004~FR-009)

```bash
curl "https://localhost/api/v1/trips?query=서울&status=UPCOMING&limit=20" \
  -H "Authorization: Bearer <A_ACCESS_TOKEN>"
```

- 기대 결과: 사용자 A 소유 여행만, 이름에 "서울" 포함, `UPCOMING` 상태만 반환
- 정렬: `IN_PROGRESS` → `UPCOMING`(시작일 가까운 순) → `COMPLETED`(종료일 최근 순)
- `limit`보다 많은 데이터가 있으면 `meta.pagination.hasNext=true`와 `nextCursor` 확인, `cursor`로 다음 페이지 조회

### 3. 상세 조회와 소유권 (FR-004, FR-017, US3)

```bash
curl https://localhost/api/v1/trips/<tripId> -H "Authorization: Bearer <A_ACCESS_TOKEN>"
curl https://localhost/api/v1/trips/<tripId> -H "Authorization: Bearer <B_ACCESS_TOKEN>"
```

- 사용자 A: `200`
- 사용자 B(비소유자): `403`
- 논리 삭제된 `tripId`로 사용자 A 조회: `404`

### 4. 수정 — 이름·기간·버전 충돌·완료 잠금 (FR-010, FR-010a, FR-011, FR-011a, FR-012, FR-013)

```bash
curl -X PATCH https://localhost/api/v1/trips/<tripId> \
  -H "Authorization: Bearer <A_ACCESS_TOKEN>" -H "Content-Type: application/json" \
  -d '{"name":"서울 여행 수정","version":1}'
```

- 기대 결과: `200`, `version=2`
- 오래된 `version`으로 재요청 → `409 VERSION_CONFLICT`
- 완료(`COMPLETED`) 상태 여행에 `startDate`/`endDate` 포함 요청 → `409 TRIP_LOCKED`
- 완료 상태 여행에 `name`만 요청 → `200`
- 기간 축소로 범위 밖 일정이 있는 여행에 `confirmDeleteOutOfRangeItems` 없이 요청 → `409 CONFIRMATION_REQUIRED`, `details.deletedItemCount` 확인 후 `confirmDeleteOutOfRangeItems: true`로 재요청 → `200`
  - F002 시점에는 `trip_days`/`itinerary_items`가 없어 `deletedItemCount`는 항상 0이다([data-model.md](data-model.md) "범위 밖" 참고). F004 이후 재검증한다.

### 5. 삭제 — 상태 무관 soft delete와 멱등성 (FR-014, FR-015, FR-016)

```bash
curl -X DELETE https://localhost/api/v1/trips/<tripId> -H "Authorization: Bearer <A_ACCESS_TOKEN>"
```

- 기대 결과: `204`
- 동일 요청 재전송: `204` (추가 부작용 없음)
- 삭제 후 목록·상세 조회에서 제외 확인
- 완료 상태 여행 삭제: `204`, 이후 목록·상세 조회에서 제외

## Android 검증

- 여행 생성 화면에서 이름·기간 유효성 오류(길이, 기간 초과, 시작일>종료일)가 즉시 표시되는지 확인
- 여행 목록 화면에서 검색어·상태 필터·무한 스크롤 동작 확인, 다른 계정으로 로그인 시 목록이 섞이지 않는지 확인
- 여행 상세에서 완료 상태 여행의 기간 수정 UI가 비활성화되고 이름 수정은 가능한지 확인
- 기간 축소 시 삭제 예정 안내 다이얼로그가 뜨고, 취소하면 서버에 반영되지 않는지 확인

## 미실행 항목

- 이 문서는 F002 구현 완료 후 실제 실행 결과를 PR에 기록하는 용도다. 지금 시점(plan 단계)에서는 실행하지 않았다.
