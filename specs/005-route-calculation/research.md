# Phase 0 Research: F005 경로 계산

## 결정 1: transaction과 외부 호출 분리

**Decision**: 일정을 먼저 커밋하고 외부 호출 뒤 별도 transaction으로 경로를 반영한다.

**Rationale**: 외부 timeout 동안 DB lock을 유지하지 않고, 경로 실패에도 일정을 보존한다. 완료 시 `schedule_version`을 재확인해 늦은 결과가 최신 일정을 덮지 못한다.

**Alternatives considered**: 한 transaction은 장애 시 일정을 rollback한다. 비동기 job은 10초 안에 최종 상태를 응답한다는 명세와 맞지 않는다.

## 결정 2: 다중 구간과 deadline

**Decision**: 최대 9개 구간을 제한된 동시성으로 계산하고 전체 monotonic 10초 deadline을 둔다. 개별 시도는 최대 5초, 일시적 오류만 남은 시간 안에서 1회 재시도한다.

**Rationale**: 순차 호출은 구간 수에 따라 10초를 넘는다. global deadline이 serialization·DB overhead까지 포함해야 한다.

**Alternatives considered**: 순차 호출, 무제한 동시 호출은 각각 성능과 provider quota 문제로 제외한다.

## 결정 3: 제공자와 후보

**Decision**: `WALK`·`CAR`는 TMAP, `TRANSIT`는 ODsay를 사용하고 각 provider의 기본 추천 후보 하나만 정규화한다.

**Rationale**: FR-002·FR-005a와 사용자의 범위 축소 결정을 따른다.

**Alternatives considered**: 후보 저장·앱 정렬·provider 교차 비교는 MVP 범위 밖이다.

## 결정 4: ODsay 지도 형상

**Decision**: 대중교통 검색 응답의 `mapObj`로 지도 형상을 조회하고 두 호출을 한 구간 계산으로 취급한다. 하나라도 무효면 구간 실패다.

**Rationale**: ODsay 공식 안내에서 검색과 지도 표현용 형상 조회가 분리된다. 임의 직선은 실제 경로를 왜곡한다.

**Alternatives considered**: 정류장 좌표 직선 연결은 FR-003을 충족하지 못한다.

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
- 공식 자료 확인 사실: ODsay는 대중교통 검색과 `mapObj` 기반 형상 조회를 제공한다. Naver Maps Android SDK는 marker/path overlay와 gesture를 지원하며 `MapView` lifecycle 전달이 필요하다.
- 구현 전 재검증: TMAP·ODsay quota와 key 권한, 필수 attribution 문구, Naver SDK 적용 버전은 계약·console 설정에 따라 달라질 수 있다. live smoke test에서 확인하되 secret은 저장하지 않는다.
