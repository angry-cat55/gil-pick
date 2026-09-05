# 길픽 프로젝트 API 명세서

- 버전: `1.2.0`
- 기본 경로: `/api/v1`
- 기준 시간대: `Asia/Seoul`
- ID: UUID 문자열
- 인증: `Authorization: Bearer {accessToken}`

## 1. 공통 규칙

### 1.1 성공 응답

```json
{
  "success": true,
  "data": {},
  "meta": {
    "requestId": "uuid"
  }
}
```

목록 응답은 `data.items`와 cursor 기반 페이지네이션을 사용한다.

```json
{
  "success": true,
  "data": {
    "items": []
  },
  "meta": {
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    },
    "requestId": "uuid"
  }
}
```

- `204 No Content` 응답에는 Body를 포함하지 않는다.
- 목록이 비어 있으면 `items: []`를 반환한다.
- nullable 값은 JSON `null`을 사용한다.

### 1.2 오류 응답

```json
{
  "success": false,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청을 처리할 수 없습니다.",
    "details": {},
    "retryable": false
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

Backend가 생성하는 오류는 위 형식을 따른다. 인증 endpoint 자체 rate limit은 공모전 MVP 필수 범위에서 제외하고, 배포 환경에 기존 보호 계층이 있을 때 별도 hardening으로 적용한다.

공통 오류 코드 원칙:

| HTTP | 대표 상황 |
|---|---|
| `400` | Query/Path/Body 형식 오류 |
| `401` | 미인증, Access Token 만료·무효 |
| `403` | 다른 사용자의 리소스 접근, 기기 불일치 |
| `404` | 리소스 없음 |
| `409` | 버전 충돌, 이미 처리된 상태, 사용자 확인 필요 |
| `422` | 도메인 규칙 위반 |
| `429` | 외부 API 또는 서비스 호출 한도 초과 |
| `502` | 외부 API 호출 실패 |
| `504` | 외부 API 또는 전체 요청 timeout |

### 1.3 인증과 사용자 식별

- 보호 API는 `Authorization: Bearer {accessToken}`을 사용한다.
- 서버는 Access Token의 signature, issuer, audience, `type=access`, 만료를 handler 실행 전에 검증하고 subject로 사용자를 식별한다.
- 일반 API의 Query/Body에 `userId`를 반복해서 받지 않는다.
- 다른 사용자의 여행·일정·감지·알림 데이터에 접근하는 요청은 `403 Forbidden`으로 거절한다.
- Access Token 유효시간은 1시간이다. Refresh Token은 최초 로그인과 각 회전 발급 시점부터 30일 유효하며 MVP에는 별도 절대 만료 상한을 두지 않는다.
- 다중 기기 로그인을 허용하며 기기별 Refresh Token을 별도로 관리한다.
- 로그아웃은 현재 기기의 Refresh Token만 폐기한다.
- 로그아웃 전에 발급된 Access Token의 서버 즉시 폐기는 MVP 범위 밖이며 해당 Token은 최대 1시간 뒤 만료된다.
- 인증 application log는 30일 보관 후 삭제하고 Backend 운영 계정만 접근한다. Token, code, `state`, ticket과 profile 원문은 기록하지 않는다.

### 1.4 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Authorization` | 보호 API | `Bearer {accessToken}` |
| `Content-Type` | Body 있음 | `application/json` |
| `Idempotency-Key` | 중복 처리 위험 API | 생성·여행 시작·승인 등의 중복 요청 방지용 UUID |
| `X-Request-Id` | 선택 | 클라이언트 요청 추적 ID |

### 1.5 날짜·시간·좌표·페이징

- 날짜: `YYYY-MM-DD`
- 시각: ISO 8601 + offset, 예: `2026-08-22T13:30:00+09:00`
- 기준 시간대: `Asia/Seoul`
- 좌표계: WGS84, 필드명 `latitude`, `longitude`
- 거리: m 단위, 필드명에 `Meters` 사용
- 소요시간: 분 단위, 필드명에 `Minutes` 사용
- ID: UUID 문자열
- 목록 API: `cursor`, `limit`
- `limit` 기본 20, 최대 100

### 1.6 동시성·멱등성

- 생성·승인·여행 시작처럼 중복 처리 위험이 있는 요청은 `Idempotency-Key`를 사용한다.
- 일정 수정과 진행 전환은 `version`으로 충돌을 검증한다.
- 버전이 최신이 아니면 `409 VERSION_CONFLICT`를 반환한다.

### 1.7 외부 API 실패 공통 정책

- 외부 API 단일 호출 timeout 기본값: 5초
- 일시적인 timeout/network/5xx 오류: 최대 1회 재시도
- quota/rate-limit/auth/잘못된 요청은 즉시 재시도하지 않는다.
- 카카오 로그인은 일회성 인가 코드의 소비 여부를 확인할 수 없으므로 Token 교환을 자동 재시도하지 않는다. Token 교환 후 사용자 정보 조회만 timeout/network/5xx 오류에 최대 1회 재시도한다.
- Google Places는 MVP에서 서버 캐시를 사용하지 않는다.
- Google Places 실패 시 Google 기반 변수만 제외하고 추천을 계속한다.
- 기상청·서울시 데이터 실패 시 해당 변수만 제외하고 나머지 가중치를 100%로 재분배한다.
- TMAP/ODsay처럼 경로 계산에 필요한 API가 최종 실패하면 경로 계산 실패로 처리한다.
- 경로 계산 전체 요청의 최대 대기시간은 10초다.

## 2. API 구현 현황

