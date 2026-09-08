# Phase 0 Research: 여행 변수 감지

/ Clarifications(Session 2026-09-08)에서 10분 주기 보장 방식·감지 결과 생성 기준·인앱 표시 소유 Feature가 확정되었다. 이 문서는 나머지 기술 미확정 항목을 정리한다. 기상청·서울시 실제 연동 세부는 **G001 Gate**에서 credential·공유 환경과 함께 확정하며, 그 전에는 아래 결정을 mock·계약 테스트로 검증한다.

---

## 1. 10분 주기 평가 실행 방식

**Decision**: `app/jobs/variable_detection.py`에 `run_variable_detection(session_factory, *, interval_seconds=600)`를 두고, `main.py` lifespan에서 `asyncio.create_task`로 등록한다. 루프는 `try / logger.exception / await asyncio.sleep(600)` 구조로 `app/jobs/auth_cleanup.py`의 `run_auth_cleanup`과 동일하다.

**Rationale**: spec Clarifications에서 "단일 서버 in-process 주기 작업"으로 확정됐고, 이 저장소에는 이미 같은 패턴(`run_auth_cleanup`)이 lifespan에서 운영 중이다. 신규 실행기·의존성이 필요 없다. `spec` Out of Scope는 Celery·분산 작업만 배제하며 in-process `asyncio` 루프는 배제하지 않는다.

**Alternatives considered**:
- 요청 시 lazy 평가: 조회 요청이 없는 동안 평가가 멈춰 SC-001("앱을 여는지와 무관하게 서버 보장") 위반. F007이 자동 확정에 lazy를 택한 것은 "특정 날짜의 다음 요청"이라는 자연스러운 트리거가 있기 때문이고, F008은 그런 트리거가 없다.
- Celery/APScheduler 프로세스 분리: MVP 범위 밖(`mvp.md`, constitution 추가 제약).

**Notes**: `uvicorn --workers N>1`로 뜨면 작업이 worker마다 생성된다. 결과 중복은 `fingerprint` partial unique + upsert로 구조적으로 차단되지만(§6), 불필요한 외부 호출을 줄이려면 배포에서 단일 worker로 실행하거나 작업을 별도 entrypoint로 분리한다. `main.py` 주석에 남긴다.

---

## 2. 평가 대상 선별

**Decision**: 대상 = `trip_days.detection_active = true` AND `trip_days.status = 'IN_PROGRESS'` AND `visit_date = 오늘(Asia/Seoul)` 인 날짜의 `itinerary_items` 중 `status IN ('PLANNED','EN_ROUTE')` 이고 `estimated_arrival_at IS NOT NULL` 인 항목.

**Rationale**: `detection_active`는 F006이 시작 시 `true`, 당일 완료 시 `false`, 상태 수정 복귀 시 `true`로 관리한다(`er-schema.md` §5.2, F006 research 결정 3). 여기에 오늘 날짜 조건을 더해 "진행이 시작되어 아직 완료되지 않은 당일"을 만족한다. ETA(`estimated_arrival_at`)가 없으면 "도착 예정 시각 기준" 판정이 불가능하므로 제외한다(spec Edge Case).

**해결됨(2026-09-08 plan 반영)**: spec FR-001·FR-014·Key Entities "평가 대상"을 "방문 상태가 `예정`·`이동 중`인 남은 장소"로 조정했다. `도착(ARRIVED)` 장소는 사용자가 이미 그 자리에 있어 "계획대로 방문 가능한지"를 물을 이유가 없고, `도착` 전환 시 pending 감지 결과를 종료한다. 이 조정은 정책 변경이 아니라 "남은 장소" 용어 정합이며 문서 PR review에서 팀이 확인한다.

---

## 3. 날씨 변수 — 기상청 단기예보

