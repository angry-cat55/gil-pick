# Phase 0 Research: F007 위치 기반 감지

`spec.md`의 요구를 F006 구현 위에 얹으면서 남는 기술 결정을 정리한다. 감지 반경·대기 시간 같은 값은 `docs/planning/requirements.md`가 이미 확정했으므로 여기서 다시 정하지 않는다.

## 1. 위치 이벤트 생성 방식

**Decision**: Play Services **Geofencing API**로 대상 장소에만 지오펜스를 등록하고, `DWELL`(도착 판정)과 `EXIT`·`ENTER`(출발 판정과 재진입 취소)를 받아 서버로 올린다. 도착용은 반경 300m·`loiteringDelay` 5분, 출발용은 반경 400m로 같은 장소에 두 개를 등록한다. 재진입 취소는 출발용 지오펜스의 `ENTER`를 `REENTER` 이벤트로 올려 처리한다.

**Rationale**:

- 앱이 화면에 없을 때도 감지되어야 한다(spec Assumptions). 지오펜스는 OS가 관리하므로 앱이 살아 있지 않아도 broadcast로 깨어난다. 자체 주기 위치 수집은 같은 결과를 얻으려면 foreground service와 배터리 예산이 필요하다.
- `DWELL`의 `loiteringDelay`가 "반경 안에서 N분 머무름"을 그대로 표현한다. 앱이 진입 시각을 직접 기억하고 세는 것보다 조건이 단순하고, 프로세스가 죽어도 유지된다.
- `play-services-location`은 F006이 시작 시 1회 위치 취득 때문에 이미 추가했다. 새 의존성이 없다.
- 한 날짜에 최대 10장소 × 2개 = 20개로, 앱당 지오펜스 100개 상한 안이다. 감지 대상은 진행 중인 한 날짜뿐이므로(spec Assumptions) 상한에 닿지 않는다.

**Alternatives considered**:

- **Foreground service + 주기적 위치 수집**: 판정 규칙을 앱이 모두 들고 있어야 하고, 지속 알림이 필요하며 배터리 소모가 크다. 위치 데이터를 필요 이상으로 받게 되어 constitution V(최소 데이터)와도 어긋난다.
- **`ENTER` + 앱 타이머로 5분 세기**: 프로세스가 죽으면 타이머가 사라져 도착을 놓친다.
- **서버 주기 폴링**: 앱이 위치를 계속 올려야 하므로 위 두 문제를 합친 형태가 된다.

## 2. 무응답 자동 확정을 누가 언제 실행하는가

**Decision**: **지연 확정(lazy finalize)**. 서버는 후보를 만들 때 `auto_finalize_at`만 저장하고 별도 작업자를 두지 않는다. 그 날짜에 대한 다음 요청(진행 조회 PROG-001, 위치 이벤트 등록, 확인 응답, 상태 전환)을 처리할 때 **가장 먼저** 만료된 `PENDING_CONFIRMATION` 후보를 확정 처리한 뒤 본래 요청을 이어서 처리한다. 앱은 `auto_finalize_at`에 맞춰 로컬 알람을 걸고 그 시각에 진행을 한 번 다시 조회해 확정을 유도한다.

**Rationale**:

- `docs/planning/mvp.md`가 **Celery와 분산 작업 중복 방지를 MVP 제외**로 명시했다. 주기 작업자를 도입하면 MVP 범위를 벗어난다.
- 확정 시각 판정은 저장된 `auto_finalize_at`과 서버 시각 비교이므로, 실제 확정을 언제 실행하든 **결과가 같다**. 늦게 실행돼도 확정 시각과 `undo_deadline`은 원래 값으로 기록한다.
- ETA와 진행 상태는 조회 시점에 확정을 반영한 값으로 내려가므로 사용자가 보는 화면은 일관된다.
- 트랜잭션 하나 안에서 "만료 확정 → 요청 처리"를 함께 처리하면 중간 상태가 노출되지 않는다.

**Trade-off (기록)**: 아무 요청도 오지 않으면 DB의 후보는 `PENDING_CONFIRMATION`으로 남는다. 사용자에게 보이는 값은 다음 조회에서 확정 후 상태로 나타나므로 화면상 문제는 없지만, DB만 직접 보면 확정이 지연된 것처럼 보인다. F011이 들어와 서버가 알림을 밀어 줄 수 있게 되면 그 시점에 확정을 함께 실행하는 방식으로 옮길 수 있다.

**Alternatives considered**:

