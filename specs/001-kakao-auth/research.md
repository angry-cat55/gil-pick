# Phase 0 Research: 카카오 인증

## 1. 카카오 로그인 흐름

**Decision**: Android는 Backend가 발급한 Kakao authorization URL을 Custom Tab으로 연다. 카카오는 Backend의 allowlist된 HTTPS callback으로 인가 코드를 전달한다. Backend는 `state` 검증, code 교환, 사용자 정보 조회 후 120초 유효 일회용 login ticket을 verified App Link의 URI fragment로 앱에 전달한다. 앱은 ticket을 Backend에서 한 번 교환하여 길픽 Token을 받는다.

**Rationale**: Kakao REST 흐름은 인가 코드를 service server의 redirect URI로 전달하고 server가 Token을 교환하도록 정의한다. Kakao Android SDK helper는 code를 내부에서 소비하고 앱에 `OAuthToken`을 반환하므로 기존 “앱이 code만 Backend로 전달” 계약과 결합할 수 없다. Backend callback 방식은 카카오 secret과 Token을 앱에서 제거하면서 기존 보안 의도를 유지한다. Android는 제3자 로그인에 WebView 대신 Custom Tab을 사용하고 verified App Link로 callback 가로채기 위험을 줄인다.

**Alternatives considered**:

- Kakao Android SDK의 `OAuthToken`을 Backend로 전달: 가장 단순하지만 “카카오 Token을 앱이 전달하지 않는다”는 승인된 정책을 바꾼다.
- 앱 custom scheme으로 REST code 수신: REST callback 정책과 Android SDK 고정 callback 동작이 달라 공식 지원 조합이 아니다.
- WebView: 제3자 credential 격리와 Android 권고에 맞지 않는다.