**Decision**: `app/clients/kma.py`가 기상청 단기예보(`getVilageFcst`)를 호출한다.
- 요청: `serviceKey`(data.go.kr), `dataType=JSON`, `base_date`/`base_time`(발표 시각 08 구간 중 최신), `nx`/`ny`(장소 위경도를 기상청 LCC(DFS) 격자 공식으로 변환), `numOfRows` 충분히 크게.
- 사용 category: `POP`(강수확률 %), `PCP`(1시간 강수량 범주 문자열 → 수치/범주 파싱, "강수없음"=0), `PTY`(강수형태: 0없음·1비·2비/눈·3눈·4소나기).
- 대상 장소 `estimated_arrival_at`에 가장 가까운 `fcstDate`+`fcstTime` 슬롯을 선택한다.
- 판정(FR-007): `POP ≥ 70` 또는 `PCP ≥ 1.0mm/h` 또는 `PTY ∈ {1,2,3,4}` 이면 위험.
- 타임아웃 5초·1회 재시도, 최종 실패·격자 변환 불가·해당 슬롯 없음 → 날씨 변수 제외(`available=false`, `unavailableReason=NO_FORECAST` 또는 `TIMEOUT`).

**Rationale**: 단기예보는 +3일까지 시간 단위 예보를 제공해 당일 남은 일정을 모두 커버한다. 초단기예보(+6h)로는 늦은 오후·저녁 장소를 못 덮는다. `requirements.md` §3이 기상청 5초·1회 재시도·실패 시 날씨 변수 제외를 이미 규정한다.

**실내외 게이트**: 아래 §5의 실내외 분류가 `INDOOR`면 예보가 있어도 날씨 위험을 적용하지 않는다(`available=false`, `unavailableReason=INDOOR`). `OUTDOOR`·`UNKNOWN`에만 적용(FR-007, SC-007).

**Alternatives considered**: OpenWeather 등 상용 API — `mvp.md`가 기상청으로 고정. TourAPI 날씨 — 예보 시점·강수 상세가 부족.

**G001 확인 필요**: 격자 변환 공식 검증, `base_time` 선택 규칙, `PCP` 범주 문자열의 실제 형식, JSON 응답 키 케이스.

---

## 4. 혼잡 변수 — 서울시 실시간 도시데이터

**Decision**:
- `app/services/detection/congestion_areas.json`에 서울시 "실시간 도시데이터" 지원 지점의 `{name, area_code, latitude, longitude}` 목록을 버전 관리 설정으로 고정한다(`er-schema.md`: `congestion_areas`는 테이블이 아니라 버전 관리 설정).
- `congestion.py`가 장소 `location`에서 PostGIS 거리로 500m 이내 지원 지점을 찾는다. 없으면 혼잡 변수 제외(`available=false`, `unavailableReason=NOT_IN_SUPPORT_AREA`). 500m 안에서는 거리 감쇠 없음(FR-006). 여러 지점이면 최근접 1곳.
- `app/clients/seoul_citydata.py`가 해당 지점의 `citydata_ppltn`(인구/혼잡)을 호출한다. `FCST_PPLTN[]`에서 `estimated_arrival_at`에 가장 가까운 `FCST_TIME`의 `FCST_CONGEST_LVL`을 쓰고, 예보가 없으면 현재값 `AREA_CONGEST_LVL`로 대체한다.
- 혼잡 4단계: `여유`→RELAXED, `보통`→NORMAL, `약간 붐빔`→SLIGHTLY_CROWDED, `붐빔`→CROWDED.
- 카테고리 민감도(FR-006, `er-schema.md` §5.3): 높음 = `SHOPPING`·`FOOD`·`CAFE`; 중간 = `NATURE`·`HISTORY_CULTURE`·`OTHER`·미분류.
- 판정: 민감도 높음이면 `SLIGHTLY_CROWDED` 이상, 민감도 중간이면 `CROWDED` 이상에서 혼잡 위험. (경계값은 `policy.py`.)
- 타임아웃 5초·1회 재시도, 최종 실패 → 혼잡 변수 제외(`unavailableReason=TIMEOUT`).

**Rationale**: `requirements.md` §3이 서울시 데이터 5초·1회 재시도·실패 시 혼잡 변수 제외를 규정. `citydata_ppltn` 타입은 혼잡·인구만 반환해 payload가 작다. 지원 지점 좌표를 API가 직접 주지 않으므로 목록을 설정으로 고정해야 500m 판정을 재현 가능하게 만든다.

