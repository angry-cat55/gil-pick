---

description: "F001 카카오 인증 구현 작업 목록"
---

# Tasks: 카카오 인증

**Input**: `/specs/001-kakao-auth/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Tests**: 명세의 독립 검증 기준과 constitution의 인증·DB 계약 검증 원칙에 따라 각 User Story에 test task를 포함한다. Test를 먼저 작성해 실패를 확인한 뒤 구현한다.

**Organization**: User Story별로 독립 구현·검증할 수 있게 구성하며 Backend는 `jh`, Frontend Android는 `jy`가 담당한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 task 완료 후 서로 다른 파일을 수정하며 동시에 진행 가능
- **[Story]**: `spec.md`의 User Story 식별자
- 모든 task는 담당, 영역, 선행 관계와 검증 방법을 포함한다.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Backend와 Android source scaffold 및 로컬 개발 환경을 만든다.

- [x] T001 Backend Python·Alembic 프로젝트와 ASGI entrypoint 초기화 in api/pyproject.toml, api/alembic.ini, api/migrations/env.py, api/app/main.py, api/app/__init__.py, api/app/api/v1/__init__.py
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: Python 3.13, FastAPI 0.141.1, SQLAlchemy 2.0.52 dependency가 lock/pin되고 `python -m pytest --collect-only`가 import 오류 없이 완료
- [x] T002 [P] Android Gradle wrapper·application entrypoint 초기화 in android/settings.gradle.kts, android/build.gradle.kts, android/app/build.gradle.kts, android/gradlew.bat, android/gradle/wrapper/gradle-wrapper.properties, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: 없음
  - 검증: `android/gradlew.bat tasks` 성공 및 `minSdk 26`, `targetSdk 36`, `compileSdk 37` 확인
- [x] T003 [P] PostgreSQL 로컬 실행과 비밀정보 없는 환경 예시 구성 in docker-compose.yml, api/.env.example
  - 영역: BE
  - 담당: jh
  - 선행: 없음
  - 검증: PostgreSQL 18.6/PostGIS 3.6.x image가 고정되고 `docker compose config` 성공 및 실제 secret 원문 미포함 확인

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 인증 User Story가 공유하는 설정, DB, Token, 오류 계약과 Android session 기반을 구현한다.

**⚠️ CRITICAL**: 각 User Story의 Backend·Frontend task는 해당 영역의 Foundation 선행 task가 완료되어야 시작할 수 있다.

- [x] T004 Backend 인증·Kakao·App Link 환경 설정과 시작 시 검증 구현 in api/app/core/config.py
  - 영역: BE
  - 담당: jh
  - 선행: T001, T003
  - 검증: 필수 환경값 누락·잘못된 HTTPS URL에 대한 unit test 통과
- [x] T005 [P] SQLAlchemy async engine과 transaction session 기반 구현 in api/app/db.py
  - 영역: BE
  - 담당: jh
  - 선행: T001, T003
  - 검증: PostgreSQL 연결·rollback integration smoke test 통과
- [x] T006 인증 공통 entity와 제약조건 migration 구현 in api/app/models/auth.py, api/migrations/versions/001_create_auth_tables.py
  - 영역: BE
  - 담당: jh
  - 선행: T005
  - 검증: Alembic upgrade 후 `users`, `device_sessions`, `auth_login_transactions`의 unique/index/check constraint 확인
- [x] T007 [P] Access JWT·opaque Refresh/login ticket과 보호 API 인증 dependency 구현 in api/app/core/security.py, api/app/api/dependencies.py, api/tests/unit/test_auth_dependency.py
  - 영역: BE
  - 담당: jh
  - 선행: T001, T004
  - 검증: JWT signature·issuer·audience·type·exp 검증과 handler 실행 전 거절, 1시간 claim, 80자 selector Token, 원문 미저장 hash unit test 통과
- [x] T008 [P] 인증 schema·공통 오류·request ID·상태 전이 log와 redaction 구현 in api/app/schemas/auth.py, api/app/api/errors.py, api/app/core/logging.py
  - 영역: BE
  - 담당: jh
  - 선행: T001
  - 검증: success/error response request ID가 operation/result/transaction 또는 session ID log와 연결되고 Token·code·state·ticket·profile 원문이 노출되지 않는 test 통과
- [x] T009 [P] AndroidKeyStore AES-GCM 기반 `auth_session.pb` session·deviceId·pending revocation 저장소 구현 in android/app/src/main/java/com/gilpick/auth/AuthSessionStore.kt
  - 영역: FE
  - 담당: jy
  - 선행: T002
  - 검증: 설치 최초 UUID `deviceId` 생성·재사용, 원자적 Token pair 저장, 평문 미저장, key invalidation 시 local 초기화 unit test 통과
- [x] T010 verified App Link·`assetlinks.json` 배포와 `auth_session.pb` backup 제외 설정 구현 in android/app/src/main/AndroidManifest.xml, android/app/src/main/res/xml/backup_rules.xml, android/app/src/main/res/xml/data_extraction_rules.xml, deployment target https://$ANDROID_APP_LINK_HOST/.well-known/assetlinks.json
  - 영역: FE
  - 담당: jy
  - 선행: T004, T009
  - 검증: 공모전 실제 host의 debug·release fingerprint 검증, 인증 완료 path만 `autoVerify` claim, API callback 미claim, `auth_session.pb` backup 제외 확인
- [x] T011 [P] Retrofit 인증 transport와 공통 DTO·오류 mapping 기반 구현 in android/app/src/main/java/com/gilpick/auth/AuthApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T002
  - 검증: OpenAPI의 success/error 응답 parsing unit test 통과
- [x] T012 Android 인증 repository 단일 진입점과 공통 UI 상태 기반 구현 in android/app/src/main/java/com/gilpick/auth/AuthRepository.kt, android/app/src/main/java/com/gilpick/auth/AuthUiState.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009, T011
  - 검증: 저장된 session 복원과 `SignedOut`·`Authenticated`·`RefreshOffline` 상태 전이 unit test 통과

**Checkpoint**: Backend와 Android 공통 기반이 준비되어 각 User Story를 seeded 상태로 독립 검증할 수 있다.

---

## Phase 3: User Story 1 - 카카오 계정으로 로그인 (Priority: P1) 🎯 MVP

**Goal**: Android 사용자가 Kakao 인증을 완료하고 일회용 ticket을 교환하여 사용자·현재 기기 session을 얻고 빈 여행 목록 shell로 진입한다.

**Independent Test**: 로그아웃 상태에서 신규·기존 Kakao 사용자의 정상 로그인, 다중 기기 로그인, provider 오류, state 위조, ticket 만료·재사용을 각각 검증한다.

### Tests for User Story 1

- [x] T013 [P] [US1] transaction·callback·ticket exchange OpenAPI contract test 작성 in api/tests/contract/test_auth_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T004, T006, T007, T008
  - 검증: 구현 전 실패하고 `201/200/302/400/401/403/500`, request ID, cache/referrer header와 URI fragment 계약을 검사
- [x] T014 [P] [US1] 신규·기존·다중 기기·ticket 단일 소비 integration test 작성 in api/tests/integration/test_auth_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T005, T006, T007
  - 검증: 구현 전 실패하고 동시 ticket 교환 중 정확히 한 건만 성공하며 부분 user/session이 남지 않는 조건 포함
- [x] T015 [P] [US1] state 선점·Kakao retry 경계·profile upsert unit test 작성 in api/tests/unit/test_auth_service.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T007, T008
  - 검증: 구현 전 실패하고 code 교환 무재시도, 사용자 조회 1회 재시도, nullable profile 로그인 성공, 외부 HTTP 호출 전 DB transaction·row lock 해제와 `PENDING→PROCESSING→VERIFIED/FAILED` 전이를 검사
- [x] T016 [P] [US1] Android login transaction·ticket 교환·암호화 저장 unit test 작성 in android/app/src/test/java/com/gilpick/auth/AuthLoginFlowTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009, T010, T011, T012
  - 검증: 구현 전 실패하고 URI fragment ticket 제거, Token pair 원자 저장과 오류별 다음 행동을 검사
- [x] T017 [P] [US1] Custom Tab·verified App Link·로그인 화면 Compose test 작성 in android/app/src/androidTest/java/com/gilpick/auth/AuthLoginTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T010, T012
  - 검증: 구현 전 실패하고 실제 host/path의 intent 수신과 성공 시 빈 여행 목록 shell 이동을 검사

### Implementation for User Story 1

- [x] T018 [P] [US1] Kakao code 교환과 사용자 조회 client 및 재시도 정책 구현 in api/app/clients/kakao.py
  - 영역: BE
  - 담당: jh
  - 선행: T004, T015
  - 검증: mock provider로 timeout·4xx·429·5xx mapping과 code 무재시도/사용자 조회 1회 재시도 test 통과
- [x] T019 [US1] login transaction 선점과 ticket 원자 교환 service 구현 in api/app/services/auth.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T007, T008, T014, T015, T018
  - 검증: `SELECT ... FOR UPDATE` ticket 단일 소비, user/device session upsert, 실패 전체 rollback과 request ID·상태 전이 log test 통과
- [x] T020 [US1] `POST /auth/kakao/transactions`·`GET /auth/kakao/callback`·`POST /auth/kakao/exchange` endpoint와 router 등록 구현 in api/app/api/v1/auth.py, api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T013, T019
  - 검증: US1 contract/integration test 전체 통과 및 callback 302·no-store·no-referrer 확인
- [x] T021 [US1] login transaction과 만료·폐기 device session 보존정책 cleanup job·application lifecycle 등록 구현 in api/app/jobs/auth_cleanup.py, api/app/main.py
  - 영역: BE
  - 담당: jh
  - 선행: T006, T020
  - 검증: transaction/snapshot은 24시간, expired/revoked session은 30일 후 삭제 대상이며 active session은 유지되고 실패 시 다음 실행에서 재시도되는 test 통과
- [x] T022 [P] [US1] Android transaction 생성·ticket 교환 API DTO와 호출 구현 in android/app/src/main/java/com/gilpick/auth/AuthApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T011, T016
  - 검증: MockWebServer로 `201/200` Token 응답과 callback error code mapping test 통과
- [x] T023 [P] [US1] App Link URI fragment의 login ticket 일회 수신·제거 구현 in android/app/src/main/java/com/gilpick/auth/AuthAppLinkHandler.kt
  - 영역: FE
  - 담당: jy
  - 선행: T010, T016, T017
  - 검증: 허용 host/path·fragment만 수락하고 query ticket·중복 intent·잘못된 URI를 거절하는 test 통과
- [x] T024 [US1] Custom Tab 시작부터 session 저장까지 Android login orchestration 구현 in android/app/src/main/java/com/gilpick/auth/AuthRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T012, T022, T023
  - 검증: 신규·기존 사용자 로그인과 오류·취소 시 local partial session 없음에 대한 unit test 통과
- [x] T025 [US1] 로그인 화면·진행·재시도와 빈 여행 목록 shell navigation 구현 in android/app/src/main/java/com/gilpick/auth/AuthViewModel.kt, android/app/src/main/java/com/gilpick/auth/AuthUiState.kt, android/app/src/main/java/com/gilpick/auth/LoginScreen.kt, android/app/src/main/java/com/gilpick/auth/AuthenticatedHomeScreen.kt, android/app/src/main/java/com/gilpick/MainActivity.kt
  - 영역: FE
  - 담당: jy
  - 선행: T017, T024
  - 검증: Compose test 통과 및 profile null 사용자도 빈 여행 목록 shell 진입 확인

**Checkpoint**: User Story 1만으로 신규·기존·다중 기기 Kakao 로그인을 독립 시연할 수 있다.

---

## Phase 4: User Story 2 - 로그인 상태 갱신 (Priority: P2)

**Goal**: 만료된 Access Token을 현재 기기의 유효한 Refresh Token으로 한 번만 회전하고, 통신 장애와 확인된 무효 자격을 구분한다.

**Independent Test**: seeded `DeviceSession`으로 정상 회전, 만료·폐기·기기 불일치, 동일 Token 동시 요청, 응답 유실과 Android single-flight를 로그인 UI 없이 검증한다.

### Tests for User Story 2

- [x] T026 [P] [US2] Refresh endpoint OpenAPI contract test 추가 in api/tests/contract/test_auth_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T013
  - 검증: 구현 전 실패하고 `200/401/403/500`, success/error request ID, sliding 30일과 no-store 응답을 검사
- [x] T027 [P] [US2] Refresh 조건부 회전·동시성·응답 유실 integration test 추가 in api/tests/integration/test_auth_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T014
  - 검증: 구현 전 실패하고 동일 Token 동시 요청 중 정확히 한 건만 `200`이며 이전 hash가 재사용되지 않음을 검사
- [ ] T028 [P] [US2] Android single-flight·1회 replay·offline 보존 unit test 작성 in android/app/src/test/java/com/gilpick/auth/AuthRefreshTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T012
  - 검증: 구현 전 실패하고 동시 `401`이 refresh 한 건으로 합쳐지며 모든 waiter가 같은 결과를 받는 조건 포함
- [ ] T029 [P] [US2] Android refresh 성공·재로그인·재시도 UI integration test 작성 in android/app/src/androidTest/java/com/gilpick/auth/AuthRefreshIntegrationTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T025
  - 검증: 구현 전 실패하고 replay 두 번째 `401`은 loop 없이 `SignedOut`, network failure는 `RefreshOffline`임을 검사

### Implementation for User Story 2

- [x] T030 [US2] Refresh Token 단일 조건부 `UPDATE ... RETURNING` 회전 service 구현 in api/app/services/auth.py
  - 영역: BE
  - 담당: jh
  - 선행: T007, T019, T027
  - 검증: 성공 1건·경쟁 요청 거절·기기 불일치 격리·발급 시점부터 30일 만료와 request ID·rotation log test 통과
- [x] T031 [US2] `POST /auth/token/refresh` endpoint와 오류 mapping 구현 in api/app/api/v1/auth.py
  - 영역: BE
  - 담당: jh
  - 선행: T020, T026, T030
  - 검증: US2 contract/integration test 전체 통과 및 Token 원문 log 미노출 확인
- [ ] T032 [P] [US2] Android Refresh API DTO와 호출 구현 in android/app/src/main/java/com/gilpick/auth/AuthApi.kt
  - 영역: FE
  - 담당: jy
  - 선행: T022, T028
  - 검증: MockWebServer로 정상·만료·무효·기기 불일치 parsing test 통과
- [ ] T033 [US2] single-flight refresh·Token 원자 교체·원 요청 최대 1회 replay 구현 in android/app/src/main/java/com/gilpick/auth/AuthRepository.kt
  - 영역: FE
  - 담당: jy
  - 선행: T024, T028, T032
  - 검증: 동시 waiter 공유, timeout session 보존, 확인된 무효 시 삭제와 replay loop 방지 unit test 통과
- [ ] T034 [US2] RefreshOffline·재시도·재로그인 Android UI 상태 연결 in android/app/src/main/java/com/gilpick/auth/AuthViewModel.kt, android/app/src/main/java/com/gilpick/auth/AuthUiState.kt
  - 영역: FE
  - 담당: jy
  - 선행: T025, T029, T033
  - 검증: US2 Android unit/instrumented test 전체 통과

**Checkpoint**: User Story 2는 seeded session만으로 로그인 기능과 분리해 독립 검증할 수 있다.

---

## Phase 5: User Story 3 - 현재 기기에서 로그아웃 (Priority: P3)

**Goal**: 현재 기기만 즉시 local logout하고 서버 폐기는 멱등 처리하며, offline 실패는 요청별 WorkManager queue로 재시도한다.

**Independent Test**: 두 seeded 기기 session 중 한 기기만 logout하고, offline·process restart·재로그인 후 복수 폐기 요청에서도 다른 기기와 새 session이 유지되는지 검증한다.

### Tests for User Story 3

- [x] T035 [P] [US3] Logout endpoint OpenAPI contract test 추가 in api/tests/contract/test_auth_contract.py
  - 영역: BE
  - 담당: jh
  - 선행: T026
  - 검증: 구현 전 실패하고 멱등 `204`, `401/403/500`, error request ID와 Bearer 없는 durable retry 계약을 검사
- [x] T036 [P] [US3] 현재 기기 격리·반복 logout integration test 추가 in api/tests/integration/test_auth_flow.py
  - 영역: BE
  - 담당: jh
  - 선행: T027
  - 검증: 구현 전 실패하고 다른 기기 row 불변, 같은 hash/device 반복 `204`, 잘못된 device/hash 거절을 검사
- [ ] T037 [P] [US3] pending revocation queue와 WorkManager 결과 정책 unit test 작성 in android/app/src/test/java/com/gilpick/auth/SessionRevocationWorkerTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009, T012
  - 검증: 구현 전 실패하고 operation ID별 격리, Token input/output 미포함, terminal/retryable 오류 분류를 검사
- [ ] T038 [P] [US3] 즉시 local logout·재부팅·복수 폐기 Android integration test 작성 in android/app/src/androidTest/java/com/gilpick/auth/AuthLogoutIntegrationTest.kt
  - 영역: FE
  - 담당: jy
  - 선행: T034
  - 검증: 구현 전 실패하고 offline logout A 후 재로그인·logout B의 worker 충돌 없음과 다른 기기 유지 조건 포함

### Implementation for User Story 3

- [x] T039 [US3] Refresh hash·device 기반 멱등 현재 기기 logout service 구현 in api/app/services/auth.py
  - 영역: BE
  - 담당: jh
  - 선행: T030, T036
  - 검증: active→revoked, 동일 자격 반복 성공, 다른 device/hash 격리와 request ID·revoke log integration test 통과
- [x] T040 [US3] Bearer 없이 durable retry 가능한 `POST /auth/logout` endpoint 구현 in api/app/api/v1/auth.py
  - 영역: BE
  - 담당: jh
  - 선행: T031, T035, T039
  - 검증: US3 contract/integration test 전체 통과 및 다른 session 미변경 확인
- [ ] T041 [P] [US3] 요청별 암호화 pending revocation queue와 unique WorkManager 구현 in android/app/src/main/java/com/gilpick/auth/AuthSessionStore.kt, android/app/src/main/java/com/gilpick/auth/SessionRevocationWorker.kt
  - 영역: FE
  - 담당: jy
  - 선행: T009, T037
  - 검증: network/timeout/429/5xx만 backoff 재시도하고 무효·만료는 성공 동등, 기기 불일치는 terminal 처리하는 unit test 통과
- [ ] T042 [US3] 즉시 local logout·로그인 화면 전환·폐기 enqueue 연결 구현 in android/app/src/main/java/com/gilpick/auth/AuthRepository.kt, android/app/src/main/java/com/gilpick/auth/AuthViewModel.kt
  - 영역: FE
  - 담당: jy
  - 선행: T033, T034, T038, T041
  - 검증: logout 직후 보호 화면 차단, process restart 재시도와 복수 operation 격리 Android test 통과

**Checkpoint**: 세 User Story가 모두 독립 검증 가능하며 현재 기기 logout이 다른 기기 session에 영향을 주지 않는다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 성능, 전체 보안 및 종단간 증거를 검증한다.

- [ ] T043 인증 endpoint 동시성·p95·100개 session Refresh smoke test 구현 in api/tests/integration/test_auth_load.py
  - 영역: BE
  - 담당: jh
  - 선행: T020, T031, T040
  - 검증: Kakao mock 기준 동시 100건 무결성 오류 없음, JSON 인증 endpoint p95 500ms 이하, 서로 다른 유효 session 100건 Refresh 전부 성공, 같은 Token 경쟁 성공 update 1건 확인
- [ ] T044 [P] Backend migration·unit·contract·integration·문서화 검증 실행 according to specs/001-kakao-auth/quickstart.md
  - 영역: BE
  - 담당: jh
  - 선행: T021, T040, T043
  - 검증: quickstart Backend 명령, request ID·상태 전이 log와 30일 log 보존 설정, 필수 Google-style docstring 확인 및 미실행 항목·이유 기록
- [ ] T045 [P] Android unit·instrumented·실제 App Link·secure storage·반복 시간 검증 실행 according to specs/001-kakao-auth/quickstart.md
  - 영역: FE
  - 담당: jy
  - 선행: T025, T034, T042
  - 검증: API 26 기능 test, API 31+ 실제 domain의 debug·release App Link, backup·DataStore·WorkManager 평문 Token 0건, 필수 KDoc, mock App Link→빈 목록 shell 20회 중 19회 이상 10초 이내 확인
- [ ] T046 Kakao test account 종단간 로그인·갱신·현재 기기 logout 검증 및 PR 증거 기록 according to specs/001-kakao-auth/quickstart.md
  - 영역: 통합
  - 담당: jy
  - 선행: T044, T045
  - 검증: 실제 Kakao test 계정 신규·기존·다중 기기 login 1회 이상, refresh, offline logout 흐름 통과와 Backend `jh`·Frontend `jy` 계약 영향 확인 기록

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 즉시 시작 가능하며 T001, T002, T003은 서로 다른 파일에서 병렬 가능하다.
- **Phase 2 Foundational**: 해당 platform Setup에 의존하며 각 User Story의 같은 영역 task를 차단한다. 첫 story task의 보조 선행 메타데이터가 Issue dependency 기준이다.
- **Phase 3 US1**: Foundational 완료 후 시작하며 권장 MVP 범위다.
- **Phase 4 US2**: seeded `DeviceSession`으로 독립 test할 수 있지만 공용 `auth.py`·`AuthRepository.kt` 수정 순서상 US1 구현 task 뒤에 실행한다.
- **Phase 5 US3**: seeded 복수 session으로 독립 test할 수 있지만 같은 공용 파일 수정 순서상 US2 구현 task 뒤에 실행한다.
- **Phase 6 Polish**: 선택한 모든 User Story 구현 완료 후 실행한다.

### User Story Dependency Graph

```text
Setup ──> Foundational ──> US1 (P1, MVP) ──> US2 (P2) ──> US3 (P3) ──> Polish
                          │                 │
                          └─ BE/FE 병렬 ────┴─ 각 story는 seeded 상태로 독립 test 가능
