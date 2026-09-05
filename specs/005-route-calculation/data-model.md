# Data Model: F005 경로 계산

## Route

| 필드 | 형식 | 규칙 |
|---|---|---|
| `route_id` | UUID | PK |
| `trip_day_id` | UUID | `trip_days` FK, cascade delete |
| `schedule_version` | integer | 1 이상, 계산 입력 version |
| `status` | enum | `READY`, `FAILED`, `HISTORICAL` |
| `is_active` | boolean | 현재 사용자 노출 대상 여부 |
| `provider` | enum nullable | `TMAP`, `ODSAY`, `MIXED`; 1개 장소는 null |
| `total_duration_seconds` | integer nullable | READY면 0 이상 |
| `total_distance_meters` | integer nullable | READY면 0 이상 |
| `route_payload` | JSONB nullable | READY면 정규화 구간·geometry·attribution 포함 |
| `failure_code` | string nullable | FAILED면 필수 |
| `calculated_at` | timestamptz nullable | 최종 완료 시각 |
| `created_at`, `updated_at` | timestamptz | 서버 시각 |

### 불변 조건

- `(trip_day_id, schedule_version)`은 unique이고, 한 `trip_day_id`에는 `is_active=true`인 현재 경로가 최대 하나이며 그 version은 현재 일정과 같다.
- `READY`: 합계와 payload 필수, failure null. `FAILED`: failure 필수, 합계와 payload null. `HISTORICAL`: inactive.
- 1개 장소 READY는 합계 0, 빈 `segments`, marker 하나다. 0개는 row 없이 `NOT_CALCULATED`로 파생한다.

## Route payload

- `segments`: 순서, 인접 `fromItemId`/`toItemId`, 이동수단, provider, 초·미터, WGS84 GeoJSON `LineString`, attribution. Provider가 여러 선을 반환하면 이동 순서대로 좌표를 이어 하나의 `LineString`으로 정규화하고, 순서를 확정할 수 없으면 `ROUTE_INVALID_RESULT`로 실패 처리한다.
- `markers`: `itemId`, 순서, 이름, 위도·경도. 일정 순서와 정확히 일치
- `providerAttributions`: 중복 제거한 표시 문구

## 상태 전이

```text
0개 → NOT_CALCULATED
1개 → READY(0초, 0m)
2개 이상 → 성공 → READY
          └ 실패 → FAILED → 같은 version 재시도 → READY 또는 FAILED
일정 입력 변경: 이전 현재 경로 → HISTORICAL + 새 version 계산
완료 전 version 변경: 결과 폐기, 최신 상태 변경 금지
```

## Idempotency

- 일정 PUT은 F004의 기존 `Idempotency-Key` 규칙을 유지한다.
- 경로 retry는 별도 key를 저장하지 않고 요청의 `scheduleVersion`과 `(trip_day_id, schedule_version)` unique 제약을 사용한다.
- 같은 version의 중복·동시 retry는 같은 Route 행을 upsert하므로 활성 경로를 중복 생성하지 않는다.
- 다른 version 요청은 `409 VERSION_CONFLICT`, READY 경로 요청은 `409 ROUTE_NOT_FAILED`로 거부한다.
