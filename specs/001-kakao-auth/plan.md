# Implementation Plan: 카카오 인증

**Branch**: `001-kakao-auth` (Spec Kit 논리 식별자; 현재 Git branch `docs/jh-spec-pr-workflow`, 구현 branch 미생성) | **Date**: 2026-08-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-kakao-auth/spec.md`

## Summary

Android 사용자는 Custom Tab에서 카카오 인증을 수행하고, Backend HTTPS callback이 `state` 검증·인가 코드 교환·사용자 확인을 담당한다. Backend는 카카오 code/Token 대신 120초 유효 일회용 login ticket을 Android verified App Link의 URI fragment로 전달하고, 앱이 ticket을 한 번 교환할 때 길픽 사용자와 기기 세션을 원자적으로 만들거나 갱신한다. 길픽 Access Token은 1시간 JWT, Refresh Token은 기기별 sliding 30일 opaque Token으로 관리한다. Android는 Token을 AndroidKeyStore 기반으로 보호하고, refresh를 single-flight로 직렬화하며, 오프라인 로그아웃의 서버 폐기는 요청별 `revocationOperationId`를 가진 WorkManager 작업으로 재시도한다.

## Technical Context

**Language/Version**: Backend Python 3.13; Android Kotlin 2.4.10

**Primary Dependencies**: FastAPI 0.141.1, SQLAlchemy 2.0.52 async, Alembic, asyncpg, HTTPX, PyJWT, APScheduler; Android `compileSdk 37`·`targetSdk 36`, Jetpack Compose BOM 2026.08.00, Lifecycle/ViewModel, Retrofit·OkHttp, Proto DataStore, WorkManager, AndroidKeyStore

**Storage**: PostgreSQL 18.6 + PostGIS 3.6.x (`users`, `device_sessions`, `auth_login_transactions`); AndroidKeyStore AES-GCM key + app-private Proto DataStore ciphertext

**Testing**: Backend pytest, pytest-asyncio, HTTPX ASGI client, PostgreSQL integration/contract tests; Android JUnit, kotlinx-coroutines-test, MockWebServer, WorkManager test utilities, Compose UI tests, App Link verification

**Target Platform**: Docker 기반 AWS Linux Backend; Android 8.0 이상(`minSdk 26`, `targetSdk 36`, `compileSdk 37`)

**Project Type**: Android mobile app + REST API web service

**Performance Goals**: mock App Link 수신부터 빈 여행 목록 shell 표시까지 20회 중 19회 이상 10초 이내; 외부 Kakao 대기시간을 제외한 JSON 인증 endpoint p95 500ms 이하; 서로 다른 유효 session 100건의 Refresh 전부 성공

**Constraints**: Access Token 1시간, Refresh Token sliding 30일; 사용자당 활성 기기 수 MVP 상한 없음; Kakao code 교환은 자동 재시도 금지, 사용자 정보 조회만 1회; 외부 호출 중 DB transaction/lock 유지 금지; Token·인가 코드·`state`·login ticket 원문 저장/log 금지; 공모전 MVP에서 새 AWS WAF·Redis·Celery·별도 Token blacklist 제외

**Scale/Scope**: MVP 단일 Backend 배포 단위와 4인 팀; 인증 endpoint 5개, Android 인증 화면/상태 저장/백그라운드 폐기; 초기 검증 기준 동시 인증 요청 100건이며 수평 확장 전용 구성은 제외

### Data Retention and Access

- `auth_login_transactions`와 임시 profile snapshot은 terminal/expired 전환 후 24시간 안에 삭제한다.
- 만료되거나 폐기된 `device_sessions`는 상태 확정 후 30일 동안 장애 확인과 재시도 진단에 사용한 뒤 삭제한다. Active session은 유효한 동안 보관한다.
- `users`의 Kakao subject와 선택 profile은 계정이 활성 상태인 동안 보관한다. 계정 탈퇴·즉시 삭제 기능은 후속 Feature에서 구현하되 그 전까지 인증 이외의 목적으로 사용하지 않는다.
- 인증 application log는 30일 보관 후 삭제하고 Backend 운영 계정만 접근한다. Token, code, `state`, ticket, profile 원문은 log에 기록하지 않는다.
- 공모전 test 계정과 test data는 심사·시연 종료 후 삭제한다.

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가 완료.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 네트워크 장애 시 로그인 상태를 보존하고 보호 기능만 잠시 차단한다. 로그아웃은 로컬에서 즉시 완료하며 서버 폐기는 재시도한다. | PASS |
| II. 계약 우선 SDD와 문서 동기화 | 카카오 공식 흐름과 충돌한 기존 계약을 승인된 Backend callback 방식으로 변경하고 `spec.md`, 요구사항, API 명세, ERD, 기술 아키텍처와 Feature contract를 함께 갱신한다. | PASS |
| III. 상태 변경 일관성·멱등성·추적성 | ticket row를 잠근 뒤 소비와 사용자·기기 세션 생성을 한 transaction으로 처리하고, Refresh 회전은 조건부 update 한 문장으로 처리한다. 로그아웃 재시도는 요청별로 격리하고 동일 자격은 멱등 `204`다. | PASS |
| IV. 외부 의존성 실패 격리 | code 교환은 재시도하지 않고 사용자 정보 조회만 1회 재시도한다. 최종 실패 시 부분 사용자·세션을 남기지 않고 다음 행동을 반환한다. | PASS |
| V. 보안·소유권·최소 데이터 | 카카오 Token은 memory에서만 사용하고 모든 장기 secret은 hash 또는 AndroidKeyStore 암호문으로만 보관한다. ticket과 session을 `deviceId`에 결합하고 수집 데이터·log의 보존·삭제 기간과 접근 범위를 정의한다. | PASS |
| 교차 계약 review | 인증은 Android·Backend 모두에 영향을 주므로 구현 PR 전에 Backend `jh`와 Frontend `jy`가 contract 영향 범위를 확인해야 한다. | PASS WITH REVIEW CONDITION |

위반 예외는 없다. Frontend review 조건은 설계 위반이 아니라 constitution에 따른 필수 품질 gate다.

### Post-Design Re-check

- `data-model.md`는 원문 secret을 저장하지 않고 상태 전이와 cleanup을 정의한다.
- `contracts/auth.openapi.yaml`은 공통 envelope, HTTP 의미, cache 방지 header와 callback의 `302`를 명시한다.
- `quickstart.md`는 정상 흐름뿐 아니라 state 위조, ticket replay, 동시 refresh, 응답 유실, 안전한 저장소와 요청별 오프라인 logout queue를 검증한다.
- Phase 1 이후에도 모든 constitution gate는 PASS이며 새로운 예외는 없다.

## Project Structure

### Documentation (this feature)

```text
specs/001-kakao-auth/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── auth.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md                 # $speckit-tasks에서 생성
```

### Source Code (repository root)

현재 저장소는 문서만 존재하므로 아래는 F001에서 생성할 최소 목표 구조다.

```text
api/
├── app/
│   ├── api/dependencies.py
│   ├── api/errors.py
│   ├── api/v1/auth.py
│   ├── clients/kakao.py
│   ├── core/config.py
│   ├── core/logging.py
│   ├── core/security.py
│   ├── db.py
│   ├── jobs/auth_cleanup.py
│   ├── main.py
│   ├── models/auth.py
│   ├── schemas/auth.py
│   └── services/auth.py
├── migrations/versions/
├── tests/
│   ├── contract/test_auth_contract.py
│   ├── integration/test_auth_flow.py
│   ├── integration/test_auth_load.py
│   ├── unit/test_auth_dependency.py
│   └── unit/test_auth_service.py
└── pyproject.toml

