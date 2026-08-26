# App Link 검증과 `assetlinks.json`

카카오 인증이 끝나고 돌아오는 링크를 브라우저가 아니라 길픽 앱이 받게 하려면, Android가
도메인 소유권을 검증해야 한다. 이 디렉터리의 `assetlinks.json`은 그 검증에 쓰이는 파일의
골격이다.

`https://$ANDROID_APP_LINK_HOST/.well-known/assetlinks.json`로 배포한다.

## 왜 필요한가

API 31 이상은 **검증되지 않은 도메인을 암시적 intent 해석에서 제외한다.** 배포 전에는
manifest에 `autoVerify`를 선언해도 링크가 앱으로 오지 않고 브라우저로 열린다. 현재 상태는
다음 명령으로 확인할 수 있다.

```powershell
adb shell pm get-app-links com.gilpick
```

```text
Domain verification state:
  app.gilpick.example: none      # 배포 전
  실제도메인: verified            # 배포 후 목표 상태
```

## 선행 조건

| 항목 | 담당 | 비고 |
|---|---|---|
| 공모전용 실제 HTTPS 도메인 | `jh`·`jy` 합의 | Backend 배포 도메인과 같은 인프라 |
| `/.well-known/` 경로 서빙 주체 | **미정** | Backend 라우트 또는 별도 정적 호스팅 |
| release keystore 보관 정책 | 팀 합의 | 분실 시 앱 업데이트 불가 |

## 1. 도메인 확정과 환경값 교체

Backend는 시작할 때 두 값의 host가 일치하는지 검사한다.

```text
# api/.env
ANDROID_APP_LINK_HOST=실제도메인
ANDROID_APP_LINK_BASE_URL=https://실제도메인/auth/kakao/complete

# android/gradle.properties
GILPICK_ANDROID_APP_LINK_HOST=실제도메인
```

## 2. release 서명 준비

App Link 검증은 APK 서명 인증서의 SHA-256 fingerprint를 대조하므로 release 서명이 필요하다.

```powershell
keytool -genkeypair -v -keystore gilpick-release.jks `
  -alias gilpick -keyalg RSA -keysize 2048 -validity 10000
```

`android/app/build.gradle.kts`는 아래 네 property가 모두 있을 때만 release 서명을 구성한다.
값이 없는 개발자도 debug 빌드와 test는 그대로 실행할 수 있다.

```text
GILPICK_KEYSTORE_PATH
GILPICK_KEYSTORE_PASSWORD
GILPICK_KEY_ALIAS
GILPICK_KEY_PASSWORD
```

네 값은 `~/.gradle/gradle.properties` 또는 CI 비밀값에 둔다. **저장소의
`gradle.properties`나 keystore 파일 자체를 commit하지 않는다.**

```powershell
.\gradlew.bat assembleRelease `
  -PGILPICK_KEYSTORE_PATH=<경로> -PGILPICK_KEYSTORE_PASSWORD=<비밀번호> `
  -PGILPICK_KEY_ALIAS=<alias> -PGILPICK_KEY_PASSWORD=<비밀번호>
```

## 3. fingerprint 수집

debug keystore는 PC마다 자동 생성되므로 **개발자마다 값이 다르다.** 앱을 기기에 올려 확인할
팀원 전원의 값을 모으거나, 공용 debug keystore를 정해 공유한다.

```powershell
# debug — 각자 자기 PC에서
keytool -list -v -keystore $env:USERPROFILE\.android\debug.keystore `
  -alias androiddebugkey -storepass android

# release — keystore 보관자가
keytool -list -v -keystore gilpick-release.jks -alias gilpick
```

빌드된 APK가 실제로 어떤 인증서로 서명됐는지는 `apksigner`로 확인한다.

```powershell
apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

Google Play App Signing을 사용하면 Play가 앱을 다시 서명하므로, 올려야 할 값은 local
keystore가 아니라 **Play Console이 알려주는 앱 서명 키의 SHA-256**이다. 공모전에 APK를
직접 배포한다면 해당하지 않는다.

## 4. `assetlinks.json` 작성과 배포

`sha256_cert_fingerprints`의 placeholder를 실제 fingerprint로 교체한다. 실제 fingerprint는
배포 인증서에 종속되므로 **이 저장소에 commit하지 않는다.**

배포 후 아래를 모두 만족해야 Android가 파일을 읽는다.

- 경로가 정확히 `/.well-known/assetlinks.json`
- HTTP `200`이고 **redirect가 없다.** Android는 redirect를 따라가지 않는다
- `Content-Type: application/json`
- 유효한 인증서의 HTTPS

```powershell
curl.exe -I https://실제도메인/.well-known/assetlinks.json
```

## 5. 검증

Android는 설치 시점에 검증을 시도한다. 이미 설치된 앱은 재검증을 직접 걸어야 하며,
`assetlinks.json`을 고쳐도 자동으로 다시 검증되지 않는다.

```powershell
adb shell pm verify-app-links --re-verify com.gilpick
adb shell pm get-app-links com.gilpick
```

`실제도메인: verified`가 나오면 링크가 앱으로 열린다. 실제 동작으로 확인한다.

```powershell
adb shell am start -W -a android.intent.action.VIEW `
  -d "https://실제도메인/auth/kakao/complete#loginTicket=test"
```

인증 완료 path는 앱 선택 dialog 없이 길픽으로 열려야 하고, API callback path
`/api/v1/auth/kakao/callback`은 앱이 claim하지 않으므로 브라우저로 열려야 한다.

## 완료 판정

- [ ] `/.well-known/assetlinks.json`이 redirect 없이 `200`으로 응답한다
- [ ] debug 빌드에서 `pm get-app-links`가 `verified`를 보여준다
- [ ] release 빌드에서도 `verified`를 보여준다
- [ ] 인증 완료 path의 `am start`가 dialog 없이 길픽으로 열린다
- [ ] API callback path는 브라우저로 열린다
- [ ] 실제 fingerprint와 keystore가 저장소에 commit되지 않았다

모두 확인되면 `specs/001-kakao-auth/tasks.md`의 T010을 `[x]`로 갱신한다.