- **Celery beat 주기 작업**: MVP 제외 항목이다.
- **앱이 확정 요청을 보내는 방식**: 앱이 죽어 있거나 네트워크가 없으면 확정이 안 된다. 확정 권한을 클라이언트에 두면 constitution I("위치 이벤트만으로 도메인 상태를 즉시 확정하지 않는다")의 취지에도 어긋난다.
- **PostgreSQL `pg_cron`**: 운영 환경에 확장 설치가 필요하고 G002 운영 준비 범위를 넓힌다.

## 3. 재질문·되돌리기 후 재개·출발 감지 중단을 어디에 저장하는가

**Decision**: **새 컬럼을 만들지 않고 `progress_transitions`에서 파생 계산**한다.

| 규칙 | 판정 방법 |
|---|---|
| 장소별 질문 횟수 상한(FR-008) | 그 item의 `ARRIVAL` transition 중 후보로 생성된 것의 개수 |
| `아직이에요` 후 재질문 가능 시각(FR-007) | 마지막 `decision=NOT_ARRIVED`의 `cancelled_at` + 재질문 간격 |
| 되돌린 뒤 감지 재개 시각(FR-017a) | 그 item·그 종류의 마지막 `undone_at` + 재질문 간격 |
| `아직 머무는 중` 이후 자동 출발 중단(FR-013) | 그 item에 `decision=STILL_HERE`인 `DEPARTURE` transition 존재 여부 |
| 두 번째 출발 되돌리기 이후 중단(FR-017a) | 그 item의 `DEPARTURE` transition 중 `undone_at`이 있는 것의 개수 ≥ 2 |

**Rationale**:

- 다섯 규칙 모두 "그 item에 대해 지금까지 무슨 일이 있었는가"의 함수다. `progress_transitions`가 이미 그 이력을 전부 담고 있어(ERD 7.2) 상태를 두 곳에 두면 어긋날 여지만 생긴다.
- item당 transition 수가 한 자리라 매 판정마다 조회해도 비용이 없다.
- constitution III의 추적 가능성 요구와 같은 자료를 쓰므로 근거와 판정이 분리되지 않는다.

**Alternatives considered**: `itinerary_items`에 `arrival_prompt_count`·`departure_detection_disabled`·`detection_paused_until` 컬럼 추가 — 이력과 파생 상태가 이중화되고, 상태 수정·되돌리기마다 두 곳을 함께 되돌려야 한다.

## 4. 감지 대상 갱신과 지오펜스 재등록 시점

**Decision**: 앱은 **진행 상태 응답에 담긴 감지 대상 목록**만 보고 지오펜스를 다시 등록한다. 서버가 진행 조회(PROG-001) 응답에 "지금 감지해야 할 장소와 종류"를 내려주고, 앱은 받은 목록과 현재 등록 상태가 다를 때만 차이를 반영한다. 진행 조회는 F006이 이미 앱 재개(`onResume`)마다 수행한다.

**Rationale**:

- 감지 대상은 상태 전환·일정 변경(FR-023)·되돌리기 쉬는 시간·`아직 머무는 중` 선택에 따라 바뀐다. 그 판정 규칙은 모두 서버에 있으므로(3절) 앱이 같은 규칙을 복제하면 두 곳이 갈라진다.
- 앱은 "무엇을 감지할지"를 계산하지 않고 등록만 한다. 서버가 계약으로 알려 주는 형태라 F006의 "앱은 서버 상태를 그대로 표시한다"(FR-021)와 같은 방향이다.

**Alternatives considered**: 앱이 일정과 상태로 대상을 직접 계산 — 위 다섯 파생 규칙을 클라이언트에도 구현해야 한다.

## 5. 위치 이벤트 검증과 중복 처리

**Decision**: 앱이 보내는 모든 이벤트를 `progress_events`에 저장하되, 정확도 초과·서버 수신 기준 유효 시간 초과는 `accepted=false`와 `rejection_reason`으로 남기고 판정에 쓰지 않는다(ERD 7.1). 중복은 앱이 만든 `client_event_id`의 `UNIQUE` 제약으로 막고, 같은 ID의 재전송에는 최초 처리 결과를 그대로 돌려준다.

**Rationale**:

- 거부된 이벤트를 오류로 돌려주면 앱이 재시도를 반복한다. `202`류의 "받았지만 쓰지 않음"이 실제 동작에 맞고 api-spec.md도 같은 방침을 적어 뒀다.
- 저장해 두면 반경·정확도 기준을 실제 검증으로 조정할 때(FR-002) 근거 자료가 된다.
- 지오펜스 broadcast는 OS가 중복 전달할 수 있어 클라이언트 생성 ID가 필요하다. constitution III의 멱등성 요구와 같다.