**Alternatives considered**: 한국관광공사 관광지 집중률 API — spec Out of Scope. 서울시 `citydata`(전체) — 날씨·행사까지 와서 과도.

**G001 확인 필요**: 지원 지점 최종 목록과 좌표 출처, `citydata_ppltn` JSON 스키마와 `FCST_TIME` 포맷·간격, 지역 조회 키(지역명 vs 코드).

---

## 5. 운영시간 변수 — 폐점 시각과 임시휴업

**Decision**: `operating_hours.py`가 대상 장소의 Google Places `places.get`을 `regularOpeningHours.periods`·`businessStatus`·`utcOffsetMinutes` field mask로 직접 호출한다(F003이 이미 쓰는 Google Places v1 계약).
- `businessStatus = CLOSED_TEMPORARILY` → 방문 불가(`visitBlocked=true`, `tempClosed=true`) (FR-008 "확인 가능한 임시휴업").
- `businessStatus = CLOSED_PERMANENTLY` → 방문 불가(`visitBlocked=true`).
- `regularOpeningHours.periods`에서 `estimated_arrival_at`의 요일에 해당하는 `close` 시각을 구한다.
  - ETA ≥ 폐점 시각 → 방문 불가(`visitBlocked=true`).
  - 폐점 30분 전 이내 → `closingSoon=true`.
  - `periods`가 없거나(24시간 영업 포함) 요일 매칭 실패 → "운영 중 가정", 운영시간 변수 제외(`available=false`, `unavailableReason=HOURS_UNKNOWN`) (FR-008 "운영시간을 확인할 수 없으면 운영 중으로 가정").
- Google 호출 실패·키 없음·타임아웃(5초·1회 재시도) → 운영시간 변수 제외(FR-009).

**Rationale**: `places` 테이블은 운영시간을 저장하지 않고(`er-schema.md` §5.3), F003은 "현재 영업 여부나 정확한 마감 시각을 계산하지 않는다"를 명시(F003 spec Q&A, FR-007). 따라서 F003이 노출하는 표시용 문자열(`regular_opening_hours` 등)을 파싱하는 것은 취약하고 계약 취지에 어긋난다. Google Places v1의 `regularOpeningHours.periods`는 요일·시·분 구조화 필드로 표준이며 F003이 이미 같은 provider를 쓴다.

**계약 추가 요청(문서화)**: 중복 Google 호출을 없애려면 F003 장소 상세 계약이 `regularOpeningHours.periods` 구조화 필드와 `businessStatus`를 그대로 노출하도록 확장하는 것이 바람직하다. MVP에서는 F008이 직접 호출하되, 이 요청을 `docs/design/api-spec.md` F003 절과 팀 공유사항에 남긴다.

**Alternatives considered**: F003 표시 문자열 파싱 — 로케일·형식 불안정, 계약 위반 소지. TourAPI 운영 안내 문자열 — 자유 서식이라 폐점 시각 추출 불가.

**G001/구현 확인 필요**: `places.get` field mask에 `regularOpeningHours.periods` 포함 시 과금·쿼터 영향, `utcOffsetMinutes`로 `Asia/Seoul` 정합.

---

## 6. 실내·실외 분류

**Decision**: `policy.py`에 내부 `category` → 노출도 매핑을 둔다.
- `NATURE` → OUTDOOR
- `SHOPPING`·`CAFE`·`FOOD` → INDOOR
- `HISTORY_CULTURE`·`OTHER`·미분류·`category` 없음 → UNKNOWN
- 날씨 페널티는 OUTDOOR·UNKNOWN에 적용, INDOOR 제외(FR-007, SC-007, spec Edge Case "구분 정보가 없으면 미상으로 보고 페널티 적용").

**Rationale**: 실내·실외 구분 필드는 어떤 계약에도 없다. `HISTORY_CULTURE`는 고궁·유적(실외)과 박물관(실내)이 섞여 UNKNOWN이 안전하다. §5에서 Google `places.get`을 이미 호출하므로 후속으로 `types`(`park`·`hiking_area`·`tourist_attraction`)를 보조 신호로 쓸 수 있으나 MVP는 category 매핑만 사용한다.