| ID | 분류 | 기능명 | 프론트 | 백엔드 | 메서드 | URL |
|---|---|---|---|---|---|---|
| AUTH-001 | 인증 | 카카오 로그인 transaction 생성 | [ ] | [ ] | POST | `/api/v1/auth/kakao/transactions` |
| AUTH-002 | 인증 | 카카오 로그인 callback | [ ] | [ ] | GET | `/api/v1/auth/kakao/callback` |
| AUTH-003 | 인증 | 카카오 login ticket 교환 | [ ] | [ ] | POST | `/api/v1/auth/kakao/exchange` |
| AUTH-004 | 인증 | 액세스 토큰 재발급 | [ ] | [ ] | POST | `/api/v1/auth/token/refresh` |
| AUTH-005 | 인증 | 로그아웃 | [ ] | [ ] | POST | `/api/v1/auth/logout` |
| USER-001 | 사용자 | 내 정보 조회 | [ ] | [ ] | GET | `/api/v1/users/me` |
| TRIP-001 | 여행 | 여행 목록 조회 | [ ] | [ ] | GET | `/api/v1/trips` |
| TRIP-002 | 여행 | 여행 생성 | [ ] | [ ] | POST | `/api/v1/trips` |
| TRIP-003 | 여행 | 여행 상세 조회 | [ ] | [ ] | GET | `/api/v1/trips/{tripId}` |
| TRIP-004 | 여행 | 여행 수정 | [ ] | [ ] | PATCH | `/api/v1/trips/{tripId}` |
| TRIP-005 | 여행 | 여행 삭제 | [ ] | [ ] | DELETE | `/api/v1/trips/{tripId}` |
| ITIN-001 | 일정 | 날짜별 일정 조회 | [ ] | [ ] | GET | `/api/v1/trips/{tripId}/days/{date}/itinerary` |
| ITIN-002 | 일정 | 날짜별 일정 저장 | [ ] | [ ] | PUT | `/api/v1/trips/{tripId}/days/{date}/itinerary` |
| PLACE-001 | 장소 | 장소 검색 | [ ] | [ ] | GET | `/api/v1/places/search` |
| PLACE-002 | 장소 | 장소 상세 조회 | [ ] | [ ] | GET | `/api/v1/places/{placeId}` |
| ROUTE-001 | 경로 | 날짜별 경로 조회 | [ ] | [ ] | GET | `/api/v1/trips/{tripId}/days/{date}/route` |
| ROUTE-002 | 경로 | 남은 경로 재계산 | [ ] | [ ] | POST | `/api/v1/trips/{tripId}/days/{date}/route/recalculate` |
| PROG-001 | 여행 진행 | 당일 진행 현황 조회 | [ ] | [ ] | GET | `/api/v1/trips/{tripId}/days/{date}/progress` |
| PROG-002 | 여행 진행 | 오늘 여행 시작 | [ ] | [ ] | POST | `/api/v1/trips/{tripId}/days/{date}/progress/start` |
| PROG-003 | 여행 진행 | 위치 이벤트 등록 | [ ] | [ ] | POST | `/api/v1/trips/{tripId}/days/{date}/progress/events` |
| PROG-004 | 여행 진행 | 자동 감지 확인 응답 | [ ] | [ ] | POST | `/api/v1/progress/transitions/{transitionId}/decisions` |
| PROG-005 | 여행 진행 | 자동 확정 되돌리기 | [ ] | [ ] | POST | `/api/v1/progress/transitions/{transitionId}/undo` |
| PROG-006 | 여행 진행 | 수동 진행 상태 처리 | [ ] | [ ] | PATCH | `/api/v1/itinerary-items/{itemId}/status` |
| DETECT-001 | 변수 감지 | 감지 목록 조회 | [ ] | [ ] | GET | `/api/v1/trips/{tripId}/detections` |
| DETECT-002 | 변수 감지 | 감지 상세 조회 | [ ] | [ ] | GET | `/api/v1/detections/{detectionId}` |
| DETECT-003 | 변수 감지 | 감지 읽음 처리 | [ ] | [ ] | PATCH | `/api/v1/detections/{detectionId}/read` |
| ALT-001 | 대체 장소 | 추천 후보 조회 | [ ] | [ ] | GET | `/api/v1/detections/{detectionId}/alternatives` |
| ALT-002 | 대체 장소 | 대체 장소 직접 검색 | [ ] | [ ] | GET | `/api/v1/detections/{detectionId}/alternatives/search` |
| REPL-001 | 일정 변경 | 대체 경로 미리보기 생성 | [ ] | [ ] | POST | `/api/v1/detections/{detectionId}/route-previews` |
| REPL-002 | 일정 변경 | 대체 장소 승인 | [ ] | [ ] | POST | `/api/v1/route-previews/{previewId}/approve` |
| REPL-003 | 일정 변경 | 대체 장소 거절 | [ ] | [ ] | POST | `/api/v1/route-previews/{previewId}/reject` |
| REPL-004 | 일정 변경 | 대체 장소 변경 되돌리기 | [ ] | [ ] | POST | `/api/v1/replacements/{replacementId}/undo` |
| NOTI-001 | 알림 | 알림 목록 조회 | [ ] | [ ] | GET | `/api/v1/notifications` |
| NOTI-002 | 알림 | 알림 읽음 처리 | [ ] | [ ] | PATCH | `/api/v1/notifications/{notificationId}/read` |
| DEV-001 | 기기 | FCM 토큰 등록·갱신 | [ ] | [ ] | PUT | `/api/v1/devices/fcm-token` |
| DEV-002 | 기기 | FCM 토큰 해제 | [ ] | [ ] | DELETE | `/api/v1/devices/{deviceId}/fcm-token` |
| PREF-001 | 사용자 설정 | 설정 조회 | [ ] | [ ] | GET | `/api/v1/users/me/preferences` |
| PREF-002 | 사용자 설정 | 설정 수정 | [ ] | [ ] | PATCH | `/api/v1/users/me/preferences` |

## 3. 인증·사용자

### AUTH-001 카카오 로그인 transaction 생성

`POST /api/v1/auth/kakao/transactions`

Request Body:

```json
{
  "deviceId": "uuid",
  "platform": "ANDROID"
}
```

Response `201`:

