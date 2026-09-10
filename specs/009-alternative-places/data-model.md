# Data Model: 대체 장소 추천

**Date**: 2026-09-09 | **Spec**: [spec.md](spec.md) | **Contracts**: [contracts/alternatives.openapi.yaml](contracts/alternatives.openapi.yaml)

F009는 **새 테이블과 migration이 없다**. 후보는 요청마다 계산하는 일시 데이터이고(`er-schema.md` 8.1 "`alternative_candidates` 테이블을 만들지 않는다"), 유일한 영속 변경은 `detections.status`의 `ACTIVE → DISMISSED` 전이다.

## 1. 영속 데이터(기존 테이블 재사용)

### 1.1 `detections` (F008, 읽기 + 상태 전이 1종)

| 컬럼 | F009 사용 |
|---|---|
| `detection_id`·`trip_day_id`·`item_id` | 후보 조회의 기준과 소유권 검증(`detection → trip_day → trip → user`) |
| `status` | `ACTIVE`일 때만 ALT-001·ALT-002 허용. DETECT-004가 `ACTIVE → DISMISSED`로 바꾼다 |
| `eta` | 후보의 혼잡·날씨·운영시간 판정 기준 시각 |
| `reason`·`primary_type`·`evaluation_snapshot` | 화면 상단 감지 요약(DETECT-002 그대로)·배너 문구(DETECT-001 확장 `eta`·`reason`) |
| `resolved_at` | DETECT-004 거절 시각으로 기록(ERD "처리 완료 시각" 재사용, 컬럼 추가 없음) |

상태 전이(F009가 일으키는 것만):

```text
ACTIVE --DETECT-004 dismiss--> DISMISSED   (resolved_at = now, 멱등: 비-ACTIVE는 무변경)
```

`RESOLVED`(F010 승인)·`INVALIDATED`(F008 종료)는 F009가 만들지 않는다. 거절 후 같은 장소의 재감지 여부는 F008 `fingerprint` 규칙(ACTIVE partial unique)에 따르며 F009는 정의하지 않는다.

F010 되돌리기는 `RESOLVED → ACTIVE`로 원래 감지를 후보 조회 대상에 복귀시킨다. 같은 `fingerprint`의 새 `ACTIVE` 감지가 이미 있으면 원래 행은 `RESOLVED → INVALIDATED`가 되고 새 행이 후보 조회 대상으로 남는다. F009는 이 전이를 수행하지 않고 결과 상태만 읽는다.

### 1.2 읽기만 하는 테이블

| 테이블 | 용도 |
|---|---|
| `itinerary_items` + `places` | 기존 장소(좌표·`category`·`tour_category_1~3`·`tour_content_id`·`google_place_id`·`name`·`address`), 같은 날짜 일정의 `place_id` 집합(후보 제외·`inSchedule`) |
| `trip_days`·`trips` | 소유권, 날짜 |
| 혼잡 지원지역 설정(`congestion_areas.json`) | 후보 좌표 500m 판정(F008 `find_nearest_congestion_area`) |

## 2. 일시 데이터(응답 전용, 저장하지 않음)

### 2.1 후보 조회 결과 `AlternativeListData`

| 필드 | 타입 | 규칙 |
|---|---|---|
| `detectionId`·`originPlaceId`·`eta` | uuid·string·datetime | 기준 감지와 기존 장소 |
| `searchRadiusMeters` | 500·1000·2000 | 후보를 찾은(또는 마지막으로 시도한) 반경 |
| `categoryMatchLevel` | SMALL·MIDDLE·LARGE·NONE | 후보를 찾은 분류 비교 단계, 후보 없음이면 NONE |
| `evaluatedAt` | datetime | 서버 평가 시각(= `candidateId` 발급 시각) |
| `items` | `AlternativeCandidate[]` ≤ 10 | 점수 내림차순 |

### 2.2 대체 후보 `AlternativeCandidate`

| 필드 | 규칙 |
|---|---|
| `rank` | 1부터, 정렬 순 |
| `candidateId` | HMAC 서명 토큰 `base64url(payload).base64url(sig)`, payload=`{d,p,t}`; 15분 |
| `place` | F003 `PlaceSummary` 그대로(TourAPI 기준, 확정 매칭 시 Google 평점·영업 필드 병합) |
| `distanceMeters` | 기존 장소 좌표와의 haversine(정수 m) |
| `adjustedRating` | 베이지안 보정 평점, 평점·리뷰 수 없으면 null |
| `score` / `displayScore` | 0~100 실수 / 반올림 정수 |
| `scoreBreakdown` | 사용된 변수만(distance·rating·congestion·weather, 각 0~1) |
| `operatingStatus`·`closesAt` | OPEN·CLOSING_SOON·UNKNOWN(+closesAt); CLOSED는 제외되어 목록에 없다 |
| `reasons` | 근거 코드 배열(INDOOR·NOT_CROWDED·NO_RAIN_RISK·CLOSER·OPEN_AT_ETA 등). 화면이 `AlternativeLabels`로 문구화 |

