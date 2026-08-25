# App Link assetlinks.json

`https://$ANDROID_APP_LINK_HOST/.well-known/assetlinks.json`로 배포한다.

## 배포 전 준비

1. debug·release 서명 인증서의 SHA-256 fingerprint를 확인한다.

   ```powershell
   keytool -list -v -keystore <keystore> -alias <alias>
   ```

2. `sha256_cert_fingerprints`의 placeholder를 실제 fingerprint로 교체한다.
   실제 fingerprint는 공모전 배포 인증서에 종속되므로 이 저장소에 commit하지 않는다.
3. 실제 host의 `/.well-known/assetlinks.json`에 `Content-Type: application/json`으로
   배포한다. redirect 없이 200을 반환해야 한다.

## 검증

```powershell
adb shell pm verify-app-links --re-verify com.gilpick
adb shell pm get-app-links com.gilpick
```

`/auth/kakao/complete`만 verified 상태여야 하고, API callback path
`/api/v1/auth/kakao/callback`은 앱이 claim하지 않아야 한다.