**Alternatives considered**: 모든 장소를 UNKNOWN 처리 — 실내 카페까지 날씨 위험이 붙어 SC-007 위반. Google types만으로 판정 — 누락 장소가 많음.

---

## 7. 종합 위험 점수

**Decision**: `scoring.py` + `policy.py`에서 관리한다.
- 각 변수의 위험 심각도(0~1)에 가중치를 곱해 합산하고, `available=false` 변수는 빼고 남은 가중치를 비례 재정규화한다(api-spec §7.2 추천 점수의 "일부 변수 없으면 나머지 가중치 비례 재분배"와 같은 방식).
- `detections.score`(`numeric(7,6)`)에는 0~1 원점수, API `totalRiskScore`에는 `round(score * 100)` 정수를 넣는다(FR-011 "표시 반올림").
- 초기 제안값(모두 `policy.py` 기본값):
  - 변수 가중치: `OPERATING_HOURS 0.5`, `WEATHER 0.25`, `CONGESTION 0.25`.
  - 변수 내 심각도: 운영시간 방문 불가 `1.0` / 폐점 임박 `0.5`; 날씨 위험 `1.0`; 혼잡 `CROWDED 1.0` / `SLIGHTLY_CROWDED 0.6`.

**Rationale**: detection용 종합 점수 가중치는 `spec`·`requirements.md`·`functional-spec.md`·`api-spec.md` 어디에도 확정돼 있지 않다(api-spec §7.2는 "추천 점수"용이며 F009 대상). spec FR-011은 값을 고정하지 말고 "한곳에서 조정 가능"하게만 두라고 요구하므로 `policy.py` 기본값으로 두고 `docs/planning/requirements.md`·`functional-spec.md` §4에 반영을 요청한다. 감지 결과 **생성 여부는 이 점수와 무관**하며 개별 변수 위험으로만 결정한다(FR-012, Clarifications).

**Alternatives considered**: api-spec §7.2 추천 가중치(거리 35/평점 30/혼잡 20/날씨 15) 재사용 — "거리·평점"은 감지에 의미가 없어 부적합.

---

## 8. 감지 결과 생성·중복 억제·종료

**Decision**:
- `fingerprint = f"{trip_day_id}:{item_id}"`. `detections`에 `status='ACTIVE'` 조건의 partial unique index(`er-schema.md` §8.1 "active fingerprint partial unique").
- 생성은 `INSERT ... ON CONFLICT (fingerprint) WHERE status='ACTIVE' DO UPDATE`(upsert): 이미 `ACTIVE` 행이 있으면 `eta`·`evaluation_snapshot`·`score`·`reason`·`last_evaluated_at`만 갱신하고 새 행을 만들지 않는다(FR-013·FR-016). 주기·재평가가 겹쳐도 두 번째 INSERT는 conflict로 흡수된다.
- 어느 변수도 위험이 아니면 행을 만들지 않는다. 이미 `ACTIVE` 행이 있는데 모든 위험이 사라지면 `evaluation_snapshot`·`score`를 갱신하되 `status`는 유지한다(사용자가 아직 볼 수 있어야 함; US2 Scenario 2). 종료는 아래 조건에서만.
- 종료(`status='INVALIDATED'`, `resolved_at=now`): 대상 항목이 `COMPLETED`·`SKIPPED`가 되거나 일정에서 제거되거나 그 날짜가 `COMPLETED`가 될 때(FR-014). 재평가·주기 진입 시 대상 목록과 대조해 처리한다.
- 재개: 날짜가 `COMPLETED`→`IN_PROGRESS`로 복귀하면(`detection_active=true`) 다음 주기에서 다시 `ACTIVE` 행이 생길 수 있다. `INVALIDATED` 행은 되살리지 않고 새로 만든다.