불변식: 같은 응답 안에서 `place.placeId` 중복 없음, 기존 장소·같은 날짜 일정 장소 없음, `operatingStatus != CLOSED`.

### 2.3 직접 검색 항목 `AlternativeSearchItem`

| 필드 | 규칙 |
|---|---|
| `place` | F003 `PlaceSummary` |
| `distanceMeters` | 좌표 없으면 null |
| `operatingStatus` | `businessStatus`만으로: CLOSED_TEMPORARILY·CLOSED_PERMANENTLY → CLOSED, OPERATIONAL → OPEN, 없음 → UNKNOWN |
| `visitable` | `operatingStatus != CLOSED && !inSchedule` |
| `inSchedule` | 기존 장소 자신 또는 같은 날짜 일정의 장소 |

### 2.4 후보 식별자 claims `CandidateClaims`

| 필드 | 설명 |
|---|---|
| `detection_id` | 발급 기준 감지 |
| `place_id` | `tourapi:…` 또는 `google:…` |
| `evaluated_at` | epoch 초. `now - evaluated_at > 15분`이면 만료 |

검증 실패(서명 불일치·만료·감지 불일치)는 F010이 `400 INVALID_CANDIDATE`로 처리한다(F010 계약에서 확정).

## 3. Android 상태 모델

### 3.1 `AlternativeUiState`

```text
Loading
Error(error: AlternativeError, retryable)          // 추천 실패·네트워크·세션 만료
Closed(status)                                     // 409 DETECTION_NOT_ACTIVE: 이미 처리된 감지
Content(
  detection: DetectionDetailDto,                   // DETECT-002
  candidates: AlternativeListData,                 // ALT-001 (items 비어 있으면 empty 표현)
  refreshing: Boolean,                             // 재조회 중 기존 내용 유지
  dismissPending: Boolean, dismissError: AlternativeError?
)
```

- `Loading`은 1초 지연 후에만 표시(ui-guidelines 9절).
- 상세와 후보는 병렬 조회. 상세 실패 = `Error`, 후보만 실패 = `Error`(후보가 핵심 결과). 409는 `Closed`.
- 거절 성공 → 화면 종료 콜백(`onDismissed`), 실패 → `dismissError` 표시 후 화면 유지.

### 3.2 `SelectedAlternative` (F010 전달 값)

`detectionId`, `placeId`, `candidateId?`(직접 검색이면 null), `name`, `distanceMeters?`, `displayScore?`. `alternativeGraph(onSelectPlace)` 콜백 인자.

### 3.3 진행 화면 배너 (`ProgressUiState.Content` 확장)

| 필드 | 규칙 |
|---|---|
| `activeDetections: List<DetectionListItemDto>` | DETECT-001 `status=ACTIVE` 결과. 조회 실패 시 빈 목록(배너 숨김) |
| `bannerDetection` | 오늘 날짜(`isToday`)이고 당일이 완료되지 않았을 때 `eta` 가장 이른 항목 하나, 없으면 null(Figma 배너는 단일 항목) |

## 4. 계약 문서 동기화 대상

- `docs/design/api-spec.md`: 2절 구현 현황(ALT-001·ALT-002 [x] 시점, DETECT-004 행 추가), 7절 DETECT-001 `status` 필터·항목 `eta`·`reason`, DETECT-004 신설, ALT-001·ALT-002 예시를 `place` 중첩 구조·`candidateId`·`operatingStatus`·`closesAt`·`categoryMatchLevel`·`reasons`·`inSchedule`로 갱신.
- `docs/design/er-schema.md` 8.1: `resolved_at`이 DISMISSED 시각도 담는다는 설명, 13절 API 매핑에 DETECT-004·ALT-001·ALT-002 추가.
- `docs/planning/requirements.md`·`functional-spec.md` 5절: 운영 상태 확인 순서(점수 상위부터 10개), 카테고리·반경 결합 순서, 거절 결정을 반영.
