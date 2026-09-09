# Phase 1 Data Model: 여행 변수 감지

기준: `docs/design/er-schema.md` §8.1(`detections`)·§10(enum). 이 Feature는 `detections` 한 테이블만 신설하고(migration 007), 나머지는 F006/F003 데이터를 읽기만 한다.

---

## 1. 신규 테이블 `detections`

한 감지 결과 = 진행 중 남은 장소 하나에 대한 혼잡·날씨·운영시간 위험 평가 묶음.

| 컬럼 | 타입 | NULL | 설명 |
|---|---|---:|---|
| `detection_id` | uuid | N | PK, `uuid4` |
| `trip_day_id` | uuid | N | FK → `trip_days.trip_day_id` `ON DELETE CASCADE` |
| `item_id` | uuid | N | FK → `itinerary_items.item_id` `ON DELETE CASCADE`. 평가 대상 장소 |
| `primary_type` | varchar(30) | N | `detection_type` enum. 종합 점수가 가장 큰(동점 시 `OPERATING_HOURS` > `WEATHER` > `CONGESTION`) 변수 |
| `status` | varchar(20) | N | `detection_status` enum. 기본 `ACTIVE` |
| `eta` | timestamptz | N | 평가 기준으로 삼은 대상 장소의 도착 예정 시각(`itinerary_items.estimated_arrival_at` 스냅샷) |
| `score` | numeric(7,6) | Y | 누락 변수 비례 재정규화 후 0~1 원점수. 표시용 `totalRiskScore = round(score*100)` |
| `reason` | text | N | 사용자 표시 요약 문구(예: "도착 시각에 문을 닫아요") |
| `evaluation_snapshot` | jsonb | N | 변수별 원값·가용성·임계값·가중치·판정 근거 + 평가 시각 + 사용 ETA (§3) |
| `fingerprint` | varchar(255) | N | 같은 장소 중복 감지 방지 키 = `"{trip_day_id}:{item_id}"` |
| `detected_at` | timestamptz | N | 최초 생성 시각(서버 시각) |
| `last_evaluated_at` | timestamptz | N | 마지막 재평가 시각(서버 시각) |
| `read_at` | timestamptz | Y | 사용자 읽음 시각(DETECT-003) |
| `resolved_at` | timestamptz | Y | `RESOLVED`·`DISMISSED`·`INVALIDATED` 전환 시각 |

### 제약·인덱스

- `PRIMARY KEY (detection_id)`
- `UNIQUE INDEX uq_detections_active_fingerprint ON detections (fingerprint) WHERE status = 'ACTIVE'` — 한 장소에 `ACTIVE` 감지 최대 1행(FR-013·FR-016)
- `INDEX ix_detections_day_status_detected ON detections (trip_day_id, status, detected_at DESC)` — 재평가·종료 대상 조회, 목록 조회
- `INDEX ix_detections_item ON detections (item_id)` — 항목 상태 변경 시 종료 처리
- `CHECK (score IS NULL OR (score >= 0 AND score < 10))` — `numeric(7,6)` 범위 방어
- `CHECK (primary_type IN ('CONGESTION','WEATHER','OPERATING_HOURS'))`
- `CHECK (status IN ('ACTIVE','RESOLVED','DISMISSED','INVALIDATED'))`

> 목록 조회 소유권은 `detections → trip_days → trips.user_id`로 검증한다. 별도 `trip_id` 비정규화 컬럼은 두지 않는다(항상 `trip_day`를 거쳐 조인).

---

## 2. Enum (`er-schema.md` §10 그대로)

| enum | 값 | F008에서의 의미 |
|---|---|---|
| `detection_type` | `CONGESTION`, `WEATHER`, `OPERATING_HOURS` | 평가하는 세 변수 |
| `detection_status` | `ACTIVE`, `RESOLVED`, `DISMISSED`, `INVALIDATED` | `ACTIVE`=pending(F008 생성) / `RESOLVED`·`DISMISSED`=결정됨(F009·F010이 설정) / `INVALIDATED`=종료(F008이 설정) |

