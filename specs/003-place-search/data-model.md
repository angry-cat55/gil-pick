# Data Model: 장소 검색

F003은 영구 entity나 DB migration을 만들지 않는다. 아래 모델은 TourAPI 기준 정보와 제한적인 Google Places 보완 정보를 전달하는 transient read model이다.

## 1. `PlaceSearchCriteria`

| Field | Type | Required | Rule |
|---|---|---:|---|
| `query` | string | 조건부 | trim 후 2글자 이상. 없으면 위도·경도 쌍 필수 |
| `category` | `PlaceCategory` | 아니오 | 키워드·주변 검색 모두에 적용 가능 |
| `area_code` | string | 아니오 | 서울 코드 `1`만 허용하며 생략해도 서버가 서울 제한을 적용. 그 외 값은 `400 INVALID_REQUEST` |
| `latitude` | number | 조건부 | -90~90. 검색어가 없으면 `longitude`와 함께 필수 |
| `longitude` | number | 조건부 | -180~180. 검색어가 없으면 `latitude`와 함께 필수 |
| `radius_meters` | integer | 아니오 | 1~20000, 기본 5000. 자동 확장하지 않음 |
| `limit` | integer | 아니오 | 1~20, 기본 20 |
| `cursor` | opaque string | 아니오 | 같은 criteria에서 발급된 다음 cursor만 허용 |

`query`가 있으면 기존 키워드 검색을 사용한다. `query`가 없으면 좌표 쌍으로 주변 검색을 사용하며 `category`는 두 모드 모두에서 결과를 좁힌다.

## 2. `PlaceCategory`

| Enum | 표시명 | 추천 체류시간 |
|---|---|---:|
| `NATURE` | 자연 | 120분 |
| `HISTORY_CULTURE` | 문화·역사 | 90분 |
| `FOOD` | 음식 | 60분 |
| `CAFE` | 카페 | 60분 |
| `SHOPPING` | 쇼핑 | 90분 |
| `OTHER` | 기타 | 60분 |

TourAPI 신분류 대·중·소 code는 server의 versioned mapping으로 위 enum에 변환한다. 미매핑 code는 `OTHER`다.

## 3. `TourApiCategory`

| Field | Type | Nullable | Meaning |
|---|---|---:|---|
| `large` | string | 예 | TourAPI 대분류 code |
| `middle` | string | 예 | TourAPI 중분류 code |
| `small` | string | 예 | TourAPI 소분류 code |

## 4. `PlaceSearchResult`

| Field | Type | Nullable | Rule |
|---|---|---:|---|
| `place_id` | string | 아니오 | `tourapi:{contentId}` 또는 `google:{placeId}` |
| `source` | `TOUR_API \| GOOGLE_PLACES` | 아니오 | Backend 병합·상세 routing용이며 화면 배지로 표시하지 않음 |
| `source_place_id` | string | 아니오 | 기준 provider의 원본 ID |
| `name` | string | 아니오 | 빈 값이면 해당 provider item 제외 |
| `category` | `PlaceCategory` | 아니오 | 내부 mapping 결과 |
| `tour_api_category` | `TourApiCategory` | 예 | 응답 필드는 필수이며 값은 nullable. TourAPI 기준 결과만 원본 code 보존 |
| `address` | string | 예 | 빈 provider 값은 null |
| `latitude` | decimal | 예 | -90~90. 두 좌표 중 하나만 유효하면 둘 다 null |
| `longitude` | decimal | 예 | -180~180. 두 좌표 중 하나만 유효하면 둘 다 null |
| `image_url` | HTTPS URL | 예 | HTTP는 검증된 동일 host HTTPS로만 normalize; 그 외 invalid는 null |
| `recommended_stay_minutes` | integer | 아니오 | 내부 category mapping 값 |
| `rating` | decimal | 예 | 확정 매칭 또는 Google 전용 결과의 Google 평점 |
| `user_rating_count` | integer | 예 | Google 평점 수 |
| `business_status` | `OPERATIONAL \| CLOSED_TEMPORARILY \| CLOSED_PERMANENTLY` | 예 | Google 원문 enum을 그대로 전달하고 Android가 표시 문구를 현지화 |
| `open_now` | boolean | 예 | Google `currentOpeningHours.openNow` 원문 값. 미제공·보완 실패·TourAPI 단독이면 null |
| `regular_opening_hours` | string list | 예 | Google 정규 영업시간 표시 문자열 |
| `current_opening_hours` | string list | 예 | Google 현재 기간 영업시간 표시 문자열 |
| `google_attributions` | string list | 예 | Google·제3자 필수 attribution 렌더링 정보 |
| `google_place_id` | string | 예 | Google 전용 결과의 기본 ID 또는 TourAPI 장소와 확정 매칭된 보조 ID. 매칭 실패 시 null |

동일 검색 흐름에서 `place_id`가 같은 item은 Android 누적 목록에 한 번만 존재한다.

## 5. `PlaceDetail`

`PlaceSearchResult`의 모든 field에 다음 nullable field를 추가한다.

| Field | Type | Meaning |
|---|---|---|
| `description` | string | TourAPI 공통 개요. HTML은 safe plain text로 정규화 |
| `phone` | string | provider 표시용 연락처. 전화 가능 여부를 단정하지 않음 |
| `operating_guide` | string | 콘텐츠 유형별 TourAPI 운영 안내를 원문 의미가 유지되게 정규화 |

