# Data Model: 카카오 인증

## Overview

F001은 `auth_login_transactions`, `users`, `device_sessions` 세 entity를 사용한다. Kakao callback 성공만으로 사용자나 session을 만들지 않고, Android가 login ticket을 교환할 때 세 entity의 최종 상태를 한 DB transaction으로 확정한다.

```text
auth_login_transactions --(ticket 소비 시 social subject로 upsert)--> users
users 1 ---------------------------------------------------------- N device_sessions
auth_login_transactions --(device binding)---------------------- 1 device_sessions
```

## 1. AuthLoginTransaction

카카오 인증 시작부터 Android의 login ticket 교환까지의 짧은 수명 상태다.

| Field | Type | Required | Validation / Meaning |
|---|---|---:|---|
| `transaction_id` | UUID | Y | PK, login ticket의 selector |
| `state_hash` | string(64) | Y | 요청별 256-bit `state`의 SHA-256 hex; unique |
| `client_device_id` | string | Y | UUID 형식, transaction 시작 기기 |
| `platform` | string | Y | MVP 고정값 `ANDROID` |
| `status` | string | Y | `PENDING`, `PROCESSING`, `VERIFIED`, `CONSUMED`, `FAILED`, `EXPIRED` |
| `login_ticket_hash` | string(64) | N | callback 성공 후 256-bit ticket secret의 SHA-256 hex; unique |
| `social_subject` | string(255) | N | Kakao user ID의 문자열 표현 |
| `nickname` | string(80) | N | ticket 교환 전까지만 보관하는 snapshot |
| `profile_image_url` | string | N | HTTPS URL 또는 null |
| `expires_at` | timestamptz | Y | 생성 시각 + 10분 |
| `ticket_expires_at` | timestamptz | N | callback 성공 시각 + 120초 |
| `consumed_at` | timestamptz | N | ticket 교환 성공 시각 |
| `failure_code` | string(80) | N | 최종 실패의 내부 안정 code |
| `created_at` | timestamptz | Y | 서버 시각 |
| `updated_at` | timestamptz | Y | 서버 시각 |

### State transitions

```text
PENDING --valid state를 원자적으로 선점--> PROCESSING
PROCESSING --Kakao verified--> VERIFIED
PROCESSING --cancel/provider/validation failure--> FAILED
PENDING --expires_at 경과--> EXPIRED
PROCESSING --expires_at 경과--> EXPIRED
VERIFIED --valid ticket + matching device, atomic exchange--> CONSUMED
VERIFIED --ticket_expires_at 경과--> EXPIRED
```

- `PENDING` callback은 짧은 DB transaction에서 `PROCESSING`으로 조건부 전환한 요청 하나만 외부 호출을 수행한다.
- `VERIFIED` ticket은 조건부 상태 변경으로 한 번만 소비한다.
- `CONSUMED`, `FAILED`, `EXPIRED`는 terminal이다.
- `CONSUMED` 전환 시 `social_subject`, `nickname`, `profile_image_url`, `login_ticket_hash`를 null 처리한다.
- APScheduler cleanup은 terminal/expired row와 임시 profile snapshot을 24시간 안에 삭제한다. Cleanup 실패는 로그인 정확성에 영향을 주지 않으며 다음 실행에서 재시도한다.

### Indexes

- PK `(transaction_id)`
- unique `(state_hash)`
- partial unique `(login_ticket_hash) WHERE login_ticket_hash IS NOT NULL`
- cleanup `(status, expires_at)`

## 2. User

카카오 계정에 연결된 길픽 사용자다.

| Field | Type | Required | Validation / Meaning |
|---|---|---:|---|
| `user_id` | UUID | Y | PK |
| `social_provider` | string(20) | Y | MVP 고정값 `KAKAO` |
| `social_subject` | string(255) | Y | Kakao user ID |
| `nickname` | string(80) | N | 이번 로그인에서 non-null이면 갱신 |
| `profile_image_url` | string | N | 이번 로그인에서 non-null이면 갱신 |
| `replacement_suggestion_enabled` | boolean | Y | 기본 `true`; F012에서 사용 |
| `created_at` | timestamptz | Y | 서버 시각 |
| `updated_at` | timestamptz | Y | 서버 시각 |
| `deleted_at` | timestamptz | N | 계정 탈퇴는 F001 범위 밖 |

### Identity and update rules

- unique `(social_provider, social_subject)`가 동일 Kakao 계정의 중복 생성을 막는다.
- ticket 교환에서 insert 충돌 시 기존 user를 재사용한다.
- Kakao가 제공한 nickname/profile image가 non-null일 때만 기존 값을 갱신한다.
- `deleted_at IS NOT NULL`인 user의 재로그인은 계정 탈퇴 정책 Feature가 정의될 때까지 `KAKAO_AUTH_FAILED`로 거절한다.

## 3. DeviceSession

한 사용자와 한 Android 앱 설치의 장기 로그인 관계다.