**Rationale**: `er-schema.md` §8.1이 `fingerprint` 컬럼과 active partial unique를 이미 규정한다. upsert는 constitution III의 멱등 요구를 DB 제약으로 보장하는 가장 단순한 방법이다.

**status 개념 매핑**: spec의 `pending`=`ACTIVE`(F008 생성), `결정됨`=`RESOLVED`/`DISMISSED`(F009·F010이 설정), `종료`=`INVALIDATED`(F008이 설정). F008은 `ACTIVE`·`INVALIDATED`만 쓰고 네 값을 모두 읽는다. FR-013의 "pending"은 `status='ACTIVE'`로 판정한다.

---

## 9. ETA 재계산 직후 재평가 (FR-003)

**Decision**: `evaluator.py`에 `reevaluate_day(session_factory, trip_day_id)` 공개 함수를 두고, 진행 상태 전환의 **commit 이후** `api/app/api/v1/progress.py`에서 호출한다(PROG-002가 도보 구간 계산을 transaction 밖에서 하는 것과 같은 후처리 패턴). 실패는 log만 남기고 진행 응답에 영향을 주지 않는다.
- 트리거: F006 수동 상태 전환(PROG-006), F006 시작(PROG-002). F007 자동 확정(PROG-004)도 같은 훅을 타야 하나 F007이 아직 `IN_PROGRESS`이므로 F008 범위에서는 F006 경로만 연결하고 F007 연결은 팀 공유사항·F007 tasks 반영 요청으로 남긴다.
- 재평가 범위: 그 날짜의 §2 대상 장소 전체(ETA가 바뀐 뒤 장소를 식별하는 별도 신호가 없으므로 날짜 단위로 다시 평가하는 편이 단순하고 안전하다). 장소가 날짜당 최대 10곳이라 비용이 작다.

**Rationale**: `recalculate_day_eta`는 여러 진입점에서 불리므로 그 함수 자체를 고치기보다 호출 지점 후단에 훅을 두는 편이 F006 코드 침습이 적다. commit 이후 실행이라 진행 transaction의 원자성·응답 지연에 영향이 없다(constitution I·III·IV).

**Alternatives considered**: `recalculate_day_eta` 내부에서 호출 — 외부 API 호출이 진행 transaction 안으로 들어와 응답을 5초씩 늦추고 실패가 진행을 막을 수 있음. 주기 작업만으로 처리 — "다음 정기 주기 전에"(FR-003·SC-001) 위반.

---

## 10. 조회 계약 정합 (api-spec DETECT-001·002·003)

**Decision**: 경로·메서드는 `api-spec.md` §7을 그대로 유지한다.
- `GET /api/v1/trips/{tripId}/detections` (cursor·limit) / `GET /api/v1/detections/{detectionId}` / `PATCH /api/v1/detections/{detectionId}/read`.
- `status` enum을 `er-schema.md` §10에 맞춰 `ACTIVE`·`RESOLVED`·`DISMISSED`·`INVALIDATED`로 한다(현재 api-spec 예시의 `PENDING_DECISION`을 대체).
- 상세 응답 `variables` 확장(FR-015 "각 변수의 사용 가능 여부와 판정 내용", "방문 불가 여부"):
  - `operatingHours`: `available`, `closesAt`, `closingSoon`, `visitBlocked`, `tempClosed`.
  - `weather`: `available`, `precipitationProbability`, `precipitationMmPerHour`, `precipitationType`(NONE/RAIN/RAIN_SNOW/SNOW/SHOWER).
  - `congestion`: `available`, `level`(RELAXED/NORMAL/SLIGHTLY_CROWDED/CROWDED), `sensitivity`(HIGH/MEDIUM).
  - 각 변수 `available=false`일 때 `unavailableReason`(NO_FORECAST/NOT_IN_SUPPORT_AREA/HOURS_UNKNOWN/INDOOR/TIMEOUT) 선택 필드(FR-023 추적성).
- 목록 정렬: `detected_at DESC, detection_id`. cursor는 이 튜플을 base64로 인코딩한 불투명 토큰.
- `read`는 `read_at`을 서버 시각으로 채우고, 이미 읽음이면 그대로 200(멱등).

