# Quickstart Validation: 카카오 인증

이 문서는 F001 구현 후 Backend와 Android의 인증 흐름을 종단간 검증하는 실행 가이드다. 현재 저장소에는 source scaffold가 없으므로 아래 경로와 명령은 `$speckit-tasks`와 구현 단계에서 생성한 뒤 실행한다.

## 1. Prerequisites

- Docker와 Docker Compose
- Python 3.13
- JDK와 Android SDK Platform 37(`compileSdk`), Android emulator 또는 API 26 이상 기기
- 수동 App Link 검증 명령용 API 31 이상 emulator/기기(API 26~30 기능 검증은 별도 수행)
- Kakao Developers test app
  - REST API key와 Client secret 활성화
  - `KAKAO_REDIRECT_URI`에 Backend HTTPS callback 등록
  - nickname 필수/선택 정책과 profile image 선택 동의 설정
- Android verified App Link용 HTTPS domain과 `assetlinks.json`
  - `ANDROID_APP_LINK_HOST`에 공모전용 실제 HTTPS host 설정
  - debug와 제출용 release APK SHA-256 fingerprint를 `assetlinks.json`에 등록하고 실제 host의 `/.well-known/assetlinks.json`에 배포
- AWS secret storage 또는 로컬 test secret

필수 환경값:

```text
DATABASE_URL
JWT_SIGNING_SECRET
JWT_ISSUER
JWT_AUDIENCE
KAKAO_REST_API_KEY
KAKAO_CLIENT_SECRET
KAKAO_REDIRECT_URI
ANDROID_APP_LINK_BASE_URL
ANDROID_APP_LINK_HOST
```

실제 secret은 repository, test output, screenshot에 넣지 않는다.

## 2. Contract and data model review

구현 전에 다음 문서가 서로 일치하는지 확인한다.

- [Feature spec](spec.md)
- [Implementation plan](plan.md)
- [Data model](data-model.md)
- [OpenAPI contract](contracts/auth.openapi.yaml)
- `docs/design/api-spec.md`
- `docs/design/er-schema.md`

확인 항목:

- JSON endpoint 4개와 Kakao callback 1개가 모두 존재한다.
- callback만 공통 JSON envelope 대신 `302`를 사용한다.
- callback 성공 ticket은 query가 아니라 App Link URI fragment로 전달한다.
- DB에는 `state`, login ticket, Refresh Token, Kakao Token 원문 컬럼이 없다.
- `deviceId`는 API에서 UUID로 검증되고 transaction/session에 결합된다.
- Token/ticket 응답은 `Cache-Control: no-store`, callback은 `Cache-Control: no-store`와 `Referrer-Policy: no-referrer`를 반환한다.

## 3. Backend validation

구현 후 repository root에서 다음 순서로 실행한다.

```powershell
docker compose up -d postgres
Set-Location api
python -m alembic upgrade head
python -m pytest tests/unit/test_auth_service.py -q
python -m pytest tests/contract/test_auth_contract.py -q
python -m pytest tests/integration/test_auth_flow.py -q
```

Expected:

- 모든 test가 통과한다.
- migration 후 `auth_login_transactions`, `users`, `device_sessions`와 정의된 unique/index/check constraint가 존재한다.
- cleanup test에서 terminal/expired login transaction과 사용자 snapshot이 24시간 이내 삭제 대상이 된다.
- 만료·폐기된 `device_sessions`가 상태 확정 30일 후 삭제 대상이 되고 인증 application log 보존기간이 30일로 설정되어 있다.
- 만료·서명·issuer·audience·type이 잘못된 Access Token이 test용 보호 route handler 실행 전에 거절된다.
- Kakao HTTP 호출 직전에 DB transaction과 row lock이 해제되어 있다.
- test log와 failure output에 code, `state`, login ticket, Access/Refresh Token 원문이 나타나지 않는다.
- 모든 login·refresh·logout 성공·실패 응답의 request ID가 application log의 operation/result/transaction 또는 session ID와 연결된다.

## 4. Android validation