**변수 판정 값(응답·스냅샷 공용, F008이 정의)**

| 개념 | 값 |
|---|---|
| 혼잡 수준 `congestion.level` | `RELAXED`, `NORMAL`, `SLIGHTLY_CROWDED`, `CROWDED` |
| 카테고리 민감도 `congestion.sensitivity` | `HIGH`(쇼핑·음식·카페), `MEDIUM`(자연·문화·역사·기타·미분류) |
| 강수 형태 `weather.precipitationType` | `NONE`, `RAIN`, `RAIN_SNOW`, `SNOW`, `SHOWER` |
| 실내외 노출도(내부용) | `OUTDOOR`, `INDOOR`, `UNKNOWN` |
| 변수 제외 사유 `unavailableReason` | `NO_FORECAST`, `NOT_IN_SUPPORT_AREA`, `HOURS_UNKNOWN`, `INDOOR`, `TIMEOUT` |

---

## 3. `evaluation_snapshot` 구조 (jsonb)

```jsonc
{
  "evaluatedAt": "2026-09-08T13:05:00+09:00",
  "eta": "2026-09-08T14:00:00+09:00",
  "weights": { "operatingHours": 0.5, "weather": 0.25, "congestion": 0.25 },
  "variables": {
    "operatingHours": {
      "available": true,
      "raw": { "businessStatus": "OPERATIONAL", "closesAt": "2026-09-08T14:00:00+09:00", "source": "google_places" },
      "verdict": { "visitBlocked": true, "closingSoon": false, "tempClosed": false },
      "severity": 1.0
    },
    "weather": {
      "available": false,
      "unavailableReason": "INDOOR",
      "raw": { "indoorExposure": "INDOOR" },
      "verdict": null,
      "severity": null
    },
    "congestion": {
      "available": true,
      "raw": { "areaName": "북촌한옥마을", "areaCode": "POI014", "distanceMeters": 180,
               "fcstTime": "2026-09-08T14:00:00+09:00", "level": "SLIGHTLY_CROWDED", "sensitivity": "MEDIUM" },
      "verdict": { "crowded": false },
      "severity": 0.0
    }
  },
  "score": { "normalizedWeights": { "operatingHours": 0.667, "congestion": 0.333 }, "raw": 0.667 }
}
```

- 정밀 사용자 위치·이동 경로는 넣지 않는다(FR-023, constitution V). `congestion.raw`의 좌표는 장소가 아니라 지원 지점 식별자·거리만 남긴다.
- `available=false`면 `verdict`·`severity`는 `null`이고 점수 계산에서 빠진다.

---

## 4. 상태 전이

```text
                (세 변수 중 위험 ≥ 1)
   (행 없음) ───────────────────────────▶ ACTIVE
      ▲                                    │  │
      │ 날짜 IN_PROGRESS 복귀 후 재위험      │  │ 같은 장소 재평가: 새 행 X, 스냅샷·score·eta 갱신
      │ (새 ACTIVE 행 생성)                 │  │ (self-loop)
      │                                    │  │
      │           F009·F010 사용자 결정     │  ▼
   INVALIDATED ◀───────────────           RESOLVED / DISMISSED
      ▲   (항목 COMPLETED·SKIPPED·         (F008은 읽기만; 이 상태에는 새 ACTIVE를
      │    일정 제거·날짜 COMPLETED)         만들지 않음 = 중복 경고 억제, FR-013)
      └── F008이 설정, resolved_at=now
```