| Field | Type | Required | Validation / Meaning |
|---|---|---:|---|
| `session_id` | UUID | Y | PK, Refresh Token selector와 Access Token `sid` |
| `user_id` | UUID | Y | FK → `users.user_id` |
| `client_device_id` | string(255) | Y | UUID 형식 |
| `platform` | string(20) | Y | MVP 고정값 `ANDROID` |
| `refresh_token_hash` | string(64) | Y | 256-bit secret의 SHA-256 hex |
| `refresh_expires_at` | timestamptz | Y | 로그인/갱신 발급 시각 + 30일 |
| `revoked_at` | timestamptz | N | 현재 기기 logout 시각 |
| `fcm_token` | string | N | F011/F012 등 후속 Feature에서 사용 |
| `app_version` | string(40) | N | 관찰 정보 |
| `last_seen_at` | timestamptz | N | 성공한 인증 요청 시각 |
| `created_at` | timestamptz | Y | 서버 시각 |
| `updated_at` | timestamptz | Y | 서버 시각 |

### Identity and lifecycle

- unique `(user_id, client_device_id)`로 사용자·앱 설치별 row 하나만 유지한다.
- 별도 활성 기기 수 상한은 없다.
- 같은 기기에서 재로그인하면 session row를 재활성화하고 새 Refresh hash/만료시각으로 교체한다.
- 앱 데이터 삭제로 새 `deviceId`가 생성되면 새 기기로 취급한다.
- 만료·폐기된 row는 상태 확정 후 30일 동안 장애 확인과 재시도 진단에 사용한 뒤 cleanup job이 삭제한다.

### Derived states

| State | Condition | Allowed transition |
|---|---|---|
| `ACTIVE` | `revoked_at IS NULL AND refresh_expires_at > now()` | refresh rotation, logout |
| `EXPIRED` | `refresh_expires_at <= now()` | Kakao 재로그인으로 같은 row 재활성화 |
| `REVOKED` | `revoked_at IS NOT NULL` | Kakao 재로그인으로 같은 row 재활성화 |

## 4. Credentials outside the database

### Access Token

- HS256 JWT, 1시간 유효
- Claims: `sub`, `sid`, `iss`, `aud`, `iat`, `exp`, `jti`, `type=access`
- 개인정보, Kakao identifier, nickname/profile을 포함하지 않는다.
- 서명 secret은 AWS secret storage에서 주입하고 repository에 저장하지 않는다.

### Refresh Token

- Wire format: `{session_id}.{base64url-256-bit-secret}`
- 원문은 응답 생성 시 memory와 Android 암호화 저장소에만 존재한다.
- Backend는 `refresh_token_hash`만 비교한다.

### Login ticket

- Wire format: `{transaction_id}.{base64url-256-bit-secret}`
- verified App Link URI fragment로 한 번 전달하고 120초 안에 교환한다.
- URI fragment는 HTTP request와 access log에 전송되지 않으며 Android는 수신 즉시 제거한 뒤 암호화 session 저장소 밖에 보존하지 않는다.

## 5. Atomic operations

### Login ticket exchange

한 DB transaction에서 다음을 수행한다.

1. `transaction_id`로 row를 `SELECT ... FOR UPDATE`하여 잠그고 `VERIFIED`, 미소비, 미만료, ticket hash·device 일치를 재검증한다. 조건이 맞지 않으면 변경 없이 rollback한다.
2. `(KAKAO, social_subject)`로 user를 upsert하고 non-null profile을 갱신한다.
3. `(user_id, client_device_id)`로 device session을 upsert하고 새 Refresh hash/30일 만료를 저장한다.
4. 잠근 transaction을 `CONSUMED`로 변경하고 snapshot/ticket hash를 제거한다.
5. commit 후 Access/Refresh Token을 응답한다.

어느 단계든 실패하면 전체 rollback한다.

### Refresh rotation

새 Refresh secret/hash를 memory에서 만든 뒤 한 조건부 `UPDATE ... RETURNING`을 수행한다.

```text
WHERE session_id = token.selector
  AND refresh_token_hash = hash(token.secret)
  AND client_device_id = request.deviceId
  AND revoked_at IS NULL
  AND refresh_expires_at > server_now
SET refresh_token_hash = new_hash,
    refresh_expires_at = server_now + 30 days,
    last_seen_at = server_now,
    updated_at = server_now
```

영향 row가 1개면 성공, 0개면 만료·무효·회전·기기 불일치를 구분해 실패한다. 동일 Token 경쟁 요청은 DB row lock과 조건 재검사로 정확히 하나만 성공한다.

### Logout

- hash·device가 일치하는 active row는 `revoked_at`을 기록한다.
- 같은 hash·device로 이미 revoked인 row는 멱등 성공한다.
- 다른 device는 `DEVICE_MISMATCH`, 다른 hash는 `INVALID_REFRESH_TOKEN`이다.
- 다른 기기 session row는 읽거나 변경하지 않는다.

## 6. Retention and access

- `auth_login_transactions`: terminal/expired 전환 후 24시간 안에 삭제
- `device_sessions`: active 동안 보관, expired/revoked 전환 후 30일 뒤 삭제
- `users`: 계정 활성 기간 동안 인증 목적으로 보관; 계정 탈퇴·즉시 삭제는 후속 Feature에서 처리
- 인증 application log: 30일 보관 후 삭제, Backend 운영 계정만 접근
- 공모전 test 계정·data: 심사·시연 종료 후 삭제
