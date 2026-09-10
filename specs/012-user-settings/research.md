# Phase 0 Research: 사용자 설정

## 1. 설정 저장 구조

- **Decision**: 기존 `users.replacement_suggestion_enabled boolean NOT NULL DEFAULT true`를 그대로 사용한다.
- **Rationale**: MVP 사용자 설정은 boolean 하나뿐이고 이미 F011 알림 생성 경로가 이 값을 읽는다. 별도 table이나 migration은 기능적 이득 없이 join과 동기화 비용만 만든다.
- **Alternatives considered**: `user_preferences` table, JSON settings column. 둘 다 다수 설정 확장을 미리 가정하므로 제외한다.

## 2. 설정 API와 소유권

- **Decision**: 인증된 principal에 대해 `GET /api/v1/users/me/preferences`와 `PATCH /api/v1/users/me/preferences`를 제공하고 사용자 ID path parameter는 받지 않는다.
- **Rationale**: 기존 `docs/design/api-spec.md`의 PREF 계약과 일치하고 다른 사용자 설정 접근 가능성을 구조적으로 제거한다.
- **Alternatives considered**: `/users/{userId}/preferences`, 전체 사용자 수정 API. 권한 경계가 넓고 F012 범위를 벗어나 제외한다.

## 3. 동시 변경과 저장 방식

- **Decision**: PATCH가 boolean 절대값을 받아 단일 `UPDATE ... RETURNING`으로 저장·응답하며, DB가 마지막으로 성공 처리한 요청의 값을 최종값으로 사용한다. ETag·version·idempotency key는 추가하지 않는다.
- **Rationale**: 토글 명령이 아니라 원하는 최종값을 전달하므로 같은 요청 재시도는 자연스럽게 멱등하다. 단일 컬럼 갱신에 version 충돌 해결 UI를 추가하는 것보다 명세의 last-successful-write 정책이 단순하다.
- **Alternatives considered**: boolean 반전 endpoint, optimistic locking, 별도 순번. 반전은 재시도에 취약하고 나머지는 사용자 선택 두 값에 과도하다.

## 4. Android 연속 입력 처리

- **Decision**: ViewModel은 한 요청만 실행하고 저장 중 입력은 마지막 희망값 하나로 합친다. 성공 후 희망값이 다르면 다음 PATCH를 보내며, 실패하면 마지막 확인값으로 복원하고 추가 요청을 멈춘다.
- **Rationale**: 중복 요청을 막으면서도 늦게 끝난 이전 응답이 최신 선택을 덮지 않게 하고, 빠른 연속 선택의 최종 의도를 보존한다.
- **Alternatives considered**: 저장 중 Switch 비활성화만 적용, 모든 입력을 병렬 전송, debounce만 적용. 비활성화는 최종 입력을 잃고 병렬 처리는 응답 순서 역전 위험이 있으며 debounce만으로 서버 처리 중첩을 막지 못한다.

## 5. Android 설정 사본

- **Decision**: 설정을 DataStore 등에 별도로 영구 저장하지 않고 서버 응답을 정본으로 삼는다. 화면 수명 동안 마지막 성공값만 상태에 유지한다.
- **Rationale**: 같은 계정의 여러 기기 변경을 다음 조회에 반영해야 하며 별도 cache 정합화 규칙이 필요 없다.
- **Alternatives considered**: 로컬 선반영 후 background sync, 장기 cache. offline 설정 변경은 명세에 없고 충돌·rollback 복잡성만 늘어나 제외한다.

## 6. 정책 문서 표시

- **Decision**: 승인된 non-blank HTTPS URL을 BuildConfig로 주입하고 AndroidX Browser `CustomTabsIntent`로 연다. 잘못된 scheme·빈 URL·Custom Tab 실행 실패는 설정 화면에 재시도 가능한 오류로 표시한다. 실행 뒤의 network·HTTP·문서 로딩 오류는 앱이 관찰하지 않고 브라우저가 표시한다.
- **Rationale**: 인증에서 이미 사용 중인 플랫폼 방식과 dependency를 재사용하며, 별도 WebView 보안 설정이나 정책 전용 화면이 필요 없다. Custom Tabs가 제공하지 않는 문서 로딩 callback을 만들지 않는다.
- **Alternatives considered**: WebView, 앱 내 HTML, 외부 브라우저 강제. 앞의 두 방식은 범위와 보안 부담이 커지고 외부 브라우저 강제는 앱 내 브라우저 요구를 만족하지 못한다.

## 7. 계정·앱 정보와 로그아웃

- **Decision**: 닉네임·프로필 URL은 F001 session, 버전은 `BuildConfig.VERSION_NAME`을 사용한다. MVP 인증 provider가 카카오 하나뿐이므로 인증된 session이 존재하면 카카오 연동 상태로 표시한다. 로그아웃은 기존 `AuthRepository.logout()`/`AuthViewModel` 흐름을 호출한다.
- **Rationale**: 이미 확인된 정보를 재사용해 별도 사용자 조회 API와 중복 session 상태를 만들지 않는다.
- **Alternatives considered**: F012 전용 profile API, 설정 ViewModel에서 Token/session 직접 삭제. 전자는 범위 밖이고 후자는 F001의 오프라인 폐기·다른 기기 유지 규칙을 깨뜨린다.

## 8. UI 정본과 최상위 탐색

- **Decision**: 구현 전에 Figma 원본과 `SettingsScreen.tsx`에서 다중 토글·slider·초기화를 제거하고 단일 장소 변경 제안 알림 토글로 동기화한다. F012는 Settings destination과 진입 상태만 추가하고 진행 중 여행 선택 규칙은 소유하지 않는다.
- **Rationale**: 현재 Figma와 확정 명세가 충돌하며, 저장소 규칙상 Figma가 모양의 정본이다. 진행 여행 선택은 별도 F002 중복 기간 정책 구현과 연결되어 F012에 섞으면 Issue 경계가 깨진다.
- **Alternatives considered**: 현재 Figma를 그대로 구현, F012에서 진행 여행 중복 정책까지 구현. 각각 FR-001과 별도 PR 범위를 위반한다.

## 9. 상태와 접근성 검증

- **Decision**: 설정 값에 loading/content/error를 적용하고 empty는 두지 않는다. 정책·로그아웃은 설정 통신 실패와 독립적으로 유지한다. 48dp touch target, 8dp 간격, 상태의 문구 병기, scroll/inset, 최대 글자 크기와 360dp 검증을 수행한다.
- **Rationale**: 명세 UI-003~UI-007과 `ui-guidelines.md`의 최저선을 직접 추적한다.
- **Alternatives considered**: 화면 전체 loading/error, empty placeholder. 단일 설정 실패가 독립 행동을 차단하고 존재할 수 없는 상태를 추가하므로 제외한다.

## 10. 관찰 가능성과 개인정보

- **Decision**: 기존 request ID 기반 구조화 log에 endpoint, 결과, error code, 처리 시간을 남기되 Token, user ID, nickname, profile URL, 정책 URL과 설정 boolean 값은 남기지 않는다. 별도 감사 table은 만들지 않는다.
- **Rationale**: 요청 단위 장애 추적에는 충분하면서 최소 데이터 원칙을 지킨다. 설정의 현재값은 `users` row가 정본이다.
- **Alternatives considered**: before/after 값 감사 이력, 사용자 식별자 log. F012의 운영 요구에 비해 개인정보와 보존 정책 부담이 커 제외한다.