**Doc-sync 조건**: 위 enum·필드 변경을 구현 PR과 **같은 PR**에서 `api-spec.md` §7(DETECT-001·002·003 예시와 응답 표, §7.1 문구)에 반영한다. `er-schema.md` §8.1과 §10은 이미 구현 방향과 일치하므로 수정 불필요. §7.1의 "임계값 초과 시 장소 변경 제안 알림을 1회 생성한다"는 F011 알림 조건으로 읽고, 오해를 없애도록 문구를 다듬는다(spec Clarifications).

**Rationale**: 나머지 진행 API가 raw enum(`IN_PROGRESS`, `PENDING_CONFIRMATION`)을 그대로 노출하므로 감지도 `er-schema` 저장 enum을 그대로 쓰는 것이 일관적이고, F009·F010·F011이 같은 값을 참조하게 된다. spec Assumptions가 "경로·응답 형태는 plan 단계에서 확정한다"고 이미 위임했다.

---

## 11. 설정·비밀값

**Decision**: `app/core/config.py`에 추가한다.
- `kma_base_url: str = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0"`, `kma_service_key: SecretStr`.
- `seoul_citydata_base_url: str = "http://openapi.seoul.go.kr:8088"`, `seoul_citydata_api_key: SecretStr`.
- `detection_provider_timeout_seconds: float = Field(default=5.0, gt=0)` (기상청·서울시 공통, `requirements.md` §3).
- `detection_cycle_seconds: int = Field(default=600, gt=0)`.
- 운영시간은 기존 `google_places_*` 설정을 재사용한다.
- 키가 비어 있으면 해당 변수는 항상 제외(FR-009)되며 앱은 정상 기동한다(다른 선택 provider 키와 동일 취급).

**Rationale**: 기존 `Settings` 패턴(`SecretStr`, `*_timeout_seconds` float, base_url 기본값)과 동일. `.env`로 주입.

---

## 12. 추적 기록 (FR-023)

**Decision**: `detections.evaluation_snapshot`(jsonb)에 변수별 `{available, raw_values, threshold, weight, verdict, unavailable_reason}`와 평가 시각·사용한 ETA를 담는다. `detected_at`·`last_evaluated_at`·`eta` 컬럼으로 생성·최근 평가·기준 시점을 남긴다. log에는 `detection_id`·`item_id`·`trip_day_id`·변수별 verdict·`unavailable_reason`만 남기고 사용자 좌표·정밀 위치·이동 경로는 남기지 않는다(constitution V). 외부 호출에는 장소 좌표만 보낸다.

**Rationale**: `er-schema.md` §8.1이 `evaluation_snapshot`·`detected_at`·`last_evaluated_at`을 이미 정의한다. `detection_signals`는 별도 테이블 없이 이 스냅샷에 저장한다(`er-schema.md` "테이블로 만들지 않는 것").

---

## 후속 처리 (명세 수정 불필요 · 구현 PR·팀 공유로)

1. ~~spec FR-001·FR-014 문구 조정~~ — **완료(2026-09-08 plan 반영, §2)**.
2. **doc-sync**: 구현 PR(T030)에서 `api-spec.md` §7 enum·상세 필드·§7.1 문구 반영 (§10).
3. **계약 추가 요청 기록**: F003 장소 상세에 `regularOpeningHours.periods`·`businessStatus` 구조화 노출 요청 (§5). spec FR-008·FR-010·Assumptions에 F008이 직접 조회함을 명시했고(`/speckit-analyze` I1 반영), `requirements.md` §3에 운영시간 provider 정책 행 추가는 T031·T030에서 수행.
4. **정책값 반영 요청**: 혼잡 경계·종합 점수 가중치·심각도·실내외 매핑을 `requirements.md`/`functional-spec.md` §4에 반영 (§6·§7, T031). spec Assumptions에 "확정 문서 없음, 조정 가능 설정으로 둠"을 명시함.
5. **F007 연결**: 자동 확정 후 `reevaluate_day` 호출을 F007 tasks에 반영 요청 (§9, T024 검증 노트).