android/
├── app/src/main/java/com/gilpick/auth/
│   ├── AuthApi.kt
│   ├── AuthAppLinkHandler.kt
│   ├── AuthRepository.kt
│   ├── AuthSessionStore.kt
│   ├── AuthUiState.kt
│   ├── AuthViewModel.kt
│   ├── AuthenticatedHomeScreen.kt
│   ├── LoginScreen.kt
│   └── SessionRevocationWorker.kt
├── app/src/main/res/xml/
│   ├── backup_rules.xml
│   └── data_extraction_rules.xml
├── app/src/test/java/com/gilpick/auth/
└── app/src/androidTest/java/com/gilpick/auth/
```

**Structure Decision**: Backend와 Android를 하나의 repository 안의 `api/`, `android/`로 분리한다. F001에서는 인증에 필요한 파일만 만들며 단일 구현을 위한 interface/factory, 범용 OAuth provider abstraction, Redis, Token blacklist, 별도 message queue는 만들지 않는다. `AuthRepository`는 Android 로그인 상태의 유일한 변경 진입점이고 Backend `auth.py`는 얇은 transport 계층, `auth.py` service는 transaction 경계를 소유한다. F002 전에는 `AuthenticatedHomeScreen`을 빈 여행 목록 shell로 사용한다.

## Phase 0 Research Decisions

결정 근거와 대안은 [research.md](research.md)에 기록했다. 모든 기술적 미확정 사항은 해소됐으며 `NEEDS CLARIFICATION`은 없다.

## Phase 1 Design Outputs

- 데이터와 상태 전이: [data-model.md](data-model.md)
- REST/callback 계약: [contracts/auth.openapi.yaml](contracts/auth.openapi.yaml)
- 종단간 검증 절차: [quickstart.md](quickstart.md)

## Complexity Tracking

Constitution 위반이나 정당화가 필요한 추가 복잡성은 없다. Refresh 응답 replay용 서버 저장·결정적 Token 생성은 구현하지 않는다. 따라서 응답 유실 후 이전 Token이 무효로 확인되면 재로그인하고, 그 직후 logout에서는 이전 Token으로 서버 폐기가 불가능할 수 있어 최대 30일 만료 또는 같은 기기 재로그인 교체에 의존한다. AndroidKeyStore key 손실로 pending logout 자격을 복호화할 수 없는 경우에도 읽을 수 없는 envelope를 제거하고 같은 서버 만료·교체 안전장치에 의존하는 MVP 잔여 위험을 수용한다.