TourAPI 기준 상세에는 확정 매칭된 `google_place_id`와 Google 평점·영업정보만 선택적으로 추가한다. 공개 기본 식별자는 계속 `tourapi:{contentId}`이고 보조 Google ID는 F004 저장을 거쳐 F008 운영시간 조회에 사용한다. Google 전용 상세는 Google이 허용한 기본정보·평점·영업정보만 사용한다. Google 사진·리뷰는 모델에 없다.

## 6. `PlaceMatch`

| Field | Type | Meaning |
|---|---|---|
| `tour_place_id` | string | `tourapi:{contentId}` |
| `google_place_id` | string | Google place ID |
| `distance_meters` | decimal | 좌표 간 거리 |
| `normalized_name_equal` | boolean | 정규화 장소명 일치 여부 |
| `address_match` | boolean | 주소 일치 여부 |
| `status` | `CONFIRMED \| AMBIGUOUS \| DIFFERENT` | 병합 판정 |

`CONFIRMED`만 병합한다. `AMBIGUOUS` Google 후보는 결과에서 제외하고 판정 근거는 요청 생명주기와 민감정보 없는 진단 log에서만 사용한다.

## 7. `PlaceSearchCursor`

Client에는 opaque string으로만 노출한다. server 내부 payload는 다음 최소값을 서명해 encode한다.

| Field | Meaning |
|---|---|
| `v` | cursor schema version |
| `tour_page_no` | 다음 TourAPI page number |
| `google_page_token` | 상업 카테고리 보완이 시작된 경우에만 다음 Google page token 또는 null |
| `seen_place_ids` | 같은 흐름에서 중복 방지에 필요한 제한된 ID 목록 |
| `criteria_hash` | trim된 query·category·서울 고정 areaCode·위도·경도·반경·limit fingerprint |

서명 오류, version 불일치, 다른 criteria 재사용은 `400 INVALID_CURSOR`다. Cursor에 service key나 검색 결과 원문은 넣지 않는다.

## 8. API Envelope과 오류

검색 성공 envelope의 `meta.pagination`은 필수이며 `nextCursor`는 nullable, `hasNext`는 필수다. 상세·오류 envelope의 `meta`에는 `requestId`만 둔다. 오류의 `retryable`은 항상 포함한다.

| 오류 code | `retryable` | 기준 |
|---|---:|---|
| `INVALID_REQUEST`, `INVALID_CURSOR`, `INVALID_ACCESS_TOKEN`, `PLACE_NOT_FOUND` | `false` | 같은 요청을 반복해도 복구되지 않음 |
| `TOUR_API_RATE_LIMITED`, `GOOGLE_PLACES_RATE_LIMITED` | `false` | 자동·즉시 재시도를 허용하지 않음 |
| `TOUR_API_TIMEOUT`, `GOOGLE_PLACES_TIMEOUT` | `true` | 이후 사용자 재시도로 복구 가능 |
| `TOUR_API_FAILED`, `GOOGLE_PLACES_FAILED` | 조건부 | 일시적 네트워크·5xx의 최종 실패는 `true`; provider application error·응답 해석 실패·그 밖의 4xx는 `false` |

- 검색 Google 보완 실패와 `tourapi:` 상세의 Google 보강 실패는 격리하고 TourAPI 결과를 유지한다.
- `google:` 상세에서만 `GOOGLE_PLACES_RATE_LIMITED`(429, 재시도 불가), `GOOGLE_PLACES_FAILED`(502, 일시적 네트워크·5xx만 재시도 가능), `GOOGLE_PLACES_TIMEOUT`(504, 재시도 가능)을 노출한다.

## 9. UI State

### `PlaceSearchUiState`

- `draftCriteria`: 사용자가 편집 중인 query/category. 지역은 서버에서 서울로 고정한다.
- `committedCriteria`: 마지막으로 실행한 검색 조건
- `items`: `placeId`로 dedupe된 immutable 목록
- `initialLoad`: `Idle | Loading | Empty | LocationUnavailable | Error | Content`
- `appendLoad`: `Idle | Loading | Error`
- `nextCursor`, `hasNext`
- `nearbyOrigin`: 주변 조회와 다음 페이지에 재사용할 현재 좌표
- `validationMessage`: 2글자·좌표 쌍 오류

새 검색 성공 시 `items`를 교체한다. append 성공 시 dedupe해 뒤에 추가하고, append 실패 시 기존 `items`를 유지한다.

### `PlaceDetailUiState`

- `Loading`
- `Content(place)`
- `NotFound`
- `Error(message, retryable)`

## 10. Lifecycle과 저장 정책

- 검색·상세 read model은 request와 화면 destination 생명주기에서만 유지한다.
- 검색 실행만으로 `places` table에 저장하지 않는다.
- Android는 process death 후 전체 검색 결과를 bundle에 저장하지 않는다. query/category와 선택한 place ID 같은 작은 복원 key만 저장하고 필요하면 다시 조회한다. 지역은 서버의 서울 고정 정책을 따른다.
- TourAPI·Google credential, provider 원문 오류와 응답 body는 DB, log, cursor, Android 저장소에 남기지 않는다.