- **생성**: §2 대상 장소에서 개별 변수 중 하나라도 위험이고 그 장소에 `ACTIVE`/`RESOLVED`/`DISMISSED` 행이 없으면 `INSERT`. 있으면(=`ACTIVE`) upsert로 갱신만. `RESOLVED`/`DISMISSED`가 있으면 아무것도 하지 않는다(사용자가 이미 결정 중/결정함).
- **갱신**: `ACTIVE` 행에 대해 `eta`·`evaluation_snapshot`·`score`·`reason`·`primary_type`·`last_evaluated_at` 갱신. 모든 위험이 사라져도 `status`는 `ACTIVE` 유지(US2 Scenario 2), `score`는 낮아진 값으로 갱신.
- **종료**(`ACTIVE` → `INVALIDATED`, `resolved_at=now`): 대상 항목 `status ∈ {ARRIVED, COMPLETED, SKIPPED}` 또는 `trip_days.status = COMPLETED`.
- **일정 삭제**: `itinerary_items`에서 항목이 삭제되면 `detections.item_id ON DELETE CASCADE`로 연결된 감지 결과도 함께 삭제한다. 삭제된 일정 항목의 감지 이력은 보존하지 않는다.
- **재개**: 날짜가 `COMPLETED` → `IN_PROGRESS`(`detection_active=true`)로 복귀하면 다음 평가에서 새 `ACTIVE` 행이 생길 수 있다. `INVALIDATED` 행은 재사용하지 않는다.
- **읽음**: `read_at` 갱신은 다른 상태 전이와 독립. 어느 `status`에서도 가능.

---

## 5. 읽기 전용 의존 데이터 (신규 저장 없음)

| 출처 | 사용하는 값 | 용도 |
|---|---|---|
| `trip_days` (F006) | `status`, `detection_active`, `visit_date`, `trip_id` | 평가 대상 날짜 선별(§2), 종료 트리거 |
| `itinerary_items` (F004/F006) | `status`, `estimated_arrival_at`, `sequence`, `place_id` | 남은 장소 선별, ETA 기준 시점, 종료 트리거 |
| `places` (F003) | `location`(geography Point), `category` | 500m 지원 지점 매핑, 카테고리 민감도, 실내외 파생 |
| `trips` (F002) | `user_id` | DETECT 소유권 검증 |
| Google Places v1 `places.get` | `regularOpeningHours.periods`, `businessStatus`, `utcOffsetMinutes` | 폐점 시각·임시휴업(research §5) |
| 기상청 단기예보 | `POP`, `PCP`, `PTY` (ETA 슬롯) | 날씨 판정(research §3) |
| 서울시 `citydata_ppltn` | `AREA_CONGEST_LVL`, `FCST_PPLTN[].{FCST_TIME,FCST_CONGEST_LVL}` | 혼잡 판정(research §4) |

---

## 6. 버전 관리 설정 (DB 아님)

### 6.1 `app/services/detection/congestion_areas.json`

```jsonc
{
  "version": "2026-09-08",
  "source": "서울 열린데이터광장 실시간 도시데이터 지원 지점 목록 (G001에서 확정)",
  "areas": [
    { "name": "북촌한옥마을", "areaCode": "POI014", "latitude": 37.582604, "longitude": 126.983998 }
    // … 지원 지점 전체
  ]
}
```

- 장소 `location`에서 `ST_DWithin(location, area_point, 500)` 으로 매핑. 여러 개면 최근접 1곳. 없으면 혼잡 변수 제외.

### 6.2 `app/services/detection/policy.py` (조정 가능한 값 단일 출처, FR-011)

- 날씨 임계값: `POP ≥ 70`, `PCP ≥ 1.0`, `PTY ∈ {1,2,3,4}`.
- 운영시간: 폐점 임박 창 `30분`.
- 혼잡 위험 경계: `HIGH` 민감도 → `SLIGHTLY_CROWDED` 이상, `MEDIUM` → `CROWDED`.
- 카테고리 민감도 매핑, 실내외 노출도 매핑(research §6).
- 종합 점수 가중치·심각도(research §7).
- `detection_cycle_seconds` 기본 600, 외부 호출 타임아웃 5초·재시도 1회.

> 이 값들은 `docs/planning/requirements.md`·`functional-spec.md` §4에 반영을 요청한다(research 미해결 4).