**보존 범위**: `progress_events`는 판정 근거이므로 좌표를 저장하지만, 이동 경로를 별도로 축적하지 않는다(FR-026). log에는 좌표를 남기지 않고 이벤트 ID·판정 결과만 남긴다(constitution V, F006과 같은 규칙).

## 6. 자동 전환의 상태 계산을 F006과 어떻게 공유하는가

**Decision**: 확정 시 실제 상태 변경은 **F006 `progress` service의 전환 함수를 그대로 호출**한다. F007은 "언제 전환할지"만 정하고 "전환하면 무엇이 바뀌는지"는 F006 규칙을 재사용한다. `transition_type`은 F007이 `ARRIVAL`·`DEPARTURE`·`COMPOSITE`를, `source`는 `GEOFENCE_DWELL`·`GEOFENCE_EXIT`·`GEOFENCE_CONFIRMED`·`GEOFENCE_AUTO`를 쓴다(ERD 10절 enum에 이미 있다).

**Rationale**:

- spec FR-006·FR-014가 "F006의 수동 도착·출발과 같은 규칙"을 요구한다. 파생 전환·ETA 재계산·당일 완료 판정을 F007이 다시 구현하면 두 경로가 갈라진다.
- 가까운 다음 장소(FR-009)는 이전 완료와 다음 도착을 한 transaction으로 적용해야 하는데(constitution III), F006이 이미 `affected_items`에 여러 item을 기록하는 구조를 갖고 있다. `COMPOSITE` 유형으로 같은 경로를 쓴다.

## 7. 되돌리기의 복원 범위

**Decision**: 되돌리기는 `affected_items`에 기록된 **그 transition이 바꾼 전부**를 `beforeStatus`로 되돌리고, 날짜 상태 스냅샷이 있으면 그것도 함께 복원한 뒤 ETA를 다시 계산한다. 되돌리기 자체도 `status=UNDONE`으로 기록하고 `progress_version`을 올린다.

**Rationale**:

- spec US2 Acceptance 4·5가 묶인 변경 전체와 당일 완료 해제를 요구한다. ERD 7.2가 "마지막 장소 전환에는 날짜 상태와 감지 활성값의 전후 스냅샷도 함께 기록한다"고 이미 정해 뒀다.
- 되돌린 뒤 상태는 확정 직전과 같아야 하므로 ETA도 다시 계산해야 한다. 이는 F006의 재계산 함수를 그대로 부르면 된다.

**만료 판정**: `undo_deadline`과 서버 시각을 비교한다(FR-018). 앱은 남은 시간을 표시만 하고 판정하지 않는다.

## 8. 백그라운드 위치 권한 요청 흐름

**Decision**: 두 단계로 나눈다. F006이 이미 받는 앱 사용 중 위치 권한을 전제로, 진행을 시작한 뒤 자동 감지를 켤 때 백그라운드 위치 권한을 별도로 요청한다. 거부하면 자동 감지만 끄고 진행은 그대로 둔다(FR-024). 권한 상태는 진행 화면에서 원인과 함께 안내한다(FR-025).

**Rationale**:

- Android 11 이상은 백그라운드 위치를 앱 사용 중 권한과 같은 화면에서 함께 요청할 수 없고, 시스템 설정으로 보내는 흐름이 필요하다. 시작 시점에 한꺼번에 요청하면 거부율이 높고 F006 시작 흐름까지 막힌다.
- 자동 감지는 부가 기능이므로 진행 시작 자체를 권한에 묶지 않는다(constitution I).

**Alternatives considered**: 진행 시작 전에 요청 — 아직 필요하지 않은 권한을 먼저 물어 거부를 유도한다.

## 9. 미해결 기술 항목

없다. `spec.md`의 미확정 2건은 2026-09-08 팀 결정으로 닫혔고(FR-015a·FR-017a), 그 결정이 이 문서의 2절·3절 설계에 반영돼 있다.

**Backend 담당자(ts) 확인 필요 항목**

1. 2절 지연 확정 방식과 그 trade-off(요청이 없으면 DB상 확정이 미뤄짐)를 수용하는지.
2. 3절대로 새 컬럼 없이 `progress_transitions` 파생 계산만 쓰는지.
3. migration 006은 `progress_events` 신설과 `progress_transitions.trigger_event_id` FK 연결만 담는다는 범위.

**Frontend 담당자(hs) 확인 필요 항목**

1. 4절대로 감지 대상 목록을 서버 응답에서만 받아 지오펜스를 등록하는지.
2. 8절 백그라운드 권한 2단계 요청 흐름과 거부 시 안내 위치.