```json
{
  "success": true,
  "data": {
    "transactionId": "uuid",
    "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?...",
    "expiresIn": 600
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

정책:
- 서버는 요청마다 256-bit 난수 `state`를 만들고 해시만 10분간 저장한다.
- `authorizationUrl`에는 서버에 등록된 고정 HTTPS callback과 요청별 `state`를 사용한다. 클라이언트가 `redirectUri`를 선택하거나 전달하지 않는다.
- transaction은 요청한 `deviceId`와 `ANDROID` platform에 결합한다.
- 앱은 `authorizationUrl`을 Custom Tab으로 열고 카카오 인가 코드나 Token을 직접 처리하지 않는다.

주요 오류: `400 INVALID_REQUEST`, `500 INTERNAL_ERROR`

### AUTH-002 카카오 로그인 callback

`GET /api/v1/auth/kakao/callback`

Query:

| 필드 | 필수 | 설명 |
|---|---|---|
| `code` | 성공 시 | 카카오 일회성 인가 코드 |
| `state` | 필수 | 로그인 transaction 검증값 |
| `error` | 실패 시 | 카카오 인증 실패 코드 |
| `error_description` | 실패 시 | 카카오 인증 실패 설명 |

Response: Android verified App Link로 `302 Found`

```http
Location: https://{configured-app-link-host}/auth/kakao/complete#loginTicket={one-time-ticket}
Cache-Control: no-store
Referrer-Policy: no-referrer
```

정책:
- 서버는 `state`가 존재하고 만료되지 않았으며 아직 처리되지 않은 transaction과 일치하는지 먼저 검증한다.
- 서버는 유효한 `PENDING` transaction을 짧은 DB transaction에서 `PROCESSING`으로 먼저 전환한 뒤 외부 호출을 시작하여 같은 callback의 중복 처리를 막는다.
- 서버는 인가 코드를 카카오 Token으로 교환하고 사용자 정보를 조회한다. 인가 코드 교환은 자동 재시도하지 않고, 사용자 정보 조회의 timeout/network/5xx만 한 번 재시도한다.
- 카카오 Token은 사용자 정보 조회 동안 memory에서만 사용하고 저장하거나 log에 남기지 않는다.
- 성공 시 `transactionId.secret` 형식의 256-bit 일회용 login ticket을 발급하고 해시만 저장한다. ticket은 120초 동안 유효하다.
- callback 성공 전에는 길픽 사용자나 기기 세션을 생성하지 않는다. 사용자 확인 결과의 최소 snapshot만 ticket 교환 시점까지 transaction에 보관한다.
- login ticket은 HTTP request나 중간 access log로 전달되지 않는 URI fragment에 넣으며 Android가 수신 즉시 제거한다. Callback request의 `code`와 `state`는 access log에서 제거한다.
- 취소·실패 시 민감정보가 없는 오류 식별자와 함께 App Link로 돌아가 앱이 재시도 또는 새 인증을 안내한다.
- 신뢰 가능한 transaction을 찾은 뒤 발생한 사용자 취소·provider 오류·timeout은 HTTP 오류 응답이 아니라 아래 App Link `error` code를 담은 `302`로 전달한다. `state`가 없거나 잘못되어 안전한 redirect 대상을 결정할 수 없을 때만 민감정보 없는 일반 `400`을 반환한다.

App Link 오류 code: `ACCESS_DENIED`, `INVALID_AUTHORIZATION_CODE`, `KAKAO_AUTH_FAILED`, `KAKAO_RATE_LIMITED`, `KAKAO_API_FAILED`, `KAKAO_API_TIMEOUT`, `LOGIN_TRANSACTION_EXPIRED`

### AUTH-003 카카오 login ticket 교환

`POST /api/v1/auth/kakao/exchange`

Request Body:

```json
{
  "loginTicket": "transaction-id.secret",
  "deviceId": "uuid"
}
```

Response `200` 또는 신규 사용자 `201`:

```json
{
  "success": true,
  "data": {
    "accessToken": "jwt",
    "expiresIn": 3600,
    "refreshToken": "session-id.secret",
    "refreshExpiresIn": 2592000,
    "user": {
      "userId": "uuid",
      "nickname": "길픽사용자",
      "profileImageUrl": "https://...",
      "provider": "KAKAO"
    }
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

정책:
- login ticket은 transaction에 결합된 동일 `deviceId`에서 유효기간 내 한 번만 교환할 수 있다.
- ticket 교환 transaction 시작 시 대상 row를 `SELECT ... FOR UPDATE`로 잠그고 조건을 재검증한다. ticket 검증, 사용자 upsert, 기기 세션 upsert, ticket 소비는 한 transaction으로 처리하여 동시 요청 중 하나만 성공한다.
- 기존 사용자의 nickname과 profile image는 카카오가 이번 로그인에서 제공한 non-null 값만 최신 값으로 갱신한다.
- 사용자당 활성 기기 수에는 MVP 별도 상한을 두지 않는다.
- 동일 계정의 다른 기기 로그인 상태는 변경하지 않는다.

주요 오류:
- `401 INVALID_LOGIN_TICKET`
- `401 LOGIN_TICKET_EXPIRED`
- `403 DEVICE_MISMATCH`

### AUTH-004 액세스 토큰 재발급

`POST /api/v1/auth/token/refresh`

Request Body:

```json
{
  "refreshToken": "refresh-token",
  "deviceId": "uuid"
}
```

Response `200`:

```json
{
  "success": true,
  "data": {
    "accessToken": "new-jwt",
    "expiresIn": 3600,
    "refreshToken": "new-refresh-token",
    "refreshExpiresIn": 2592000
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류:
- `401 TOKEN_EXPIRED`
- `401 INVALID_REFRESH_TOKEN`
- `403 DEVICE_MISMATCH`

정책:
- Refresh Token은 `sessionId.secret` 형식의 256-bit opaque Token이며 서버는 해시만 저장한다.
- 같은 Refresh Token을 사용한 동시 갱신은 조건부 갱신으로 정확히 한 요청만 성공한다.
- 성공 시 이전 Refresh Token은 즉시 무효화하고 새 Refresh Token 만료시각을 발급 시점부터 30일로 갱신한다.
- 갱신 성공 후 응답이 유실되면 앱은 기존 로그인 상태를 보존하고 재시도한다. 재시도에서 이전 Token이 무효로 확인되면 새 카카오 로그인을 요구한다. MVP에서는 Token 원문이나 암호화된 성공 응답을 저장하는 replay 기능을 추가하지 않는다.

### AUTH-005 로그아웃

`POST /api/v1/auth/logout`

Request Body:

```json
{
  "refreshToken": "refresh-token",
  "deviceId": "uuid"
}
```

Response: `204 No Content`

정책:
- 현재 기기의 Refresh Token만 폐기한다.
- 같은 `deviceId`와 Refresh Token으로 이미 폐기된 세션의 로그아웃을 반복하면 멱등하게 `204`를 반환한다.
- 앱은 서버 응답과 관계없이 로컬 로그인 상태를 즉시 종료하고, 로그아웃마다 별도 `revocationOperationId`를 가진 암호화 envelope를 대기 큐에 보존한다. WorkManager는 operation ID별 작업으로 통신 실패를 재시도한다.
- pending 폐기 요청의 `INVALID_REFRESH_TOKEN`·`TOKEN_EXPIRED`는 폐기 완료와 동등하게 종료하고, `DEVICE_MISMATCH`는 다른 session을 변경하지 않은 terminal failure로 기록한다.
- Refresh 성공 응답 유실 직후에는 앱이 보유한 이전 Token으로 서버 폐기가 불가능할 수 있다. MVP는 서버 자격의 최대 30일 만료 또는 같은 기기 재로그인 시 교체를 잔여 위험으로 허용한다.
- 모든 기기에서 로그아웃 기능은 MVP에서 제외한다.

주요 오류: `401 INVALID_REFRESH_TOKEN`, `401 TOKEN_EXPIRED`, `403 DEVICE_MISMATCH`

### USER-001 내 정보 조회

`GET /api/v1/users/me`

Response `200`:

```json
{
  "success": true,
  "data": {
    "userId": "uuid",
    "nickname": "길픽사용자",
    "profileImageUrl": "https://...",
    "provider": "KAKAO",
    "createdAt": "2026-08-22T13:30:00+09:00"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `401`

## 4. 여행

여행 상태는 KST 현재 날짜를 기준으로 산정한다.
- 시작일 전: `UPCOMING`
- 시작일~종료일: `IN_PROGRESS`
- 종료일 후: `COMPLETED`

### TRIP-001 여행 목록 조회

`GET /api/v1/trips`

Query:

| 이름 | 필수 | 설명 |
|---|---|---|
| `query` | 아니오 | 여행명 검색 |
| `status` | 아니오 | `UPCOMING`, `IN_PROGRESS`, `COMPLETED` |
| `cursor` | 아니오 | 다음 페이지 cursor |
| `limit` | 아니오 | 기본 20, 최대 100 |

정렬:
1. 여행 중
2. 시작일이 가까운 예정 여행
3. 종료일이 최근인 완료 여행

Response `200`:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "tripId": "uuid",
        "name": "서울 여행",
        "startDate": "2026-08-22",
        "endDate": "2026-08-24",
        "status": "IN_PROGRESS",
        "dayCount": 3,
        "version": 1,
        "createdAt": "2026-08-22T13:30:00+09:00"
      }
    ]
  },
  "meta": {
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    },
    "requestId": "uuid"
  }
}
```

주요 오류: `400`, `401`

### TRIP-002 여행 생성

`POST /api/v1/trips`

Header: `Idempotency-Key`

Request Body:

```json
{
  "name": "서울 여행",
  "startDate": "2026-08-22",
  "endDate": "2026-08-24"
}
```

검증:
- 여행명 2~30자
- 같은 이름 허용
- 최대 여행 기간 7일
- `startDate <= endDate`

Response `201`:

```json
{
  "success": true,
  "data": {
    "tripId": "uuid",
    "name": "서울 여행",
    "startDate": "2026-08-22",
    "endDate": "2026-08-24",
    "status": "IN_PROGRESS",
    "dayCount": 3,
    "createdAt": "2026-08-22T13:30:00+09:00",
    "version": 1
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `400`, `401`, `422 INVALID_TRIP_PERIOD`

### TRIP-003 여행 상세 조회

`GET /api/v1/trips/{tripId}`

Response `200`:

```json
{
  "success": true,
  "data": {
    "tripId": "uuid",
    "name": "서울 여행",
    "startDate": "2026-08-22",
    "endDate": "2026-08-24",
    "status": "IN_PROGRESS",
    "dayCount": 3,
    "version": 3,
    "createdAt": "2026-08-22T13:30:00+09:00"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `401`, `403`, `404 TRIP_NOT_FOUND`

### TRIP-004 여행 수정

`PATCH /api/v1/trips/{tripId}`

Request Body:

```json
{
  "name": "서울 여행 수정",
  "startDate": "2026-08-22",
  "endDate": "2026-08-23",
  "version": 3,
  "confirmDeleteOutOfRangeItems": true
}
```

- 변경하지 않는 필드는 생략 가능하다.
- 여행 기간 축소로 범위 밖 일정이 삭제되는 경우 사용자 확인이 필요하다.
- 확인 화면에는 삭제될 일정 개수를 표시한다.
- 여행 상태가 `COMPLETED`이면 `name`만 수정할 수 있다. `startDate`/`endDate` 변경은 `409 TRIP_LOCKED`로 거부한다.

Response `200`:

```json
{
  "success": true,
  "data": {
    "tripId": "uuid",
    "name": "서울 여행 수정",
    "startDate": "2026-08-22",
    "endDate": "2026-08-23",
    "status": "IN_PROGRESS",
    "dayCount": 2,
    "version": 4
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

F002에서는 `trip_days`·`itinerary_items`가 아직 없으므로 수정 성공 응답도 다른 여행 API와 같은 `TripEnvelope`를 사용하며 `deletedDayCount`·`deletedItemCount`를 포함하지 않는다. 실제 일정 삭제 개수는 F004에서 일정 테이블을 도입할 때 계약 version을 갱신해 추가한다.

삭제 확인이 필요한 경우 `409 CONFIRMATION_REQUIRED` 예시:

```json
{
  "success": false,
  "error": {
    "code": "CONFIRMATION_REQUIRED",
    "message": "여행 기간을 줄이면 범위 밖 일정이 삭제됩니다.",
    "details": {
      "deletedItemCount": 0
    },
    "retryable": false
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

F002의 `deletedItemCount`는 항상 `0`이며 F004 이후 실제 범위 밖 일정 개수로 대체한다.

주요 오류: `400`, `401 INVALID_ACCESS_TOKEN`, `403`, `404`, `409 VERSION_CONFLICT`, `409 CONFIRMATION_REQUIRED`, `409 TRIP_LOCKED`, `422`

### TRIP-005 여행 삭제

`DELETE /api/v1/trips/{tripId}`

Response: `204 No Content`

정책:
- soft delete
- 삭제된 여행 및 연관 데이터는 일반 조회에서 비노출
- 자동 물리 삭제·보존기간 정리 작업은 MVP에서 제외
- 사용자 복구 기능은 MVP에서 제외
- 여행 상태와 무관하게 소유한 여행을 논리 삭제할 수 있다.
- 이미 논리 삭제된 여행에 대한 반복 요청은 추가 부작용 없이 `204`를 반환한다(멱등).

주요 오류: `400`, `401`, `403`, `404`

## 5. 일정·장소·경로

### 5.1 일정 공통 DTO

`items[]`:

| 필드 | 형식 | 규칙 |
|---|---|---|
| `itemId` | UUID 또는 null | 신규 항목은 null |
| `placeId` | 문자열 | 내부 장소 식별자 또는 정규화한 외부 장소 참조 |
| `sequence` | 정수 | 날짜 안에서 중복 불가 |
| `plannedStayMinutes` | 정수 | 30~360분, 30분 단위 |
| `transportModeToNext` | enum/null | `WALK`, `CAR`, `TRANSIT`; 마지막은 null |
| `status` | enum | `PLANNED`, `EN_ROUTE`, `ARRIVED`, `COMPLETED`, `SKIPPED` |

카테고리 추천 체류시간:
- 자연 120분
- 문화·역사 90분
- 음식 60분
- 카페 60분
- 쇼핑 90분
- 기타 60분

### ITIN-001 날짜별 일정 조회

`GET /api/v1/trips/{tripId}/days/{date}/itinerary`

Response `200`:

```json
{
  "success": true,
  "data": {
    "date": "2026-08-22",
    "version": 5,
    "routeStatus": "READY",
    "items": [
      {
        "itemId": "uuid",
        "placeId": "place-uuid",
        "placeName": "경복궁",
        "sequence": 1,
        "plannedStayMinutes": 90,
        "transportModeToNext": "WALK",
        "status": "PLANNED"
      }
    ]
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `401`, `403`, `404`

### ITIN-002 날짜별 일정 저장

`PUT /api/v1/trips/{tripId}/days/{date}/itinerary`

Header: `Idempotency-Key`

Request Body:

```json
{
  "version": 5,
  "items": [
    {
      "itemId": "uuid",
      "placeId": "place-uuid",
      "sequence": 1,
      "plannedStayMinutes": 90,
      "transportModeToNext": "WALK",
      "status": "PLANNED"
    },
    {
      "itemId": null,
      "placeId": "place-uuid-2",
      "sequence": 2,
      "plannedStayMinutes": 60,
      "transportModeToNext": null,
      "status": "PLANNED"
    }
  ]
}
```

처리 규칙:
- 장소·순서·체류시간·이동수단 수정 가능
- 처리된 장소는 상태·체류시간·순서 수정 가능
- 처리된 장소를 다른 장소로 교체할 경우 상태 유지
- 경로 계산 실패 시에도 일정 저장은 유지
- 경로 계산 실패 시 `routeStatus: FAILED`
- 자동 재시도 1회 후 실패하면 앱에서 `경로 다시 계산`을 제공

Response `200` 또는 신규 일자 `201`:

```json
{
  "success": true,
  "data": {
    "date": "2026-08-22",
    "version": 6,
    "routeStatus": "READY",
    "items": [],
    "route": {
      "routeId": "uuid",
      "totalDurationMinutes": 45,
      "totalDistanceMeters": 5200
    }
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

경로 실패 시:

```json
{
  "success": true,
  "data": {
    "date": "2026-08-22",
    "version": 6,
    "routeStatus": "FAILED",
    "items": [],
    "route": null
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `400`, `403`, `404`, `409 VERSION_CONFLICT`, `422`

### PLACE-001 장소 검색

`GET /api/v1/places/search`

Query:
- `query`: 선택. trim 후 2글자 이상
- `category`: 선택. `NATURE | HISTORY_CULTURE | FOOD | CAFE | SHOPPING | OTHER`
- `query`와 `category`는 단독 또는 조합 가능하며 둘 다 없으면 `400 INVALID_REQUEST`
- 선택 `areaCode`, `cursor`
- `limit`: 선택, 기본·최대 20

Response `200`:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "placeId": "tourapi:126508",
        "source": "TOUR_API",
        "sourcePlaceId": "126508",
        "name": "경복궁",
        "category": "HISTORY_CULTURE",
        "tourApiCategory": {
          "large": "A02",
          "middle": "A0201",
          "small": "A02010100"
        },
        "address": "서울특별시 종로구 사직로 161",
        "latitude": 37.579617,
        "longitude": 126.977041,
        "imageUrl": "https://...",
        "recommendedStayMinutes": 90,
        "rating": 4.6,
        "userRatingCount": 1203,
        "businessStatus": "OPERATIONAL",
        "regularOpeningHours": null,
        "currentOpeningHours": null,
        "googleAttributions": ["Google Maps"]
      }
    ]
  },
  "meta": {
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    },
    "requestId": "uuid"
  }
}
```

주요 오류: `400 INVALID_REQUEST | INVALID_CURSOR`, `401 INVALID_ACCESS_TOKEN`, `429 TOUR_API_RATE_LIMITED`, `502 TOUR_API_FAILED`, `504 TOUR_API_TIMEOUT`

### PLACE-002 장소 상세 조회

`GET /api/v1/places/{placeId}`

Response `200`:

```json
{
  "success": true,
  "data": {
    "placeId": "tourapi:126508",
    "source": "TOUR_API",
    "sourcePlaceId": "126508",
    "name": "경복궁",
    "category": "HISTORY_CULTURE",
    "tourApiCategory": {
      "large": "A02",
      "middle": "A0201",
      "small": "A02010100"
    },
    "address": "서울특별시 종로구 사직로 161",
    "latitude": 37.579617,
    "longitude": 126.977041,
    "imageUrl": "https://...",
    "description": "...",
    "phone": "02-...",
    "recommendedStayMinutes": 90,
    "operatingGuide": "매주 화요일 휴무, 관람 시간은 계절별로 다름",
    "rating": 4.6,
    "userRatingCount": 1203,
    "businessStatus": "OPERATIONAL",
    "regularOpeningHours": null,
    "currentOpeningHours": null,
    "googleAttributions": ["Google Maps"]
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

운영 안내 정책:
- TourAPI가 제공하는 콘텐츠 유형별 운영 안내를 nullable 문자열로 정규화
- 제공되지 않은 운영 안내는 `null`
- `openNow` 또는 정확한 종료 시각을 추론하지 않음
- 관광지·문화시설·자연·축제·숙박은 TourAPI만 사용하고, 음식·카페·쇼핑은 페이지의 TourAPI 결과가 `limit` 미만일 때만 Google Places로 부족분 보완
- 확정 매칭은 TourAPI ID·기본·상세정보를 유지하고 Google 평점·평점 수·영업정보만 병합하며 모호한 Google 후보는 제외
- Google 전용 결과는 `google:{placeId}`를 사용하고 Google 사진·리뷰는 반환하지 않음
- Google 실패 시 TourAPI 결과를 유지하고 Google 필드만 제외하며, TourAPI 실패를 Google 결과로 대체하지 않음
- 장소별 provider 배지는 화면에 표시하지 않지만 Google 데이터 영역의 필수 attribution은 준수
- 검색 결과와 상세 응답은 DB나 server cache에 저장하지 않음

주요 오류:

- 공통: `400 INVALID_REQUEST`, `401 INVALID_ACCESS_TOKEN`, `404 PLACE_NOT_FOUND`
- `tourapi:` 기준 provider 실패: `429 TOUR_API_RATE_LIMITED`, `502 TOUR_API_FAILED`, `504 TOUR_API_TIMEOUT`
- `google:` 기준 provider 실패: `429 GOOGLE_PLACES_RATE_LIMITED`, `502 GOOGLE_PLACES_FAILED`, `504 GOOGLE_PLACES_TIMEOUT`
- `tourapi:` 상세의 선택적 Google 보강 실패는 응답 실패로 전파하지 않고 TourAPI 정보만 반환

### ROUTE-001 날짜별 경로 조회

`GET /api/v1/trips/{tripId}/days/{date}/route`

Response `200`:

```json
{
  "success": true,
  "data": {
    "routeId": "uuid",
    "version": 3,
    "status": "READY",
    "totalDurationMinutes": 85,
    "totalDistanceMeters": 11200,
    "segments": [
      {
        "fromItemId": "uuid",
        "toItemId": "uuid",
        "transportMode": "WALK",
        "durationMinutes": 18,
        "distanceMeters": 1300,
        "provider": "TMAP"
      }
    ],
    "providerAttribution": ["TMAP"]
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `403`, `404`, `502`

### ROUTE-002 남은 경로 재계산

`POST /api/v1/trips/{tripId}/days/{date}/route/recalculate`

Header: `Idempotency-Key`

Request Body:

```json
{
  "fromItemId": "uuid",
  "currentLocation": {
    "latitude": 37.57,
    "longitude": 126.98
  },
  "reason": "MANUAL_RECALCULATION",
  "itineraryVersion": 6
}
```

- `currentLocation`은 위치 권한·수집 상태에 따라 생략 가능하다.
- 위치가 없으면 예정 장소 간 경로와 계획 체류시간을 기준으로 계산한다.

Response `200`:

```json
{
  "success": true,
  "data": {
    "routeId": "uuid",
    "version": 4,
    "status": "READY",
    "totalDurationMinutes": 62,
    "totalDistanceMeters": 8800,
    "segments": []
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

정책:
- 동기 처리
- 외부 API 5초 timeout 후 1회 재시도
- 전체 10초 초과 시 실패

주요 오류: `400`, `403`, `404`, `409 VERSION_CONFLICT`, `422`, `502`, `504 ROUTE_TIMEOUT`

## 6. 여행 진행

### 6.1 공통 정책

- 기본 상태 흐름: `PLANNED → EN_ROUTE → ARRIVED → COMPLETED`
- 모든 상태에서 `SKIPPED` 전환 가능
- 완료·건너뛰기 취소 가능
- 수동 상태 수정 가능
- 특정 장소를 수동으로 `ARRIVED` 처리하면 그 뒤 일정은 `PLANNED`로 초기화하고 ETA·변수 감지를 재계산한다.
- 자동 위치 이벤트는 정확도 100m 이하이고 발생 후 2분 이내일 때 사용한다.
- 기준값은 실제 테스트 후 조정 가능하다.
- 정확도·신선도 기준 미충족 시 자동 도착/출발 감지를 보류하고 수동 진행을 허용한다.
- 도착 후보: 300m 이내 연속 5분 DWELL
- 도착 후보 알림 후 5분 무응답이면 자동 `ARRIVED`
- `아직이에요` 선택 후 재알림은 10분 뒤, 동일 장소 총 알림 횟수는 최초 포함 최대 2회
- 출발 후보: 400m EXIT
- EXIT 후 5분 무응답·무재진입 시 자동 출발 확정
- `아직 머무는 중` 선택 시 해당 장소 자동 출발 감지 중단
- 자동 도착·출발 확정 후 되돌리기는 5분
- 마지막 장소 도착 확정 시 당일 일정을 완료하고 ETA·변수 감지·지오펜스를 종료한다.

### PROG-001 당일 진행 현황 조회

`GET /api/v1/trips/{tripId}/days/{date}/progress`

Response `200`:

```json
{
  "success": true,
  "data": {
    "dayStatus": "IN_PROGRESS",
    "actualStartedAt": "2026-08-22T09:10:00+09:00",
    "currentItemId": "uuid",
    "items": [
      {
        "itemId": "uuid",
        "sequence": 1,
        "status": "ARRIVED",
        "eta": "2026-08-22T10:30:00+09:00"
      }
    ],
    "activeTransition": null,
    "detectionActive": true
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `403`, `404`

### PROG-002 오늘 여행 시작

`POST /api/v1/trips/{tripId}/days/{date}/progress/start`

Header: `Idempotency-Key`

Request Body:

```json
{
  "version": 6
}
```

Response `200`:

```json
{
  "success": true,
  "data": {
    "dayStatus": "IN_PROGRESS",
    "actualStartedAt": "2026-08-22T09:10:00+09:00",
    "version": 7
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

정책:
- 서버 수신 시각을 `actualStartedAt`으로 저장
- 진행 재개 시 기존 `actualStartedAt`을 변경하지 않음

주요 오류: `403`, `404`, `409 VERSION_CONFLICT`, `422`

### PROG-003 위치 이벤트 등록

`POST /api/v1/trips/{tripId}/days/{date}/progress/events`

Request Body:

```json
{
  "eventId": "uuid",
  "eventType": "DWELL",
  "itemId": "uuid",
  "occurredAt": "2026-08-22T10:10:00+09:00",
  "location": {
    "latitude": 37.5796,
    "longitude": 126.9770,
    "accuracyMeters": 35
  }
}
```

`eventType` 예시: `DWELL`, `EXIT`, `REENTER`

Response `200`:

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "transition": {
      "transitionId": "uuid",
      "type": "ARRIVAL_CANDIDATE",
      "status": "WAITING_USER_DECISION",
      "expiresAt": "2026-08-22T10:15:00+09:00"
    }
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

기준 미충족 이벤트는 오류로 만들기보다 자동 감지 판단에서 제외할 수 있다.

주요 오류: `400`, `403`, `404`, `409 DUPLICATE_EVENT`

### PROG-004 자동 감지 확인 응답

`POST /api/v1/progress/transitions/{transitionId}/decisions`

Request Body 예시:

```json
{
  "decision": "CONFIRM"
}
```

허용 decision은 transition 종류에 따라 `CONFIRM`, `NOT_YET`, `STILL_STAYING` 등을 사용한다.

Response `200`:

```json
{
  "success": true,
  "data": {
    "transitionId": "uuid",
    "resultStatus": "ARRIVED",
    "itemId": "uuid",
    "undoExpiresAt": "2026-08-22T10:20:00+09:00"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `404`, `409 TRANSITION_EXPIRED`, `409 ALREADY_DECIDED`

### PROG-005 자동 확정 되돌리기

`POST /api/v1/progress/transitions/{transitionId}/undo`

Response `200`:

```json
{
  "success": true,
  "data": {
    "transitionId": "uuid",
    "restoredStatus": "EN_ROUTE",
    "itemId": "uuid"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

- 자동 도착·출발 확정 후 5분 이내 허용
- 5분 이후에는 일반 수동 상태 수정 기능으로 보정

주요 오류: `404`, `409 UNDO_EXPIRED`, `409 ALREADY_UNDONE`

### PROG-006 수동 진행 상태 처리

`PATCH /api/v1/itinerary-items/{itemId}/status`

Request Body:

```json
{
  "status": "ARRIVED",
  "version": 7
}
```

Response `200`:

```json
{
  "success": true,
  "data": {
    "itemId": "uuid",
    "status": "ARRIVED",
    "version": 8,
    "recalculated": true
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

정책:
- 완료·건너뛰기 취소 허용
- 수동 상태 교정 허용
- 해당 장소를 `ARRIVED`로 변경하면 이후 일정 상태를 `PLANNED`로 초기화하고 ETA·변수 감지를 재계산

주요 오류: `403`, `404`, `409 VERSION_CONFLICT`, `422 INVALID_STATUS_TRANSITION`

## 7. 변수 감지·대체 장소 추천

### 7.1 변수 감지 정책

- 서버 감지 주기: 10분
- 각 주기마다 대상 장소의 전체 변수 점수를 계산한다.
- 임계값 초과 시 장소 변경 제안 알림을 1회 생성한다.
- 사용자가 해당 제안을 결정하기 전에는 동일 대상 장소에 추가 제안을 보내지 않는다.
- 해당 장소 일정이 완료·통과되면 pending 제안 상태는 종료한다.
- MVP는 단일 서버를 전제로 하며 다중 서버 분산락은 제외한다.

혼잡:
- ETA 시점 예보가 없으면 혼잡 변수 제외
- 지원지역 매핑 실패 시 혼잡 변수 제외
- 500m 이내 서울시 혼잡 지원 핫스팟이 있으면 혼잡 영향 포함
- 500m 안에서는 거리 감쇠 없음
- 민감도 높음: 쇼핑, 음식, 카페
- 민감도 중간: 자연, 문화·역사, 기타 및 미분류

날씨:
- 강수확률 70% 이상 또는 예상 강수량 1mm/h 이상이면 경고
- 강수성 상태만 경고
- 실외와 실내/실외 미상 장소에는 날씨 페널티 적용
- 실내 장소는 원칙적으로 미적용
- 예보 누락 시 날씨 변수 제외

운영시간:
- ETA가 폐점 시각 이상이면 방문 불가
- ETA가 폐점 30분 전 이내면 폐점 임박 경고
- 임시휴업 정보가 있으면 방문 불가
- 운영시간 미상은 운영시간 변수 제외

### DETECT-001 감지 목록 조회

`GET /api/v1/trips/{tripId}/detections`

Query: `cursor`, `limit`

Response `200`:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "detectionId": "uuid",
        "itemId": "uuid",
        "placeName": "경복궁",
        "status": "PENDING_DECISION",
        "totalRiskScore": 78,
        "createdAt": "2026-08-22T11:00:00+09:00",
        "read": false
      }
    ]
  },
  "meta": {
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    },
    "requestId": "uuid"
  }
}
```

주요 오류: `403`, `404`

### DETECT-002 감지 상세 조회

`GET /api/v1/detections/{detectionId}`

Response `200`:

```json
{
  "success": true,
  "data": {
    "detectionId": "uuid",
    "tripId": "uuid",
    "itemId": "uuid",
    "eta": "2026-08-22T13:00:00+09:00",
    "totalRiskScore": 78,
    "variables": {
      "congestion": {
        "available": true,
        "level": "CROWDED"
      },
      "weather": {
        "available": true,
        "precipitationProbability": 80,
        "precipitationMmPerHour": 0.5
      },
      "operatingHours": {
        "available": true,
        "closesAt": "2026-08-22T13:20:00+09:00",
        "closingSoon": true
      }
    },
    "status": "PENDING_DECISION",
    "read": false
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `403`, `404`

### DETECT-003 감지 읽음 처리

`PATCH /api/v1/detections/{detectionId}/read`

Request Body: 없음

Response `200`:

```json
{
  "success": true,
  "data": {
    "detectionId": "uuid",
    "read": true
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `403`, `404`

### 7.2 추천 점수 정책

기본 가중치:
- 거리 35%
- 평점 30%
- 혼잡 20%
- 날씨 15%

평점은 Bayesian 보정 점수를 사용한다.

`adjusted = (v / (v + m)) * R + (m / (v + m)) * C`

- `R`: 장소 평점
- `v`: 평점 수
- `C`: 현재 후보들의 평균 평점
- `m`: 현재 후보들의 평점 수 중앙값
- 후보가 5개 미만이면 `m = 20`
- 일부 변수가 없으면 해당 변수를 제외하고 나머지 가중치를 비례 재분배
- 화면 표시 점수는 정수 반올림
- 내부 정렬은 반올림 전 소수점 점수 사용
- 반환 후보 최대 10개
- 동점: 거리 짧음 → Bayesian 평점 높음 → 리뷰 수 많음

### ALT-001 추천 후보 조회

`GET /api/v1/detections/{detectionId}/alternatives`

Response `200`:

```json
{
  "success": true,
  "data": {
    "searchRadiusMeters": 500,
    "items": [
      {
        "placeId": "tourapi:123",
        "name": "대체 장소",
        "category": "문화·역사",
        "distanceMeters": 320,
        "rating": 4.5,
        "userRatingCount": 820,
        "adjustedRating": 4.42,
        "score": 86.73,
        "displayScore": 87,
        "scoreBreakdown": {
          "distance": 0.92,
          "rating": 0.88,
          "congestion": 0.76,
          "weather": 1.0
        },
        "operatingStatus": "OPEN"
      }
    ]
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

탐색 반경:
1. 500m
2. 후보 없음 → 1km
3. 후보 없음 → 최대 2km
4. 2km에도 없으면 대체 장소 없음

기준점은 기존 장소 좌표다.

주요 오류: `403`, `404`, `502`, `504`

### ALT-002 대체 장소 직접 검색

`GET /api/v1/detections/{detectionId}/alternatives/search`

Query: `query`, 선택 `cursor`, `limit`

Response 구조는 PLACE-001과 동일한 장소 기본 DTO를 사용하되 해당 detection의 기존 장소를 기준으로 거리와 방문 가능 여부를 추가한다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "placeId": "tourapi:456",
        "name": "직접 검색 장소",
        "category": "카페",
        "distanceMeters": 680,
        "visitable": true
      }
    ]
  },
  "meta": {
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    },
    "requestId": "uuid"
  }
}
```

주요 오류: `400`, `403`, `404`, `502`, `504`

## 8. 대체 일정 변경

### REPL-001 대체 경로 미리보기 생성

`POST /api/v1/detections/{detectionId}/route-previews`

Header: `Idempotency-Key`

Request Body:

```json
{
  "alternativePlaceId": "tourapi:123",
  "itineraryVersion": 8
}
```

Response `200`:

```json
{
  "success": true,
  "data": {
    "previewId": "uuid",
    "detectionId": "uuid",
    "originalItemId": "uuid",
    "alternativePlace": {
      "placeId": "tourapi:123",
      "name": "대체 장소"
    },
    "route": {
      "totalDurationMinutes": 58,
      "totalDistanceMeters": 7400,
      "segments": []
    },
    "itineraryVersion": 8,
    "expiresAt": "2026-08-22T12:10:00+09:00"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

- 후보 선택만으로 일정을 변경하지 않는다.
- 미리보기 생성 후 사용자 승인 전까지 실제 일정과 경로는 유지한다.

주요 오류: `403`, `404`, `409 VERSION_CONFLICT`, `422`, `502`, `504`

### REPL-002 대체 장소 승인

`POST /api/v1/route-previews/{previewId}/approve`

Header: `Idempotency-Key`

Request Body: 없음

Response `200`:

```json
{
  "success": true,
  "data": {
    "replacementId": "uuid",
    "tripId": "uuid",
    "date": "2026-08-22",
    "replacedItemId": "uuid",
    "newPlaceId": "tourapi:123",
    "itineraryVersion": 9,
    "routeStatus": "READY",
    "undoExpiresAt": "2026-08-22T12:00:30+09:00"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

정책:
- 일정·경로·변경 이력을 하나의 승인 작업으로 저장
- 승인 후 30초까지 되돌리기 허용
- 30초 경계 포함
- 후속 일정 변경 발생 시 기존 되돌리기 비활성화

주요 오류: `403`, `404`, `409 PREVIEW_EXPIRED`, `409 VERSION_CONFLICT`, `409 ALREADY_APPROVED`, `502`

### REPL-003 대체 장소 거절

`POST /api/v1/route-previews/{previewId}/reject`

Request Body: 없음

Response: `204 No Content`

- 미리보기를 폐기하고 기존 일정과 경로는 변경하지 않는다.

주요 오류: `403`, `404`, `409 ALREADY_APPROVED`

### REPL-004 대체 장소 변경 되돌리기

`POST /api/v1/replacements/{replacementId}/undo`

Request Body: 없음

Response `200`:

```json
{
  "success": true,
  "data": {
    "replacementId": "uuid",
    "restored": true,
    "itineraryVersion": 10,
    "routeStatus": "READY"
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류:
- `404`
- `409 UNDO_EXPIRED`
- `409 FOLLOW_UP_CHANGE_EXISTS`
- `409 ALREADY_UNDONE`

## 9. 알림·기기·사용자 설정

### NOTI-001 알림 목록 조회

`GET /api/v1/notifications`

Query: `cursor`, `limit`, 선택 `read`

Response `200`:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "notificationId": "uuid",
        "type": "PLACE_CHANGE_SUGGESTION",
        "tripId": "uuid",
        "detectionId": "uuid",
        "title": "다음 장소 변경을 추천해요",
        "body": "현재 상황을 반영한 대체 장소를 확인해보세요.",
        "read": false,
        "createdAt": "2026-08-22T11:00:00+09:00"
      }
    ]
  },
  "meta": {
    "pagination": {
      "nextCursor": null,
      "hasNext": false
    },
    "requestId": "uuid"
  }
}
```

주요 오류: `401`

### NOTI-002 알림 읽음 처리

`PATCH /api/v1/notifications/{notificationId}/read`

Request Body: 없음

Response `200`:

```json
{
  "success": true,
  "data": {
    "notificationId": "uuid",
    "read": true
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `403`, `404`

### DEV-001 FCM 토큰 등록·갱신

`PUT /api/v1/devices/fcm-token`

Request Body:

```json
{
  "deviceId": "uuid",
  "fcmToken": "fcm-token",
  "platform": "ANDROID"
}
```

Response `200`:

```json
{
  "success": true,
  "data": {
    "deviceId": "uuid",
    "registered": true
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `400`, `401`, `403`

### DEV-002 FCM 토큰 해제

`DELETE /api/v1/devices/{deviceId}/fcm-token`

Response: `204 No Content`

주요 오류: `401`, `403`, `404`

### PREF-001 설정 조회

`GET /api/v1/users/me/preferences`

MVP에서는 장소 변경 제안 알림 전체 ON/OFF만 앱 설정으로 제공한다. 혼잡·날씨·운영시간 임계값 직접 조절 및 설정 초기화는 MVP에서 제외한다.

Response `200`:

```json
{
  "success": true,
  "data": {
    "placeChangeSuggestionNotificationEnabled": true
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `401`

### PREF-002 설정 수정

`PATCH /api/v1/users/me/preferences`

Request Body:

```json
{
  "placeChangeSuggestionNotificationEnabled": false
}
```

Response `200`:

```json
{
  "success": true,
  "data": {
    "placeChangeSuggestionNotificationEnabled": false
  },
  "meta": {
    "requestId": "uuid"
  }
}
```

주요 오류: `400`, `401`

설정 화면에는 API 감지 설정과 별개로 이용약관, 개인정보처리방침, 로그아웃 진입점을 제공한다.

## 10. 위치 권한·수집 실패 fallback

### 정밀 위치 권한 거부

- 현재 사용자 위치 표시 없음
- 위치 기반 자동 도착·출발 감지 비활성화
- 경로는 표시
- ETA는 선택 이동수단의 예정 장소 간 경로 API 이동시간 + 계획 체류시간 기준
- 실시간 현재 위치 기반 ETA 보정 없음
- 수동 도착·출발 허용

### 백그라운드 위치 권한 거부

- 앱이 비활성/백그라운드 상태일 때 자동 감지 기능 비활성화
- 수동 도착·출발 허용

### 진행 중 위치 수집 실패

위 fallback 정책과 동일하게 처리한다.

## 11. 구현 시 문서 변경 원칙

- 본 문서는 프론트엔드와 백엔드의 API 계약 기준이다.
- 구현 중 더 적절한 데이터 구조가 발견되더라도 API 계약을 코드에서 임의로 변경하지 않는다.
- Request/Response 계약 변경이 필요하면 관련 feature의 `spec.md`, `plan.md`와 본 API 명세의 관련 내용을 함께 갱신한다.
- DB 구조 변경이 필요하면 ER Schema와 feature plan을 함께 갱신한다.
- 함수명, 내부 클래스 구조, repository/service 내부 구현처럼 외부 계약에 영향이 없는 변경은 본 문서 갱신 대상이 아니다.
