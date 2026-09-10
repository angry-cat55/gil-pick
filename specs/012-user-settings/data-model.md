# Data Model: 사용자 설정

## 1. 영속 entity

### User

F012는 새 entity를 만들지 않고 기존 `users` row의 한 field를 사용한다.

| Field | Type | Null | Default | 규칙 |
|---|---|---:|---:|---|
| `user_id` | UUID | N | 생성 시 부여 | 인증 principal과 일치하는 row만 조회·변경 |
| `replacement_suggestion_enabled` | boolean | N | `true` | 장소 변경 제안 알림 전체 허용 여부 |
| `nickname` | varchar(80) | Y | 없음 | F001 session 표시 정보의 원천이며 F012에서 수정하지 않음 |
| `profile_image_url` | text | Y | 없음 | F001 session 표시 정보의 원천이며 F012에서 수정하지 않음 |
| `deleted_at` | timestamptz | Y | `null` | 탈퇴하지 않은 인증 사용자만 대상 |
| `updated_at` | timestamptz | N | 서버 시각 | 기존 model 갱신 규칙 적용 |

### 관계와 불변식

- 한 사용자 row에 설정 값 하나만 존재한다.
- API는 path나 body에서 `user_id`를 받지 않고 인증 principal의 ID만 사용한다.
- `replacement_suggestion_enabled=false`는 이후 새 장소 변경 제안 알림 생성을 건너뛰게 하지만 detection과 도착·출발 확인 알림에는 영향을 주지 않는다.
- 다시 `true`가 되어도 비활성 기간에 건너뛴 알림을 소급 생성하지 않는다.
- 여러 요청이 겹치면 DB가 마지막으로 성공 처리한 UPDATE의 값이 정본이다.

### 상태 전이

```text
true  --PATCH false 성공--> false
false --PATCH true 성공---> true
현재값 --동일값 PATCH 성공--> 현재값
현재값 --PATCH 실패--------> 현재값
```

별도 version, 변경 이력, soft-delete 또는 preference row 상태는 없다.

## 2. API representation

| API field | Domain/DB field | Validation |
|---|---|---|
| `placeChangeSuggestionNotificationEnabled` | `users.replacement_suggestion_enabled` | required boolean, `null`·문자열·숫자 거부 |

GET과 PATCH 성공 응답은 저장된 값을 공통 success envelope의 `data`에 반환한다. 알 수 없는 request field는 기존 Pydantic strict request 정책에 따라 거부한다.

## 3. Android 화면 model

### SettingsUiState

| Field | 의미 |
|---|---|
| `nickname` | F001 session 값 또는 대체 표시용 `null` |
| `profileImageUrl` | F001 session 값 또는 기본 avatar용 `null` |
| `isKakaoConnected` | F001 인증 session 존재 여부로 표시하는 MVP 카카오 연동 상태 |
| `versionName` | `BuildConfig.VERSION_NAME` |
| `preferencePhase` | 설정 영역의 loading/content/error 상태 |
| `policyOpenError` | 정책 문서 열기 실패 표시; 평상시는 `null` |

### PreferencePhase

```text
Loading
  ├─ 조회 성공 → Content(lastConfirmedValue, isSaving=false)
  └─ 조회 실패 → Error(lastConfirmedValue=null, retryable=true)

Content(value, false)
  └─ 사용자 선택 → Content(value, true), desiredValue 기록

Content(value, true)
  ├─ 저장 성공 + desiredValue==응답값 → Content(응답값, false)
  ├─ 저장 성공 + desiredValue!=응답값 → 다음 PATCH, Content(응답값, true)
  └─ 저장 실패 → Error(lastConfirmedValue=value, retryable=true)

Error(lastConfirmedValue?, true)
  ├─ 조회 재시도 → Loading
  └─ 변경 재시도 → 마지막 희망값 PATCH
```

- empty 상태는 없다. 인증된 사용자 row에는 NOT NULL 기본 설정이 항상 존재한다.
- 정책 문서 열기와 로그아웃은 `preferencePhase`와 독립적이다.
- logout 성공 상태를 SettingsUiState에 중복 저장하지 않고 F001 인증 상태가 최상위 navigation을 전환한다.

## 4. 비영속 설정

| 이름 | 위치 | 규칙 |
|---|---|---|
| 개인정보처리방침 URL | Android BuildConfig | 승인된 non-blank HTTPS URL |
| 이용약관 URL | Android BuildConfig | 승인된 non-blank HTTPS URL |
| 앱 버전 | Android BuildConfig | `VERSION_NAME` 표시 |

정책 URL이나 문서 본문은 DB에 저장하지 않으며 request log에도 기록하지 않는다.
