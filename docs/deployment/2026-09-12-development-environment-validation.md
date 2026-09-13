# 2026-09-12~13 개발 환경 구축 및 검증 작업 기록

## 1. 작업 개요

Firebase·FCM 준비, Android 서명과 App Link 설정, AWS 개발 서버 구축, F011 자동 검증을 진행했다. 이어서 Android 앱을 배포 API에 연결하고 카카오 로그인과 실제 FCM 전달을 검증했다.

실제 secret, 서비스 계정 JSON 원문, 비밀번호, API Key와 private key는 이 문서와 저장소에 기록하지 않는다.

## 2. Firebase·FCM 준비

- Firebase 프로젝트 생성 및 Spark 요금제 사용
- Firebase에 Android 앱 등록
- `android/app/google-services.json` 배치
- `android/.gitignore`의 `app/google-services.json` 규칙으로 Git 제외 확인
- Firebase Admin SDK 서비스 계정 키 발급
- 서비스 계정 JSON 유효성 및 Firebase 프로젝트 일치 확인
- Google OAuth Token 발급 성공 확인
- Firebase가 포함된 Android debug 빌드 성공

Firebase 프로젝트 ID:

```text
gilpick-85911
```

## 3. Android 서명·App Link

### 3.1 팀 공용 debug 서명

팀 공용 debug keystore를 저장소 밖의 로컬 secret 디렉터리에 보관하고 개인 Gradle 설정에 경로를 등록했다.

```properties
GILPICK_DEBUG_KEYSTORE_PATH=<LOCAL_SECRET_DIR>/debug.keystore
```

```text
Alias: androiddebugkey
SHA-256: 77:AD:5D:EC:26:A4:A0:CB:83:06:15:42:2C:C7:DA:C0:E2:36:AC:C4:6F:12:1D:AE:A3:4F:1E:4D:E7:93:86:BD
```

### 3.2 App Link와 정책 문서

- `https://gilpick.pages.dev/.well-known/assetlinks.json`에 debug 인증서 fingerprint 반영
- 앱 재설치 후 Android App Link 검증 완료
- domain verification 결과: `gilpick.pages.dev: verified`
- `assetlinks.json` HTTP 200 응답 확인
- 개인정보처리방침과 이용약관 주소 준비 및 응답 확인

```properties
GILPICK_PRIVACY_POLICY_URL=https://gilpick.pages.dev/privacy
GILPICK_TERMS_OF_SERVICE_URL=https://gilpick.pages.dev/terms
```

```dotenv
ANDROID_APP_LINK_BASE_URL=https://gilpick.pages.dev/auth/kakao/complete
ANDROID_APP_LINK_HOST=gilpick.pages.dev
```

`ANDROID_APP_LINK_BASE_URL`은 카카오 인증 완료 후 Android 앱으로 복귀시키는 주소이며 API base URL과 용도가 다르다.

## 4. AWS 개발 서버 구축

### 4.1 AWS 계정·비용 안전 설정

- AWS IAM 사용자 생성
- MFA 설정
- 비용 알림 설정

### 4.2 EC2·RDS 구성

- Ubuntu EC2 `t3.micro` 생성
- EC2에 탄력적 IP 연결
- RDS PostgreSQL `t4g.micro` 생성
- `gilpick` 데이터베이스 생성
- PostGIS 확장 설정
- EC2에 Docker 및 Docker Compose 설치

저장소의 배포 구성은 FastAPI와 Caddy를 Docker Compose로 실행한다. Caddy는 80·443 포트를 공개하고 FastAPI container로 요청을 전달하며, API container에는 health check와 `restart: unless-stopped`가 적용된다.

### 4.3 Domain·HTTPS·API 배포

- DuckDNS domain 연결: `gilpick-api.duckdns.org`
- EC2 탄력적 공인 IP 연결 완료(실제 값은 저장소에 기록하지 않음)
- FastAPI와 Caddy container 실행
- DB migration 적용
- Caddy를 통한 HTTPS 인증서 자동 발급 적용
- 외부 health check 성공

```text
GET https://gilpick-api.duckdns.org/health
HTTP 200
{"status":"ok"}
```

외부 HTTPS health 응답을 통해 공개 domain → Caddy → FastAPI health endpoint 경로가 동작하는 것을 확인했다.

