# Phase 0 Research: F005 경로 계산

## 결정 8: 구간별 수단 추정과 캐시

**Decision**: 별도 동기식 endpoint에서 `WALK`·`TRANSIT`·`CAR`를 기존 provider 동시성 한도 안에서 계산한다. 결과는 수단별로 독립 반환하고 성공 300초, 실패 60초 TTL의 DB cache를 사용한다. cache key에 `schedule_version`을 포함하고 새 version 요청 때 이전 row를 제거한다.

**Rationale**: 기존 정식 경로의 all-or-nothing 계약과 저장된 이동 수단을 보존하면서 #508이 필요한 비교 값만 제공한다. DB cache는 요청마다 생성되는 service와 여러 API instance에서도 동일하게 작동한다.

**Alternatives considered**: 정식 `RouteSegment` 확장은 실패 격리와 cache 수명을 기존 경로 계약에 섞는다. process memory cache는 instance 간 공유되지 않으며 service가 요청마다 생성되어 재사용되지 않는다. background worker는 현재 응답 규모에 불필요하다.

## 결정 1: transaction과 외부 호출 분리

**Decision**: 일정을 먼저 커밋하고 외부 호출 뒤 별도 transaction으로 경로를 반영한다.

**Rationale**: 외부 timeout 동안 DB lock을 유지하지 않고, 경로 실패에도 일정을 보존한다. 완료 시 `schedule_version`을 재확인해 늦은 결과가 최신 일정을 덮지 못한다.

**Alternatives considered**: 한 transaction은 장애 시 일정을 rollback한다. 비동기 job은 10초 안에 최종 상태를 응답한다는 명세와 맞지 않는다.

## 결정 2: 다중 구간과 deadline

**Decision**: 최대 9개 구간을 제한된 동시성으로 계산하고 전체 monotonic 10초 deadline을 둔다. 개별 시도는 최대 5초, 일시적 오류만 남은 시간 안에서 1회 재시도한다.

**Rationale**: 순차 호출은 구간 수에 따라 10초를 넘는다. global deadline이 serialization·DB overhead까지 포함해야 한다.

**Alternatives considered**: 순차 호출, 무제한 동시 호출은 각각 성능과 provider quota 문제로 제외한다.

## 결정 3: 제공자와 후보

**Decision**: `WALK`·`CAR`는 TMAP, `TRANSIT`는 Kakao Maps를 사용하고 각 provider의 기본 추천 후보 하나만 정규화한다. 교체 전 저장된 `ODSAY` provider 값은 조회 호환성을 위해 유지한다.

**Rationale**: FR-002·FR-005a와 사용자의 범위 축소 결정을 따른다.

**Alternatives considered**: 후보 저장·앱 정렬·provider 교차 비교는 MVP 범위 밖이다.

## 결정 4: Kakao Maps 지도 형상

**Decision**: 대중교통 응답의 첫 번째 `routes` 항목을 기본 추천 경로로 채택하고 `steps[].path.points`를 제공 순서대로 이어 지도 형상으로 사용한다. 공식 `StepProperties`의 `WALKING`·`BUS`·`SUBWAY`를 각각 `WALK`·`BUS`·`SUBWAY`로 정규화하고, `time`·`distance`는 초·미터 그대로 사용한다. `stops`의 첫 번째·마지막 이름은 승차·하차 지점으로, 중복을 제거한 `vehicles[].name`은 제공 순서대로 ` / `로 연결한 노선명으로 사용한다. 공식 계약에 정류장 수 의미가 없으므로 `stopCount`는 null이다.

**Rationale**: Kakao Maps 공식 응답이 기본 추천 경로의 단계별 실제 좌표와 상세 이동 정보를 제공하므로 별도 형상 호출이나 임의 직선 연결이 필요 없다. 상세 정보는 보조 정보이므로 누락·오류가 있어도 유효한 전체 경로를 실패시키지 않는다.

**Alternatives considered**: 정류장 좌표 직선 연결은 FR-003을 충족하지 못한다. 제공자 원문을 그대로 공개하거나 일부 단계만 반환하면 계약 결합과 불완전한 안내가 생겨 제외했다.

## 결정 5: 상태와 재시도 API

**Decision**: 영속 상태는 `NOT_CALCULATED`, `READY`, `FAILED`, 보관용 `HISTORICAL`이다. `CALCULATING`은 Android local 상태다. 복구는 실패 상태 전용 `POST .../route/retry`로 제공하고, `(trip_day_id, schedule_version)` 경로 한 행을 upsert하여 멱등하게 처리한다.

**Rationale**: 동기 처리에 중간 영속 상태나 F005 전용 멱등성 table을 추가하면 정리 작업이 불필요하게 복잡하다. 버전별 unique 제약과 upsert만으로 중복 경로 노출을 막을 수 있고, `/retry`는 정상 경로 재탐색과 구분된다.

**Alternatives considered**: 기존 `/route/recalculate`는 F006의 진행 중 남은 경로 재계산 의미와 섞이므로 F005에서 사용하지 않는다.

## 결정 6: Android 지도 통합

**Decision**: Naver Maps `MapView`를 lifecycle 전달 `AndroidView` adapter로 감싸고 ViewModel은 SDK 객체가 아닌 immutable overlay 모델과 `StateFlow`를 제공한다.

**Rationale**: 기존 Compose 구조를 유지하면서 preview·단위 test가 가능하고, 구간 목록을 접근 가능한 대체 표현으로 제공할 수 있다.

**Alternatives considered**: Fragment 전환은 Navigation Compose 통합 비용이 크고, SDK 객체의 ViewModel 보관은 lifecycle과 test를 결합한다.

## 결정 7: 저장 형식

**Decision**: 합계·상태는 column, 화면에 필요한 정규화 구간·geometry·attribution은 JSONB payload로 저장한다. provider 원문 전체는 저장하지 않는다.

**Rationale**: 두 provider를 하나의 API 계약으로 제공하고 provider 결합도와 불필요한 데이터 저장을 줄인다.

**Alternatives considered**: 구간·좌표의 완전 정규화는 MVP 조회 요구에 비해 복잡하고, 원문 저장은 최소 데이터 원칙에 어긋난다.

## 확인 근거와 한계

- 저장소 확정 사실: `httpx2` async timeout/retry pattern, PostgreSQL/PostGIS, 일정 `schedule_version`, Compose/Retrofit/StateFlow 구조를 사용한다.
- 공식 자료 확인 사실: Kakao Maps 대중교통 경로 응답은 기본 경로 목록, 총 시간·거리와 `steps[].path.points` 형상을 제공한다. Naver Maps Android SDK는 marker/path overlay와 gesture를 지원하며 `MapView` lifecycle 전달이 필요하다.
- 2026-09-14 AWS PoC 확인 사실: 기존 `KAKAO_REST_API_KEY`로 HTTP 200·`status=OK`·15개 경로를 받았고 Kakao Developers 통계에 대중교통 호출 1건이 집계됐다. secret 원문은 기록하지 않는다.
- 구현 전 재검증: TMAP·Kakao Maps quota와 key 권한, 필수 attribution 문구, Naver SDK 적용 버전은 계약·console 설정에 따라 달라질 수 있다. live smoke test에서 확인하되 secret은 저장하지 않는다.
