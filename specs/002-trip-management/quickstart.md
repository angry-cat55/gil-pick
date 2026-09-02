# Quickstart: 여행 관리 검증

이 문서는 F002 구현이 끝난 뒤 `spec.md`의 완료 조건을 종단간으로 확인하는 절차다. 전체 구현 코드는 여기 포함하지 않는다.

## 사전 준비

- F001(카카오 인증) 기준 발급된 유효한 Access Token 1개(사용자 A), 다른 사용자(사용자 B)의 Access Token 1개
- Backend: `uv run --project api alembic -c api/alembic.ini upgrade head`로 F002 migration까지 적용
- Backend 실행: `uv run --project api uvicorn app.main:app --reload`
- 로컬 URL: `BASE_URL=http://127.0.0.1:8000`

## Backend 검증

### 1. 여행 생성과 검증 (FR-001, FR-001a, FR-001b, FR-002, FR-003)

```bash
curl -X POST "$BASE_URL/api/v1/trips" \
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
curl "$BASE_URL/api/v1/trips?query=서울&status=UPCOMING&limit=20" \
  -H "Authorization: Bearer <A_ACCESS_TOKEN>"
```

- 기대 결과: 사용자 A 소유 여행만, 이름에 "서울" 포함, `UPCOMING` 상태만 반환
- 정렬: `IN_PROGRESS` → `UPCOMING`(시작일 가까운 순) → `COMPLETED`(종료일 최근 순)
- `limit`보다 많은 데이터가 있으면 `meta.pagination.hasNext=true`와 `nextCursor` 확인, `cursor`로 다음 페이지 조회

### 3. 상세 조회와 소유권 (FR-004, FR-017, US3)

```bash
curl "$BASE_URL/api/v1/trips/<tripId>" -H "Authorization: Bearer <A_ACCESS_TOKEN>"
curl "$BASE_URL/api/v1/trips/<tripId>" -H "Authorization: Bearer <B_ACCESS_TOKEN>"
```

- 사용자 A: `200`
- 사용자 B(비소유자): `403`
- 논리 삭제된 `tripId`로 사용자 A 조회: `404`

### 4. 수정 — 이름·기간·버전 충돌·완료 잠금 (FR-010, FR-010a, FR-011, FR-011a, FR-012, FR-013)

```bash
curl -X PATCH "$BASE_URL/api/v1/trips/<tripId>" \
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
curl -X DELETE "$BASE_URL/api/v1/trips/<tripId>" -H "Authorization: Bearer <A_ACCESS_TOKEN>"
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

### F001 실제 계정 refresh 승계 검증

F001 T046은 구현 당시 Access Token 만료를 유도할 보호 API 호출 경로가 없어 실제 Kakao 계정 session의 refresh 회전을 실행하지 못했다. F002의 `TripRepository`가 `AuthRepository.withAccessToken()`을 통해 보호 API를 호출하므로 T048에서 다음 흐름을 함께 검증한다.

1. 실제 Kakao test 계정으로 로그인하고 현재 기기의 `device_sessions` 식별자·Refresh Token hash·다른 기기 session 상태를 원문 Token 없이 기록한다.
2. Backend를 종료하고 `JWT_SIGNING_SECRET`을 다른 안전한 test 값으로 변경해 재시작하여 기존 Access Token의 검증 실패를 유도한 뒤 여행 생성·조회 등 보호 API를 호출한다. Refresh Token hash는 이 설정의 영향을 받지 않는다.
3. 최초 보호 API가 `401`, `POST /api/v1/auth/token/refresh`가 `200`, 원 요청의 최대 1회 replay가 성공하는 순서를 request ID와 함께 확인한다.
4. refresh 전후 같은 기기 session의 Refresh Token hash가 회전하고 session이 활성 상태로 유지되며, 다른 기기 session에는 영향이 없는지 확인한다.
5. 앱 재시작 후에도 새 Token pair로 보호 API를 호출할 수 있는지 확인한다.

PR에는 실행 환경·절차·HTTP 상태·DB 상태 전이만 기록하며 Access/Refresh Token, 전체 JWT, Kakao code와 login ticket 원문을 남기지 않는다.

## Backend 실행 결과 (2026-08-28)

- 환경: 로컬 PostgreSQL, `uvicorn` HTTP(`127.0.0.1:8765`), 사용자 A·B용 로컬 검증 JWT
- migration: `uv run alembic -c alembic.ini upgrade head` 성공
- 시나리오 1: 생성 `201`, 같은 멱등 키에 같은 `tripId`, 역순·8일·공백 이름 `422`, 같은 이름의 다른 여행 `201`
- 시나리오 2: 사용자 A/B 목록 격리, `IN_PROGRESS → UPCOMING → COMPLETED` 정렬, 검색·상태 필터, `limit=1`의 `hasNext=true`·`nextCursor`와 다음 페이지 중복 없음 확인
- 시나리오 3: 소유자 상세 `200`, 비소유자 상세 `403 FORBIDDEN`
- 시나리오 4: 이름 수정 `200`·`version=2`, 오래된 version `409 VERSION_CONFLICT`, 완료 여행 기간 수정 `409 TRIP_LOCKED`·이름 수정 `200`, 기간 축소 확인 전 `409 CONFIRMATION_REQUIRED`·확인 후 `200`
- 시나리오 5: 삭제와 반복 삭제 `204`, 삭제 후 목록·검색·상세 제외, 완료 여행 삭제 `204`
- 종단간 검증: quickstart의 HTTP 요청과 동일한 payload·header를 표준 라이브러리 client로 실행하고 생성·검증·검색 필터·cursor 발급·소유권·version 충돌·삭제 응답을 assertion으로 확인
- 보완 회귀 검증: PostgreSQL integration test로 사용자 목록 격리·전체 상태 정렬·cursor 다음 페이지·기간 축소 확인·삭제 후 목록/검색 제외를 확인하고, contract test로 완료 여행 기간 잠금과 이름 수정 계약을 확인
- 실행 중 Uvicorn access log formatter와 민감정보 filter의 호환 오류를 발견했으며 API 결과에는 영향이 없었다. 수정은 Issue #123으로 분리했다.

### 미실행 항목

- Backend 검증에서는 실제 카카오 로그인으로 발급한 운영 Access Token을 외부 카카오 인증정보가 없는 로컬 환경이라 사용하지 않았다. JWT 검증 규칙과 사용자 분리는 F001 테스트와 로컬 검증 JWT로 확인했으며, 실제 계정 session의 refresh는 T048에서 위 Android 절에 따라 검증한다.
- Android 확인 항목은 T048(Issue #108) 범위이므로 실행하지 않았다.