### 4.4 작업 관리

- 배포 작업용 GitHub Issue: `#418`
- 배포 코드 작업 브랜치 push 완료

## 5. F011 알림 기능 자동 검증

- T045 전체 자동 검증 완료
- Backend F011 관련 테스트: `61 passed`
- Android 단위 테스트 및 debug 빌드 성공
- Android 알림 계측 테스트: `38 tests, 0 failed`
- 결과를 `specs/011-notification/quickstart.md`에 기록
- 원격 브랜치 push 완료

```text
브랜치: test/ts-notification-validation
커밋: 18ec51b
```

- 완료되지 않은 상태로 잘못 생성했던 PR `#408` 닫음
- F011 상태는 아직 `IN_PROGRESS` 유지

## 6. Android와 배포 API 연결

### 6.1 API base URL 설정

Android 로컬 Gradle 설정에 배포 API 주소를 추가했다.

```properties
GILPICK_API_BASE_URL=https://gilpick-api.duckdns.org/api/v1/
```

정책 문서 URL은 기존 값을 유지했으며, 생성된 Android `BuildConfig`에도 배포 API 주소가 반영된 것을 확인했다.

### 6.2 Android SDK·JDK 설정

`android/local.properties`가 없어 처음 빌드가 실패했고, 로컬 SDK 경로를 설정해 해결했다.

```properties
sdk.dir=<ANDROID_SDK_PATH>
```

`local.properties`는 머신 종속 로컬 설정이므로 Git에 포함하지 않는다.

Gradle이 `jlink.exe`가 없는 VS Code 내장 JRE를 사용한 문제는 설치된 JDK 21을 현재 CMD 세션에 지정하고 Gradle daemon을 중지해 해결했다.

```cmd
set "JAVA_HOME=<JDK_21_PATH>"
set "PATH=%JAVA_HOME%\bin;%PATH%"
.\gradlew.bat --stop
```

### 6.3 앱 빌드·설치·실행

