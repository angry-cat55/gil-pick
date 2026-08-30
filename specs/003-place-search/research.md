# Phase 0 Research: 장소 검색

## 1. TourAPI 서비스와 호출 경계

**Decision**: Backend가 한국관광공사 국문 관광정보 서비스 `KorService2`를 HTTPS로 호출하고 Android에는 길픽 `/api/v1/places` 계약만 노출한다. Base URL은 `https://apis.data.go.kr/B551011/KorService2`이며 key는 Backend 환경변수의 `SecretStr`로만 주입한다.

**Rationale**: 공식 공공데이터포털은 `KorService2`가 REST JSON/XML로 검색·상세 기능을 제공한다고 명시한다. Backend proxy는 service key, provider의 가변 field와 오류 code를 Android에서 격리하고 공통 인증·request ID·오류 envelope를 재사용한다.

**Alternatives considered**:

- Android가 TourAPI 직접 호출: APK와 network log에 service key가 노출되고 provider 계약이 Android DTO에 결합되어 제외.
- 범용 관광 provider interface: F003은 TourAPI 하나만 사용하며 후속 provider 요구가 확정되지 않아 제외.

**Official source**: [한국관광공사 국문 관광정보 서비스](https://www.data.go.kr/data/15101578/openapi.do), [한국관광콘텐츠랩](https://api.visitkorea.or.kr/)

## 2. 검색 endpoint 조합

**Decision**: keyword가 있으면 `searchKeyword2`에 keyword와 선택 법정동·신분류 조건을 함께 전달한다. keyword 없이 category만 있으면 `areaBasedList2`에 분류·지역 조건을 전달한다. 길픽 `query`는 trim 후 2글자 이상이며 내부 category와 함께 사용할 수 있다.

**Rationale**: 공식 최신 서비스는 `searchKeyword2`, `areaBasedList2`, `ldongCode2`, `lclsSystmCode2`를 제공하고 키워드 검색에 법정동·신분류 조건 결합을 지원한다. 검색 방식별 endpoint를 숨겨 Android에는 하나의 안정적인 검색 계약을 제공한다.

**Alternatives considered**:

- 모든 검색을 `searchKeyword2`로 처리: keyword가 필수라 category-only 요구를 만족하지 못함.
- category-only 검색을 client에서 전체 결과 filtering: 호출량과 누락 위험이 커 제외.

## 3. 내부 카테고리와 외부 코드 mapping

**Decision**: `NATURE`, `HISTORY_CULTURE`, `FOOD`, `CAFE`, `SHOPPING`, `OTHER` 6개 내부 enum과 추천 체류시간을 server의 명시적 versioned mapping으로 관리한다. 외부 대·중·소분류는 `lclsSystmCode2` 공식 결과를 fixture로 확보한 뒤 task에서 정확한 mapping을 확정한다. 매핑되지 않은 검색 결과는 `OTHER`로 변환한다.

**Rationale**: spec clarification과 ERD는 6개 내부 분류를 확정했지만 provider code 자체는 변경 가능하다. mapper를 transport parsing과 분리하면 F009가 같은 분류를 재사용하면서도 외부 코드 변경을 한 곳에서 검증할 수 있다.

**Alternatives considered**:

- provider code를 Android에 그대로 노출해 화면에서 mapping: 여러 client와 F009에 규칙이 중복되어 제외.
- DB mapping table: 운영 중 동적 변경 요구가 없고 F003은 DB를 사용하지 않아 제외.

## 4. 상세 조회와 운영 안내

**Decision**: `detailCommon2`와 `detailIntro2`를 병렬 호출해 기본 상세와 콘텐츠 유형별 소개정보를 조합한다. TourAPI가 제공한 운영 안내는 유형별 adapter가 하나의 nullable `operatingGuide` 문자열로 정규화한다. 현재 영업 여부와 정확한 마감 시각은 계산하지 않는다.

**Rationale**: 공통 정보와 소개 정보가 별도 endpoint이고 `detailIntro2` 필드는 content type마다 다르다. 사용자에게 provider의 확인된 설명은 제공하되 구조화된 실시간 영업 상태처럼 과도하게 해석하지 않는 것이 FR-007과 constitution IV에 맞는다.

**Alternatives considered**:

- `openNow`, `closesAt` 계산: TourAPI 원문만으로 신뢰 가능한 실시간 상태를 보장할 수 없어 제외.
- Google Places로 보강: F009 범위라 제외.
- 상세 endpoint 순차 호출: 독립 read이므로 불필요하게 latency가 늘어 제외.

## 5. Pagination, 정렬과 중복 제거

**Decision**: 길픽 API는 `limit` 기본·최대 20과 서명된 opaque cursor를 사용한다. cursor는 다음 provider `pageNo`, 검색 조건 fingerprint와 version을 담고 변조·다른 조건 재사용을 거부한다. TourAPI의 응답 순서를 유지하며 Android가 `placeId` stable key로 누적 결과를 dedupe한다.

**Rationale**: provider는 `pageNo`, `numOfRows`, `totalCount`를 사용하지만 프로젝트 공통 계약은 cursor다. provider pagination을 노출하지 않으면 향후 계약 변경을 격리할 수 있다. 일 1,000회 개발 quota를 고려해 20건이면 MVP 화면에 충분하며 Paging 3 없이 기존 상태 모델로 append할 수 있다.

**Alternatives considered**:

- page number를 public API에 노출: 프로젝트 목록 계약과 불일치해 제외.
- 서버가 모든 이전 content ID를 cursor에 저장: cursor가 페이지마다 커져 제외.
- Redis/DB cache와 server-side dedupe: 실시간 조회·무저장 범위를 넘고 인프라가 불필요해 제외.

## 6. Timeout, retry와 오류 mapping

**Decision**: 외부 호출 하나당 5초 timeout을 두고 전송 timeout과 일시적 5xx만 service에서 최대 1회 재시도한다. 공식 `APPLICATION_ERROR`·`SERVICETIMEOUT_ERROR`를 포함한 TourAPI application error와 invalid request·key·permission, 일/초 quota, blacklist 오류는 재시도하지 않는다. 길픽은 `TOUR_API_TIMEOUT`(504), `TOUR_API_FAILED`(502), `TOUR_API_RATE_LIMITED`(429), `PLACE_NOT_FOUND`(404)로 변환하고 provider 원문은 노출하지 않는다.

**Rationale**: 공식 오류표가 재호출 가능한 일시 오류와 key·quota 오류를 구분한다. retryable GET만 1회 재시도하면 spec을 지키면서 quota 소진과 retry storm을 피할 수 있다.

**Alternatives considered**:

- 모든 오류 1회 재시도: quota와 credential 오류를 악화시켜 제외.
- client 내부 무제한 exponential retry: 5초 사용자 목표와 최대 1회 정책에 어긋나 제외.

## 7. 저장과 cache

**Decision**: F003은 DB table, migration, repository와 server cache를 추가하지 않는다. 검색·상세 결과는 요청 생명주기에서만 존재한다. F004가 일정에 장소를 추가할 때 `places` 최소 참조정보를 저장한다.

**Rationale**: PLACE-01과 FR-014는 실시간 조회와 일정 저장 시 최소 보관을 분리한다. F003에서 미리 저장하면 stale data 정리·보존 정책과 migration이 불필요하게 생긴다.

**Alternatives considered**:

- in-process TTL cache: 다중 process 일관성이 없고 명시적 검색으로 호출량을 이미 제한하므로 제외.
- Redis cache: MVP 이후 기술 범위이며 운영 의존성을 늘려 제외.

## 8. Android navigation, state와 이미지

**Decision**: 안정 버전 Navigation Compose 2.9.8의 type-safe route로 검색·상세 back stack을 구성하고 destination-scoped ViewModel로 검색 결과를 유지한다. Coil 3.6.0 `coil-compose`와 `coil-network-okhttp`로 HTTPS 이미지를 표시한다. cursor append는 `StateFlow + LazyColumn`으로 직접 구현하고 Paging 3를 추가하지 않는다.

**Rationale**: UI-009는 상세 복귀 시 검색 조건·결과·scroll 유지를 요구하며 Android 공식 문서는 back stack entry에 ViewModel을 scope하는 방식을 제공한다. Coil 공식 문서는 Compose·OkHttp network image artifact를 제공하며 현재 Kotlin 2.4.10·compileSdk 37 조합과 맞는다. F003 pagination은 단일 remote source와 단순 append여서 Paging 3가 주는 복잡성이 이득보다 크다.

**Alternatives considered**:

- 한 composable 안에서 selected ID로 화면 전환: 작지만 F004 진입·뒤로가기와 app navigation 확장에 취약해 제외.
- custom image loader: decode·cache·lifecycle·오류 처리를 다시 구현해야 해 제외.
- Paging 3: DB cache·RemoteMediator가 없고 기존 StateFlow 패턴으로 요구를 충족해 제외.

**Official sources**: [Navigation 2.9.8 release](https://developer.android.com/jetpack/androidx/releases/navigation), [Android UI state saving](https://developer.android.com/develop/ui/compose/state-saving), [Coil getting started](https://coil-kt.github.io/coil/getting_started/)

## 9. UI 구조와 검증 기준

**Decision**: 저장소 UI token과 검색 입력 규칙을 재사용하고 새 시각 token을 만들지 않는다. `loading`, `empty`, `error`, `content`와 initial/append load를 분리하며 1초 초과 initial load만 skeleton을 표시한다. 결과 행은 64~72dp 썸네일, 장소명·category·주소·추천 체류시간을 우선 표시하고 divider로 연결한다.

**Rationale**: `.pen`에는 F003 전용 화면이 없으므로 승인되지 않은 디자인을 복제할 수 없다. `ui-guidelines.md`의 목록·검색·접근성 규칙과 spec UI-001~011이 충분한 source of truth다. `ui-ux-pro-max`의 loading·empty recovery 권고와 `compose-expert`의 stable key·semantics·navigation state 원칙을 저장소 기준에 맞춰 적용했다.

**Alternatives considered**:

- 일정 편집의 장소 타임라인 화면을 그대로 사용: F004 전용 순서·핀 의미가 검색 결과에는 없어 제외.
- 모든 결과를 card로 감싸기: 저장소의 낮은 card 장식 원칙과 충돌해 제외.

## 10. 공식 계약에서 구현 전 확인할 항목

다음은 제품 요구의 미확정이 아니라 credential과 최신 공식 활용매뉴얼로 확인할 provider 세부사항이다.

- `arrange` 허용값과 선택한 기본 정렬의 정확한 의미
- `numOfRows` 공식 최대값(길픽은 이와 무관하게 최대 20으로 제한)
- `detailIntro2` 콘텐츠 유형별 운영 안내 field명과 빈 값 형태
- 신분류·법정동 코드의 최신 fixture와 내부 6개 category mapping
- 개발·운영 quota와 운영계정 증설 승인 상태

이 검증은 TourAPI 환경 준비 Issue에 포함하고 실제 값이나 key를 repository·test output에 남기지 않는다.