```powershell
Set-Location android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

Expected:

- `AuthRepository`가 동시에 발생한 보호 API 실패를 refresh 호출 하나로 합친다.
- Token pair가 한 DataStore update로 교체된다.
- refresh timeout/network failure 시 모든 동시 대기자가 `RefreshOffline`을 받고 암호화 local session은 유지된다.
- `INVALID_REFRESH_TOKEN`, `TOKEN_EXPIRED`, `DEVICE_MISMATCH`는 `SignedOut`으로 전환한다.
- 원 요청 replay는 refresh 성공 후 최대 한 번만 수행하고, replay도 `401`이면 refresh loop 없이 `SignedOut`으로 전환한다.
- logout 직후 보호 화면 접근이 차단되고 각 pending revocation envelope에 별도 `revocationOperationId`와 unique work가 하나씩 등록된다.
- process 재시작 뒤에도 pending revocation이 남고 network 복구 후 처리된다.
- DataStore 파일, WorkManager input/output, backup/data-extraction 산출물에 Access/Refresh Token 원문이 없다.
- AndroidKeyStore key invalidation 시 local session과 읽을 수 없는 pending envelope를 제거하고 crash나 stuck 상태 없이 `SignedOut`으로 전환한다.

App Link 확인:

```powershell
adb shell pm verify-app-links --re-verify com.gilpick
adb shell pm get-app-links com.gilpick
adb shell am start -W -a android.intent.action.VIEW -d "https://$env:ANDROID_APP_LINK_HOST/auth/kakao/complete#loginTicket=test"
```

Expected: 실제 공모전 domain의 인증 완료 path가 debug·release 인증서에서 검증되고 `am start`가 길픽 package/activity로 resolve되어 다른 앱 선택 dialog 없이 열린다. 같은 host의 API callback path `/api/v1/auth/kakao/callback`은 앱이 claim하지 않아야 한다.

## 5. End-to-end Kakao login

1. 로그아웃 상태의 앱에서 카카오 로그인을 누른다.
2. 앱이 `POST /api/v1/auth/kakao/transactions`를 호출하고 받은 `authorizationUrl`을 Custom Tab으로 연다.
3. 카카오 test 계정으로 동의한다.
4. Kakao가 Backend HTTPS callback을 호출하고 Backend가 login ticket을 URI fragment에 담은 verified App Link로 redirect하는지 확인한다.
5. 앱이 `POST /api/v1/auth/kakao/exchange`로 ticket을 교환한다.
6. 신규 사용자는 `201`, 기존 사용자는 `200`과 1시간/30일 Token pair를 받는다.
7. 여행 목록 진입까지 시간을 측정한다.

Expected:

- 실제 Kakao test 계정 로그인이 한 번 이상 성공하고 빈 여행 목록 shell로 진입한다.
- F002 구현 전에는 빈 여행 목록 shell을 표시한다.
- 같은 Kakao account로 다른 기기에서 로그인해도 기존 기기 session은 유지된다.
- 같은 기기 재로그인은 session row를 중복 생성하지 않고 새 Refresh hash로 교체한다.
- ticket을 다시 교환하면 `401 INVALID_LOGIN_TICKET`이다.

## 6. Security and failure scenarios

| Scenario | Expected outcome |
|---|---|
| 변조·만료된 `state` callback | Kakao code 교환 없음, 사용자/session 생성 없음 |
| code 교환 timeout | 같은 code 자동 재시도 없음, 새 인증 안내 |
| 사용자 정보 조회 첫 timeout | 정확히 한 번 재시도 |
| 사용자 정보 조회 재시도도 실패 | 부분 user/session 없음, retryable provider 오류 |
| 만료된 login ticket | `401 LOGIN_TICKET_EXPIRED` |
| ticket의 device 불일치 | `403 DEVICE_MISMATCH`, ticket 미소비 |
| 같은 Refresh Token 동시 요청 2건 | 정확히 1건 `200`, 나머지 `401 INVALID_REFRESH_TOKEN` |
| Refresh 성공 응답 유실 | local session 보존; 재시도에서 old Token 무효 확인 시 새 Kakao 로그인 |
| Refresh 응답 유실 직후 logout | old Token 폐기가 불가능할 수 있음; 최대 30일 만료 또는 같은 기기 재로그인 교체를 잔여 위험으로 확인 |
| 한 기기 logout | 현재 기기만 revoked, 다른 기기 정상 |
| 같은 logout 재전송 | 멱등 `204` |
| offline logout | 즉시 `SignedOut`, 연결 복구 후 server revocation 성공 |
| offline logout A 후 같은 기기 재로그인·logout B | 서로 다른 operation ID의 worker가 각 envelope만 처리하고 충돌 없음 |
| pending logout의 `INVALID_REFRESH_TOKEN`·`TOKEN_EXPIRED` | 성공과 동등하게 envelope 삭제 |
| pending logout의 `DEVICE_MISMATCH` | 다른 session 변경 없이 terminal diagnostic 후 envelope 삭제 |
| 앱 삭제 전 pending revocation 미완료 | worker 보장 없음; server Refresh Token은 최대 30일 후 만료 |
| Token/code/ticket 포함 요청 log 검사 | 원문 노출 0건 |

## 7. Performance smoke test

Kakao provider는 mock하고 JSON endpoint의 application/DB 경로만 측정한다.

```powershell
Set-Location api
python -m pytest tests/integration/test_auth_load.py -q
Set-Location ..\android
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.gilpick.auth.AuthLoginTest
```

Expected:

- 동시 인증 요청 100건에서 데이터 무결성 오류가 없다.
- 외부 Kakao 대기시간을 제외한 인증 endpoint p95가 500ms 이하다.
- 같은 Token 동시 refresh에서 성공 row update가 정확히 한 건이다.
- 서로 다른 유효 session 100건의 Refresh가 모두 성공한다.
- Android mock 환경에서 App Link 수신부터 빈 여행 목록 shell 표시까지 20회 중 19회 이상이 10초 이내다.

## 8. Completion evidence

PR에는 다음을 남긴다.

- 실제 실행한 명령과 pass/fail 결과
- App Link verification 결과
- migration upgrade 검증 결과
- 정상/오류 contract test 결과
- Token·code·ticket log redaction 확인 결과
- 인증 log 30일 보존·접근 제한 설정과 Google-style docstring·KDoc 확인 결과
- Backend `jh`와 Frontend `jy`의 contract 영향 확인 기록

실행하지 않은 항목은 통과로 표시하지 않고 미실행 이유를 기록한다.