```cmd
cd /d <REPOSITORY_PATH>\android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

- 연결 기기: `emulator-5554`
- 기기 상태: `device`
- `com.gilpick` 앱 설치 및 실행 성공
- 로그인 초기 화면 표시 정상
- 앱 강제 종료 없음
- 확인한 로그에서 DNS, network connection, TLS 관련 예외 없음
- Android 알림 권한 최종 허용 확인
- EC2 공개 HTTPS API를 사용하므로 `adb reverse`는 사용하지 않음

## 7. 카카오 로그인 설정·종단 검증

### 7.1 Kakao Developers 설정

- 일반 카카오 로그인 기능 사용
- 비즈니스 인증과 로그아웃 Redirect URI는 사용하지 않음
- 카카오 로그인 Redirect URI 등록

```text
https://gilpick-api.duckdns.org/api/v1/auth/kakao/callback
```

- Client Secret 생성 및 활성화
- 카카오 API 호출 허용 IP에 EC2 탄력적 IP 등록

```text
<EC2_ELASTIC_IP>
```

- REST API Key와 Client Secret을 EC2의 `/opt/gilpick/.env`에 반영
- API container 재생성 후 health 확인

### 7.2 검증 결과

- 카카오 로그인 transaction 생성 API: HTTP 201
- 본인 카카오 계정으로 실제 로그인 성공
- 카카오 인증 후 verified App Link를 통해 길픽 앱으로 복귀
- 복귀 후 `com.gilpick/.MainActivity` 활성 상태 확인
- `INVALID_LOGIN_TICKET`, `DEVICE_MISMATCH`, `KAKAO_AUTH_FAILED` 오류 없음

카카오 로그인 요청 → Backend callback → App Link 복귀 → 길픽 Token 발급의 실제 종단 흐름이 성공했다.

## 8. 실제 FCM 설정·전달 검증

### 8.1 EC2 환경변수 반영

다음 FCM 설정을 `/opt/gilpick/.env`에 반영하고 API container를 재생성했다.

```dotenv
FCM_ENABLED=true
FCM_PROJECT_ID=gilpick-85911
FCM_SERVICE_ACCOUNT_JSON=<Firebase Admin SDK 서비스 계정 JSON 한 줄>
```

초기 확인에서는 세 값이 모두 `False`였으나 환경 파일 수정과 container 재생성 후 모두 `True`로 확인됐다.

### 8.2 서비스 계정 JSON 오류 해결

최초 입력한 JSON에는 `client_email`이 없어 `FCM_OAUTH_FAILED`가 발생했다. Android용 `google-services.json`이 아니라 Firebase Admin SDK 서비스 계정 JSON을 사용하도록 교체했다.

교체 후 secret을 출력하지 않고 필수 필드와 프로젝트를 검증했다.

```text
필수 필드(client_email, private_key, token_uri): True
project_id: gilpick-85911
```

### 8.3 기기 Token과 실제 수신

- 카카오 로그인 후 DEV-001을 통한 FCM Token 등록 확인
- 활성 상태이며 FCM Token이 있는 기기 수: `1`
- 앱을 background로 전환
- EC2 Backend의 실제 `FcmClient`에서 FCM HTTP v1 data-only 테스트 메시지 발송
- 발송 결과: `OK`
- Android 시스템 알림 `길픽 알림 테스트` 실제 수신
- 수신 화면 screenshot 확보

검증된 전달 경로:

```text
EC2 Backend
→ Firebase OAuth 인증
→ FCM HTTP v1 발송
→ 등록된 기기 Token
→ background 상태 Android
→ 시스템 알림 표시
```

이 단계에서는 FCM 전송·수신 기반만 확인했다. 이후 실제 여행 식별자가 포함된 알림으로 대상 화면 이동까지 추가 검증했다.

### 8.4 도착 확인 알림과 진행 화면 이동

- 당일 진행 중 여행에서 서버의 진행 이벤트 등록 경로로 `DWELL` 상당 이벤트 생성
- 이벤트 처리 결과 `accepted=True` 확인
- background dispatch 뒤 Android 시스템 알림 `도착하셨나요?` 수신
- 알림을 눌러 해당 여행 진행 화면과 도착 확인 sheet 표시 확인
- `네, 도착했어요`를 선택해 일정 진행 상태 갱신 확인

실제 Android geofence 자동 이벤트는 별도로 검증하지 못했다. Emulator log에서 `registration not active, registration not permitted`가 확인돼 서버 이벤트 등록 경로로 알림 이후 흐름을 검증했다.

### 8.5 장소 변경 제안 알림과 대체 장소 이동

- 운영시간 위험 verdict를 사용해 실제 `ACTIVE` 감지와 `PLACE_CHANGE_SUGGESTION` 알림 생성
- `detectionId`와 여행·일정 항목 연결 확인
- 활성 FCM 기기 1개와 `FcmClient` 직접 발송 결과 `OK` 확인
- background Android에서 장소 변경 제안 알림 수신
- 알림을 눌러 해당 `detectionId`의 대체 장소 추천 화면 이동 확인

알림 행에는 `sentAt`이 기록됐지만 최초 background worker 발송 알림은 화면에서 확인하지 못했다. 직접 FCM 발송은 성공했으며 당시 worker의 전달 결과 `INFO` log가 container log에 나타나지 않아 최초 미표시 원인은 확정하지 못했다.

### 8.6 설정·로그아웃 경계 검증

- 장소 변경 제안 알림 설정 OFF가 서버에 `False`로 저장됨을 확인
- rollback 기반 신규 감지 시뮬레이션에서 감지는 생성되고 알림은 생성되지 않음 확인
- 설정 ON 복구 후 OFF 기간의 누락 알림이 소급 발송되지 않음 확인
- 로그아웃 후 발송 가능한 활성 FCM 기기 수가 `0`이 됨을 확인
- 재로그인 후 활성 FCM 기기 수가 `1`로 복구되고 직접 발송 결과 `OK` 확인
- 앱 foreground 상태에서는 새 시스템 알림이 표시되지 않음 확인

## 9. ODsay와 경로 계산 진단

### 9.1 초기 감지 대상 제외

초기 진행 여행은 `estimated_arrival_at`이 없어 F008 감지 대상에서 제외됐다. TRANSIT 경로 계산 실패가 ETA 미생성의 직접 원인이었다.

### 9.2 ODsay 인증 정상화

ODsay LAB에서 Server Key와 허용 IP를 설정하고 EC2 환경변수를 갱신한 뒤 API container를 재생성했다. 이후 `searchPubTransPathT` 직접 호출 결과는 다음과 같았다.

```text
{'pathCount': 25}
```

따라서 ODsay Server Key 인증과 EC2에서의 API 도달은 정상임을 확인했다.

### 9.3 남은 TRANSIT 경로 문제

서로 다른 서울 좌표로 구성한 실제 앱 일정에서도 TRANSIT route는 다음 상태로 실패했다.

```text
status: FAILED
provider: 없음
failure_code: ROUTE_INVALID_RESULT
```

ODsay 원본 응답에는 경로 25개가 있으므로 인증 실패는 아니다. 현재 근거만으로 정확한 원인을 확정할 수 없으며, ODsay 응답을 내부 route 결과로 변환하는 adapter 또는 검증 단계의 별도 버그로 추적해야 한다. F011 알림 검증은 정상 동작하는 CAR 경로로 전환해 계속 진행했다.

## 10. 발생한 주요 오류와 해결

### 해결 완료

| 오류 | 원인 | 해결 |
|---|---|---|
| `SDK location not found` | `android/local.properties` 누락 | 실제 SDK 경로를 `sdk.dir`에 설정 |
| `jlink.exe does not exist` | VS Code 내장 JRE 사용 | `JAVA_HOME`과 `PATH`를 JDK 21로 지정 |
| `adb` 명령을 찾지 못함 | `platform-tools`가 PATH에 없음 | `adb.exe` 전체 경로 사용 |
| FCM 설정 3개가 `False` | 환경 파일 미반영·container 미재생성 | `/opt/gilpick/.env` 수정 후 API 강제 재생성 |
| `FCM_OAUTH_FAILED`, `client_email` 누락 | 잘못된 종류의 Firebase JSON | Admin SDK 서비스 계정 JSON으로 교체 |
| ODsay `ApiKeyAuthFailed` | Server Key와 허용 IP 설정 미반영 | ODsay LAB 설정과 EC2 환경변수 갱신 후 직접 호출 성공 |

### 별도 추적 필요

| 현상 | 확인된 사실 | 영향 |
|---|---|---|
| ODsay `ROUTE_INVALID_RESULT` | 직접 API 호출은 경로 25개를 반환하지만 앱 route 변환은 실패 | TRANSIT ETA와 실제 변수 감지 차단 |
| geofence 등록 불가 | Emulator에서 `registration not active, registration not permitted` 확인 | 실제 위치 기반 자동 `DWELL` 이벤트 미검증 |
| 진행 알림 즉시 발송 transaction 충돌 | `A transaction is already begun on this Session.` 발생 | 즉시 발송 실패 후 background worker에 의존 |
| dispatch 성공 `INFO` log 미표시 | `sentAt`은 기록되지만 container log에서 전달 결과를 확인하지 못함 | 운영 관찰성 저하 |

## 11. 후속 작업

1. ODsay `ROUTE_INVALID_RESULT` 재현 fixture와 adapter 변환 단계를 별도 Issue에서 진단한다.
2. 실제 Android geofence 등록 권한과 등록 상태를 별도 Issue에서 진단한다.
3. 진행 알림 즉시 발송의 session transaction 경계를 별도 Issue에서 수정한다.
4. notification dispatch 성공·실패 log가 운영 container에 출력되도록 logging 설정을 점검한다.
5. F011 Feature 상태와 quickstart task 완료 처리는 F011 소유 작업에서 실제 검증 범위와 미검증 항목을 반영해 결정한다.

## 12. 현재 상태 요약

AWS 개발 인프라, HTTPS API, RDS migration, Android 배포 API 연결, 카카오 실제 로그인, Firebase OAuth·FCM 전달, 알림 설정과 로그인 경계가 동작한다. 도착 확인과 장소 변경 제안 알림은 실제 Android에서 수신했고 각 도메인 화면으로 이동했다.

배포 환경과 FCM 기반은 Issue #418의 완료 조건을 충족한다. ODsay TRANSIT 변환, 실제 geofence 자동 이벤트, 진행 알림 즉시 발송 transaction과 dispatch log 관찰성은 배포 구성과 분리해 후속 버그로 추적한다.