```

### Within Each User Story

- Test task를 먼저 작성하고 구현 전 실패를 확인한다.
- Backend는 model/security → service → endpoint 순서로 진행한다.
- Frontend는 API/store → repository → ViewModel/UI 순서로 진행한다.
- Backend와 Frontend는 서로 다른 파일을 소유하므로 명시된 contract를 기준으로 병렬 진행할 수 있다.
- 같은 `api/app/services/auth.py`, `api/app/api/v1/auth.py`, `AuthApi.kt`, `AuthRepository.kt`, `AuthViewModel.kt`를 수정하는 story task는 선행 ID 순서대로 실행한다.

### Parallel Opportunities

- Setup: T001, T002, T003
- Foundational: T004·T005·T008 병렬 후 T004→T007, Android는 T009·T011 병렬 후 T009→T010
- US1 tests: T013, T014, T015, T016, T017
- US1 implementation tracks: Backend T018→T019→T020→T021과 Frontend T022·T023→T024→T025
- US2 tests: T026, T027, T028, T029
- US2 implementation tracks: Backend T030→T031과 Frontend T032→T033→T034
- US3 tests: T035, T036, T037, T038
- US3 implementation tracks: Backend T039→T040과 Frontend T041→T042
- Final validation: T044와 T045

---

## Parallel Examples

### User Story 1

```text
jh: T013 + T014 + T015 test 작성 후 T018 → T019 → T020 → T021
jy: T016 + T017 test 작성 후 T022 + T023 → T024 → T025
```

### User Story 2

```text
jh: T026 + T027 → T030 → T031
jy: T028 + T029 → T032 → T033 → T034
```

### User Story 3

```text
jh: T035 + T036 → T039 → T040
jy: T037 + T038 → T041 → T042
```

---

## Implementation Strategy

### MVP First: User Story 1

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료
3. Phase 3 US1의 Backend와 Frontend track 병렬 구현
4. US1 test와 정상 Kakao login을 독립 검증
5. 빈 여행 목록 shell 진입이 가능하면 MVP 인증 increment로 review

### Incremental Delivery

1. Setup + Foundational → 공통 인증 기반
2. US1 → Kakao 로그인 가능
3. US2 → 재로그인 없는 session 갱신 가능
4. US3 → 현재 기기 logout과 offline 폐기 가능
5. Polish → 성능·전체 E2E 증거 완료

### Assignment Strategy

- **Backend (`jh`)**: `api/`, PostgreSQL migration, Docker와 Backend test를 소유한다.
- **Frontend Android (`jy`)**: `android/`, App Link, secure session, WorkManager, Android test를 소유한다.
- **통합 검증 (`jy`, T046)**: Android 사용자 흐름을 기준으로 실행하고 Backend 계약 확인은 `jh`가 함께 기록한다.

---

## Notes

- `[P]`는 선행 task 완료 후 서로 다른 파일을 수정하는 경우에만 사용한다.
- 각 task의 담당과 선행 관계는 `$speckit-taskstoissues` 이후 실제 GitHub Issue assignee와 dependency에 반영한다.
- API·DB·인증 계약 변경은 Backend `jh`와 Frontend `jy`가 모두 영향 범위를 확인한다.
- 실행하지 않은 test는 통과로 표시하지 않고 이유를 PR에 기록한다.
- 범용 OAuth abstraction, Redis, Token blacklist, 별도 message queue와 모든 기기 logout은 이 Feature 범위 밖이다.
