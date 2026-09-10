# Quickstart: 사용자 설정 검증

## 1. 사전 조건

- PostgreSQL test DB와 API 환경 변수가 준비되어 있어야 한다.
- Android debug build에 승인된 개인정보처리방침·이용약관 HTTPS URL을 주입해야 한다.
- Figma 원본과 `docs/design/figma-make/src/screens/SettingsScreen.tsx`가 단일 장소 변경 제안 알림 토글로 동기화되어야 한다.
- Backend·Android 계약은 `specs/012-user-settings/contracts/preferences.openapi.yaml`을 기준으로 한다.

정책 URL과 Figma 동기화가 준비되지 않았다면 API 검증은 진행할 수 있지만 정책 문서와 최종 screenshot 종단간 검증은 완료 처리하지 않는다.

## 2. Backend 검증

```powershell
cd api
uv sync --frozen
uv run pytest tests/unit/test_preferences_schema.py tests/unit/test_preferences_service.py
uv run pytest tests/integration/test_preferences_flow.py
uv run pytest tests/unit/test_notification_service.py tests/integration/test_notification_detection_hook.py
```

확인 결과:

1. 인증 사용자의 GET은 기본값 `true` 또는 저장된 값을 반환한다.
2. PATCH `false`/`true`는 응답값과 다음 GET에 동일하게 반영된다.
3. body 누락, `null`, 문자열, 숫자, 알 수 없는 field는 `400` 공통 오류 envelope로 거절된다.
4. Token 누락·무효는 `401`이며 다른 사용자 설정을 지정할 경로가 없다.
5. 같은 값 재요청은 부수효과 없이 같은 값을 반환한다.
6. 겹친 변경 뒤 최종 GET은 DB가 마지막으로 성공 처리한 값을 반환한다.
7. 설정이 꺼졌을 때 새 장소 변경 제안 알림은 만들지 않지만 detection과 도착·출발 확인 알림은 계속 만든다. 다시 켜도 누락분을 소급 생성하지 않는다.

## 3. Android 자동 검증

```powershell
cd android
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

최소 검증 항목:

- 초기 조회 성공·지연 loading·실패 재시도
- PATCH 성공 후 확정값 갱신, 실패 후 마지막 성공값 복원
- 빠른 연속 선택에서 동시 요청은 한 건이며 마지막 희망값이 최종 저장됨
- 늦은 이전 응답이 최신 상태를 덮지 않음
- 설정 저장 중 정책 문서와 로그아웃 행동 유지
- 인증 `401` 처리 시 F001 refresh/replay 규칙 재사용
- session 닉네임·프로필 누락 대체 표시와 `BuildConfig.VERSION_NAME` 표시

## 4. 정책 문서 검증

1. 개인정보처리방침과 이용약관을 각각 선택한다.
2. 각 항목이 서로 다른 승인된 HTTPS 문서를 Custom Tab으로 여는지 확인한다.
3. 뒤로 가기로 설정 화면과 로그인 상태가 유지되는지 확인한다.
4. 빈 URL, HTTP URL, Custom Tab 실행 불가를 각각 자동 test로 재현한다.
5. 앱이 감지 가능한 실패에서는 설정 화면과 로그인 상태가 유지되고 원인과 재시도 행동이 보이는지 확인한다.
6. Custom Tab 실행 뒤 offline·HTTP·문서 로딩 오류는 브라우저가 표시하며 앱 오류 상태 검증 대상이 아님을 확인한다.
7. 정상 네트워크에서 각 정책 문서를 5회 열어 모두 선택 후 3초 이내에 표시되는지 수동 측정한다.

## 5. UI·접근성·adaptive 검증

동기화된 Figma `SettingsScreen`을 기준으로 screenshot을 비교한다.

| 시나리오 | 기대 결과 |
|---|---|
| content / 알림 ON·OFF | 단일 장소 변경 제안 토글만 존재하고 상태를 색 이외 방식으로도 구분 |
| 조회 1초 초과 | 설정 영역만 loading, 정책·로그아웃은 사용 가능 |
| 조회·저장 error | 원인과 재시도 표시, 저장 실패 시 마지막 성공값 유지 |
| 360dp | 가로 scroll·잘림·겹침 없음 |
| phone/tablet 세로·가로 | 모든 항목을 세로 scroll로 접근 가능 |
| 시스템 최대 글자 크기 | 핵심 문구와 상태가 잘리지 않음 |
| system/navigation bar | 고정 탐색과 system 영역이 내용을 가리지 않음 |
| touch target | 모든 대상 48×48dp 이상, 대상 간 8dp 이상 |

## 6. 로그아웃 종단간 검증

1. 같은 계정을 두 기기에 로그인한다.
2. 한 기기의 설정 화면에서 로그아웃한다.
3. 해당 기기가 즉시 로그인 화면으로 이동하는지 확인한다.
4. offline에서도 local session이 즉시 제거되고 서버 폐기 재시도가 예약되는지 확인한다.
5. 다른 기기의 session과 저장된 알림 설정은 유지되는지 확인한다.

## 7. 계약·정적 검증

```powershell
git diff --check
cd api
uv run ruff check app tests
uv run mypy app
```

OpenAPI YAML 파싱, 공통 envelope, `docs/design/api-spec.md`의 PREF-001/PREF-002와 field 이름 일치도 함께 확인한다.
