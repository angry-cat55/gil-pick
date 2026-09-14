# CI (GitHub Actions)

Issue #425. PR과 `main` push에서 Backend·Android 기본 품질 검증을 자동 실행한다. 배포(CD)는 이 workflow들의 범위 밖이며, `docs/deployment/aws-dev.md` 5-1절의 수동 트리거 배포 workflow(Issue #523)를 따로 사용한다.

## 구성

- `.github/workflows/backend-ci.yml`: `api/**` 변경이 있는 PR·`main` push에서만 실행한다.
- `.github/workflows/android-ci.yml`: `android/**` 변경이 있는 PR·`main` push에서만 실행한다.
- 문서(`docs/`, `specs/`)만 바꾼 PR은 두 workflow 모두 트리거되지 않는다.
- 같은 PR·branch에 새 push가 들어오면 이전 실행은 `concurrency` 설정으로 자동 취소된다.

## Backend CI

`ubuntu-latest`에서 `postgis/postgis:18-3.6` service container를 띄우고 다음 순서로 실행한다.

1. `uv sync --frozen`
2. `python -m compileall -q app tests` (문법 검사)
3. `alembic upgrade head`
4. `pytest tests/unit -q`
5. `pytest tests/contract -q`
6. `pytest tests/integration -q`

`tests/smoke`(실제 provider 호출, `RUN_ROUTE_PROVIDER_SMOKE=1` opt-in)는 CI에서 실행하지 않는다. 운영 secret이 없어도 항상 통과해야 하므로 실행하지 않는 것이 맞다.

### 왜 unit·contract·integration을 한 번에 안 돌리나

`pytest tests/ -q`로 전체를 한 번에 돌리면 공유 DB·로깅 핸들러 상태가 섞여 `test_progress_flow.py`·`test_itinerary_flow.py`·`test_api_foundation.py`의 `caplog` 기반 단언 일부가 순서 의존적으로 실패한다(회귀 아님). 디렉터리별로 별도 `pytest` invocation으로 분리하면 이 문제가 재현되지 않는다.

### CI 전용 dummy 환경변수

`Settings`(`api/app/core/config.py`)의 필수 필드(`jwt_signing_secret`, `kakao_rest_api_key` 등)는 workflow의 `env:`에 실제 provider를 호출하지 않는 dummy 값으로 채워져 있다. 이 값들은 secret이 아니라 workflow 파일에 평문으로 있고, `*.gilpick.invalid` 같은 예약 TLD를 사용해 실제 서비스와 절대 겹치지 않게 했다. 운영 API key(TMAP·Kakao Maps·Google Places·TourAPI 등)는 CI에 없으며, 그래서 `tests/smoke`가 CI에서 항상 skip된다.

## Android CI

`ubuntu-latest` + JDK 17(temurin) + `gradle/actions/setup-gradle`로 다음을 실행한다.

1. `./gradlew testDebugUnitTest`
2. `./gradlew assembleDebug`

`connectedAndroidTest`(에뮬레이터 필요)는 CI에 포함하지 않는다 — GitHub 기본 runner에 AVD가 없어 느리고 불안정하다. 필요하면 별도 self-hosted runner나 AVD 설정 Issue로 분리한다.

`app/google-services.json`은 저장소에 없고 CI에도 넣지 않는다. `app/build.gradle.kts`가 파일 존재 여부로 FCM plugin 적용을 조건부 처리하므로, 파일이 없어도 빌드는 성공하고 FCM 관련 코드만 빌드되되 실행 시 초기화되지 않는다. `GILPICK_API_BASE_URL` 등은 `android/gradle.properties`에 CI-safe 기본값이 커밋돼 있어 별도 주입이 필요 없다. Naver Maps client ID가 없어 지도 SDK 초기화 관련 런타임 동작은 CI로 검증되지 않는다(빌드·unit test는 영향 없음).

## 실패 원인 확인

GitHub Actions 탭 → 해당 workflow run → 실패한 job·step에서 로그를 그대로 볼 수 있다. Backend는 실패한 step(문법 검사/migration/unit/contract/integration) 이름으로 어느 단계인지 바로 구분된다. Android unit test 실패는 `android-unit-test-report` artifact(HTML)로 상세 확인할 수 있다.

## 필수 status check 지정

이 PR은 workflow만 추가한다. branch protection에서 `Backend CI`·`Android CI`를 필수 check로 지정할지는 저장소 관리자가 별도로 결정한다.
