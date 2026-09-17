# 대중교통 단계별 geometry 보완 설계

## 목표

Kakao 대중교통 경로의 장소와 첫·마지막 단계 사이, 그리고 인접 단계 사이에 좌표 공백이 있어도 임의 직선이 그려지지 않게 한다. 실제 보행 연결이 필요한 공백은 TMAP WALK geometry로 보완하며, 보완에 실패하면 해당 날짜 경로 계산 전체를 `FAILED`로 처리한다.

## 공개 계약

- `RouteStep.geometry`를 nullable GeoJSON `LineString`으로 추가한다.
- 새로 계산한 Kakao 대중교통 경로는 모든 step에 유효한 geometry를 제공한다.
- 기존 JSONB처럼 `steps` 또는 step `geometry`가 없는 저장 데이터는 계속 조회할 수 있다.
- Kakao 형상에 TMAP 보행 형상이 포함된 구간은 `providerAttribution`을 `Kakao Maps · TMAP`으로 제공한다.
- 구간의 `provider`와 시간·거리 합계는 Kakao 값을 유지한다. TMAP 호출은 Kakao가 이미 합계에 포함한 보행 이동의 누락 형상만 보완한다.

## 좌표 연결 정책

1. Kakao adapter는 각 `steps[].path.points`를 개별 `NormalizedTransitStep.geometry`로 보존한다.
2. 좌표 간 거리가 3m 이하이면 provider 좌표 정밀도 차이로 보고 뒤 단계 시작점을 앞 단계 끝점에 맞춘다.
3. 3m를 초과하는 공백은 다음 규칙에 따라 TMAP WALK로 계산한다.
   - 장소 → 첫 step: 첫 step이 `WALK`일 때 그 step 앞에 보완 geometry를 합친다.
   - step → step: 두 step 중 하나가 `WALK`일 때 그 WALK step에 보완 geometry를 합친다.
   - 마지막 step → 장소: 마지막 step이 `WALK`일 때 그 step 뒤에 보완 geometry를 합친다.
4. 공백에 인접한 WALK step이 없거나 TMAP이 유효한 geometry를 반환하지 못하면 `ROUTE_INVALID_RESULT` 또는 TMAP의 안정적인 provider 오류를 그대로 전파한다.
5. 모든 step이 연속해진 뒤에만 전체 `RouteSegment.geometry`를 만든다. 두 좌표를 직선으로 삽입하는 fallback은 두지 않는다.

## 호출과 실패 처리

- Kakao 호출과 보완 TMAP 호출은 기존 날짜 전체 10초 deadline을 공유한다.
- 한 대중교통 구간의 독립적인 보완 호출은 `asyncio.gather`로 실행하되 서비스의 기존 구간 동시성 경계 안에서 수행한다.
- 보완 호출 하나라도 실패하면 `_calculate_segment`가 `RouteProviderError`를 전파하고 기존 orchestration이 날짜 전체 결과를 `FAILED`로 만든다.
- timeout·네트워크·429·5xx 재시도 정책은 기존 구간 재시도 정책을 그대로 사용한다. 보완 실패만 따로 삼키지 않는다.

## Android 영향

- Android DTO에 nullable step geometry를 추가해 legacy 응답을 유지한다.
- 전체 segment geometry가 장소 좌표부터 다음 장소 좌표까지 실제 경로로 완성되므로 기존 `segmentPath()`는 marker 좌표를 임의로 덧붙이지 않고 Backend geometry만 사용한다.
- 단계별 overlay 전환은 별도 Android Issue에서 할 수 있으며, 이번 Backend Issue는 그 입력 계약을 제공한다.

## 검증

- 장소→첫 WALK, 마지막 WALK→장소, BUS/SUBWAY↔WALK 환승 공백을 각각 TMAP geometry로 보완한다.
- 3m 이하 오차는 외부 호출 없이 endpoint를 맞춘다.
- WALK가 없는 공백, TMAP 실패, 잘못된 TMAP geometry는 날짜 전체 `FAILED`를 만든다.
- step 순서·geometry 연속성·전체 geometry 조합을 검증한다.
- JSONB round-trip과 step geometry 없는 legacy payload를 검증한다.
- OpenAPI·공용 API 문서·F005 명세를 동기화한다.