**Sources**: [Kakao REST Login](https://developers.kakao.com/docs/en/kakaologin/rest-api), [Kakao Android Login](https://developers.kakao.com/docs/en/kakaologin/android), [Android App Links](https://developer.android.com/training/app-links/about), [Android Web authentication](https://developer.android.com/develop/ui/views/layout/webapps)

## 2. Backend와 Android 기준 버전

**Decision**: Backend는 Python 3.13, FastAPI 0.141.1, SQLAlchemy 2.0.52 async, PostgreSQL 18.6/PostGIS 3.6.x를 고정한다. Android는 Kotlin 2.4.10, `compileSdk/targetSdk 36`, `minSdk 26`, Compose BOM 2026.08.00을 기준으로 한다.

**Rationale**: 저장소에는 제품별 기술만 확정되어 있고 manifest가 없다. 2026-08-25 공식 안정판을 기준으로 재현 가능한 시작점을 선택하되 FastAPI의 `0.x` 특성상 minor를 고정한다. Android 8.0은 AndroidKeyStore AES-GCM, WorkManager, App Links를 적용하면서 MVP 지원 범위를 과도하게 넓히지 않는 하한이다.

**Alternatives considered**:

- pre-release Kotlin 2.4.20-RC: 안정판 요구 때문에 제외했다.
- PostgreSQL 17: 안정 기간은 길지만 신규 구축 시 공식 지원 종료가 더 빠르다.
- Android API 23 하한: 지원 기기는 늘지만 backup·crypto·background 호환 분기가 증가한다.

**Sources**: [FastAPI versions](https://fastapi.tiangolo.com/deployment/versions/), [SQLAlchemy 2.0](https://docs.sqlalchemy.org/en/20/intro.html), [PostgreSQL versioning](https://www.postgresql.org/support/versioning/), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Android 16 SDK](https://developer.android.com/about/versions/16/setup-sdk), [Compose BOM](https://developer.android.com/develop/ui/compose/bom)

## 3. Login transaction과 ticket

**Decision**: `auth_login_transactions`에 `state`와 login ticket의 SHA-256 hash, device binding, 상태, 만료시각과 최소 사용자 snapshot만 저장한다. `state`와 ticket secret은 256-bit 난수다. transaction은 10분, ticket은 callback 후 120초 유효하며 한 번만 소비한다. callback은 `PENDING`을 `PROCESSING`으로 원자적 선점한 후 외부 호출을 수행한다. ticket 교환 transaction은 `VERIFIED` row를 `SELECT ... FOR UPDATE`로 잠근 뒤 조건을 재검증하고, ticket 소비·사용자 upsert·기기 세션 upsert를 함께 commit한다. APScheduler가 terminal/expired row와 snapshot을 24시간 안에 정리한다.

**Rationale**: server-side state가 CSRF 방어, device binding, ticket 일회성 소비를 단순한 조건부 상태 전이로 검증한다. Callback 성공만으로 사용자나 기기 세션을 만들지 않아 App Link 전달 실패가 부분 가입으로 남지 않는다. Token·code·state·ticket 원문은 저장하지 않는다.

**Alternatives considered**:

- 서명된 stateless state/ticket: 일회 사용과 취소를 위해 결국 server-side replay 상태가 필요하다.
- Kakao Token 임시 저장: 장기 secret 저장 범위를 넓혀 제외한다.
- callback에서 즉시 사용자·세션 생성: 앱이 ticket을 받지 못해도 부분 로그인 상태가 남는다.

## 4. 사용자 profile과 활성 기기 수

**Decision**: 신규 사용자는 ticket 교환 시 생성한다. 기존 사용자는 카카오가 이번 로그인에서 제공한 non-null nickname/profile image만 갱신하며 null은 기존 값을 지우지 않는다. 사용자당 활성 기기 수에는 MVP 상한을 두지 않는다.

**Rationale**: 별도 profile 편집 Feature 없이 카카오 표시 정보를 최신화하면서 선택 동의 철회나 일시적 누락으로 기존 표시값이 사라지는 것을 막는다. 임의 기기 상한은 eviction UX와 session 관리 endpoint를 추가하므로 요구가 생길 때 도입한다.

**Alternatives considered**:

- 최초 로그인 값 고정: Kakao profile 변경이 반영되지 않는다.
- null까지 매번 덮어쓰기: 선택 정보 누락이 사용자 표시를 불필요하게 지운다.
- 활성 기기 5개 제한: 초과 처리 정책과 기기 관리 UI가 MVP 범위를 늘린다.

## 5. 길픽 Token 설계

**Decision**: Access Token은 HS256 JWT이며 `sub`, `sid`, `iss`, `aud`, `iat`, `exp`, `jti`, `type=access`만 포함하고 1시간 유효하다. Refresh Token은 `sessionId.secret` opaque 형식이며 `secret`은 256-bit 난수다. DB에는 SHA-256 hash만 저장한다.

**Rationale**: 단일 Backend가 발급·검증하는 MVP에는 JWKS와 비대칭키가 필요하지 않다. Refresh는 어차피 기기별 DB 상태가 필요하므로 JWT 이점이 없다. 고엔트로피 random Token은 빠른 digest로도 offline brute-force가 현실적으로 불가능하다.

**Alternatives considered**:

- RS256/EdDSA: 독립 검증 서비스가 생길 때 도입한다.
- JWT Refresh Token: 폐기·회전 DB 상태와 중복된다.
- Argon2 hash: 사용자 비밀번호와 달리 256-bit random secret에는 비용만 늘린다.

**Sources**: [JWT BCP](https://www.rfc-editor.org/rfc/rfc8725.html), [Python secrets](https://docs.python.org/3/library/secrets.html), [FastAPI JWT](https://fastapi.tiangolo.com/tutorial/security/oauth2-jwt/)

## 6. Refresh 회전과 응답 유실

**Decision**: Refresh 회전은 `session_id`, 현재 hash, `deviceId`, 활성·만료 조건을 포함한 단일 `UPDATE ... RETURNING`으로 처리한다. 동시 요청은 정확히 한 건만 성공한다. MVP에서는 성공 응답 replay를 위한 원문/암호문 저장이나 결정적 Token 생성은 추가하지 않는다. 응답이 유실되면 앱은 세션을 보존하고 같은 Token으로 재시도하며, 서버가 이전 Token 무효를 확정하면 새 카카오 로그인을 요구한다.

**Rationale**: 조건부 update는 별도 distributed lock 없이 PostgreSQL이 경쟁 요청을 직렬화한다. 응답 replay는 이전 hash, idempotency key, 결정적 secret 또는 암호화 응답 보관과 cleanup을 요구한다. 명세는 통신 실패 시 즉시 로그아웃하지 않고 무효가 확인될 때 재로그인하도록 정했으므로 단순 동작도 요구를 만족한다.

**Alternatives considered**:

- `Idempotency-Key`와 결정적 Refresh Token 재현: 응답 유실 복구는 향상되지만 custom Token derivation과 replay window가 추가된다.
- 이전 Token grace period: 서로 다른 동시 요청이 여러 번 성공할 수 있어 확정 정책을 위반한다.
- 성공 응답 원문/암호문 저장: Token 저장 범위와 cleanup 부담을 늘린다.

**Sources**: [PostgreSQL UPDATE](https://www.postgresql.org/docs/current/sql-update.html), [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html), [OAuth Security BCP](https://www.rfc-editor.org/rfc/rfc9700.html)

## 7. Kakao 호출과 transaction 경계

**Decision**: 입력 검증 후 첫 번째 짧은 DB transaction에서 `state` hash와 만료를 확인하고 `PENDING`을 `PROCESSING`으로 조건부 전환해 commit한다. DB connection과 lock을 해제한 뒤 Kakao code 교환·사용자 정보 조회를 수행하고, 두 번째 짧은 DB transaction에서 `VERIFIED` 또는 `FAILED` 결과를 기록한다. code 교환은 자동 재시도하지 않고, 사용자 정보 조회의 timeout/network/5xx만 1회 재시도한다. Kakao 4xx·401·429는 자동 재시도하지 않는다.

**Rationale**: 일회성 code 소비 여부가 불확실한 timeout을 반복하지 않으며, 외부 network 대기 중 DB connection과 row lock을 점유하지 않는다. 부분 사용자·세션 생성도 방지한다.

**Alternatives considered**: 모든 외부 오류 1회 재시도, 외부 호출 전체를 DB transaction 안에서 수행, Kakao 응답을 임시 영구 저장하는 방식을 제외했다.

## 8. Android session과 오프라인 로그아웃

**Decision**: AndroidKeyStore의 non-exportable AES key와 AES-GCM으로 Token을 암호화하고 `auth_session.pb` Proto DataStore에는 ciphertext/IV/만료·session metadata만 원자적으로 저장한다. 앱 설치 단위 `deviceId`는 최초 실행 시 UUID로 생성해 같은 DataStore에 보존하고 앱 데이터 삭제 후에는 새 값으로 생성한다. `AuthRepository`가 session의 단일 source of truth이며 refresh는 in-flight 작업 하나로 합친다. 로그아웃은 active session을 즉시 제거하고 각 요청에 새 `revocationOperationId`를 부여한 pending revocation envelope 큐로 격리한다. WorkManager unique work 이름은 session ID가 아니라 이 operation ID를 사용하며 각 worker는 한 envelope만 exponential backoff로 처리한다. `INVALID_REFRESH_TOKEN`·`TOKEN_EXPIRED`는 폐기 완료와 동등하게 envelope를 삭제하고, `DEVICE_MISMATCH`는 다른 session을 건드리지 않은 terminal failure로 기록 후 삭제한다. network/timeout/`429`/`5xx`만 재시도한다. AndroidKeyStore key 손실로 pending envelope를 복호화할 수 없으면 읽을 수 없는 local data를 제거하고 local logout을 유지하며 서버의 최대 30일 만료 또는 같은 기기 재로그인 교체에 의존한다.

**Rationale**: process 종료·재부팅 후에도 폐기 재시도를 유지하면서 pending Token을 인증에 재사용하지 않는다. 요청별 operation ID는 같은 session row가 재로그인으로 재활성화되거나 여러 logout envelope가 생겨도 WorkManager 작업 충돌을 막는다. WorkManager input/output에는 Token을 넣지 않는다. 앱 삭제·데이터 삭제 후에는 worker가 보장되지 않으며 서버의 30일 만료가 최종 안전장치다.

**Alternatives considered**: plaintext DataStore, deprecated EncryptedSharedPreferences, 화면 coroutine만 재시도, 서버 성공 전까지 사용자 로그아웃 차단을 제외했다.

**Sources**: [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Android cryptography](https://developer.android.com/privacy-and-security/cryptography), [DataStore](https://developer.android.com/topic/libraries/architecture/datastore), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent), [Offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first)

## 9. 오류·rate limit·관찰 가능성

**Decision**: Backend가 생성하는 JSON 오류는 공통 envelope와 안정적인 error code, `retryable`, request ID를 사용한다. Callback은 신뢰 가능한 transaction을 찾은 경우 성공·실패 모두 민감정보를 제거한 `302` App Link로 종료하고, 성공 ticket은 URI fragment에 넣는다. Token/ticket을 반환하는 JSON 응답과 callback에는 `Cache-Control: no-store`, callback에는 추가로 `Referrer-Policy: no-referrer`를 보낸다. Backend log에는 request ID, 내부 error code, provider status/code, latency, transaction/session ID만 남긴다. 공모전 MVP를 위해 새 AWS WAF·Redis·DB rate counter는 필수 구현에서 제외하고, 배포 환경에 기존 WAF가 있을 때만 별도 hardening으로 적용한다.

**Rationale**: 실제 운영 traffic과 공유 limiter 저장소가 없는 공모전 단계에서 임의의 per-path 한도와 새 인프라를 필수화하면 검증 비용이 핵심 인증 구현보다 커진다. Kakao provider의 `429`는 기존 callback 오류 code로 구분하고, 자체 rate limit은 실제 배포 경계가 확정된 뒤 추가한다. Token, code, state, ticket, profile은 log에서 제외한다.

**Alternatives considered**: Backend process-local limiter는 여러 worker에서 한도가 갈라진다. PostgreSQL rate counter와 새 Redis 기반 application limiter는 공통 envelope를 유지하지만 MVP 인증을 위해 새 상태 저장 경로를 추가하므로 제외했다. 새 WAF 구축도 공모전 필수 범위에서는 제외했다.

**Source**: [AWS WAF rate-based rules](https://docs.aws.amazon.com/waf/latest/developerguide/waf-rule-statement-type-rate-based.html)

## 10. 데이터 보존·삭제와 접근

**Decision**: terminal/expired login transaction과 임시 profile snapshot은 24시간 안에 삭제한다. 만료·폐기된 device session은 장애 확인과 재시도 진단을 위해 30일 보관 후 삭제하고 active session은 유효한 동안 보관한다. Kakao subject와 선택 profile은 계정 활성 기간에만 인증 목적으로 보관하며 계정 탈퇴·즉시 삭제는 후속 Feature에서 처리한다. 인증 application log는 30일 보관 후 삭제하고 Backend 운영 계정만 접근한다. 공모전 test 계정과 data는 심사·시연 종료 후 삭제한다.

**Rationale**: 공모전 MVP에서 별도 개인정보 lifecycle service를 만들지 않으면서도 수집 전에 보존 기간과 cleanup 책임을 확정한다. 인증 transaction의 짧은 보존은 민감 snapshot 노출 범위를 줄이고, session의 30일 진단 기간은 Refresh 최대 수명과 맞춘다.

**Alternatives considered**: 모든 row 무기한 보관, 즉시 물리 삭제, 별도 archival storage와 개인정보 관리 service는 MVP 범위에서 제외했다.
