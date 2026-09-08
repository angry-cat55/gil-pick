# Implementation Plan: 여행 변수 감지

**Branch**: `docs/ts-f008-variable-detection-spec` (Spec Kit 논리 식별자 `008-variable-detection`) | **Date**: 2026-09-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-variable-detection/spec.md`

## Summary

진행이 시작된 당일 날짜의 `완료`·`건너뜀`이 아닌 남은 장소를 대상으로, 각 장소의 **F006 ETA 시점**을 기준으로 혼잡·날씨·운영시간 세 변수를 평가한다. 평가는 (1) 애플리케이션 프로세스 안에서 도는 10분 주기 작업(`auth_cleanup`과 같은 `asyncio` 루프)과 (2) F006/F007이 ETA를 다시 계산한 직후의 재평가 훅, 두 경로로 실행한다. 어느 한 변수라도 위험이면(`OPERATING_HOURS` 방문 불가·폐점 임박, `WEATHER` 위험, `CONGESTION` 혼잡) 그 장소에 대한 `detections` 행 하나를 만들고, 이미 `ACTIVE` 행이 있으면 만들지 않고 변수 스냅샷과 종합 위험 점수만 갱신한다. 장소가 `완료`·`건너뜀`이 되거나 일정에서 빠지거나 당일이 완료되면 그 행을 `INVALIDATED`로 종료한다.

외부 데이터는 **기상청 단기예보**(날씨)와 **서울시 실시간 도시데이터**(혼잡)를 각 5초·1회 재시도로 호출하고, 실패하면 그 변수만 평가에서 빼고 나머지로 계속한다. 세 변수를 모두 확보하지 못하면 그 장소에 대한 행을 만들지 않는다. 서울시 혼잡 지원 지점 좌표는 **버전 관리된 설정 파일**로 두고 장소와의 500m 공간 판정에 쓴다. 운영시간의 구조화된 폐점 시각과 장소의 실내·실외 구분은 현재 계약에 없으므로, 이 plan에서 **파생 규칙 + 계약 추가 요청**으로 처리한다(아래 Constitution Check II, research.md 참조).

조회 계약은 `api-spec.md`의 DETECT-001·DETECT-002·DETECT-003을 그대로 쓰되, `status` enum을 `er-schema.md` §10(`ACTIVE`·`RESOLVED`·`DISMISSED`·`INVALIDATED`)에 맞추고 상세 응답에 `visitBlocked`·`precipitationType`·`sensitivity`를 더한다. 이 문서 차이는 같은 PR에서 `api-spec.md` §7을 고쳐 동기화한다. 신규 테이블은 `detections` 하나(migration 007)이며 Android 작업은 없다.

## Technical Context

**Language/Version**: Backend Python 3.13 (FastAPI). Android 변경 없음

**Primary Dependencies**: FastAPI, SQLAlchemy 2 async, GeoAlchemy2, Alembic, `httpx`, pydantic-settings — 모두 재사용. **새 의존성 없음**. 기상청·서울시 호출은 기존 `httpx` 기반 client 패턴(`app/clients/*`)을 따른다

**Storage**: PostgreSQL/PostGIS. 신규 `detections` 테이블(migration 007). `itinerary_items.estimated_arrival_at`(ETA), `trip_days.detection_active`, `itinerary_items.status`, `places.location`·`places.category`는 읽기만 한다. 서울시 혼잡 지원 지점은 DB 테이블이 아니라 `app/services/detection/` 아래 버전 관리된 설정(JSON/py)로 둔다(`er-schema.md` "테이블로 만들지 않는 것")

**Testing**: pytest unit/contract/integration. 외부 호출은 `httpx` mock transport와 계약 fixture로 검증한다(기상청·서울시 실제 연동은 G001 Gate). 서버 시각을 고정해 ETA 경계(폐점 시각, 폐점 30분 전)를 검증한다

**Target Platform**: Linux API 서버 단일 인스턴스. 다중 서버·분산락은 범위 밖

**Project Type**: REST API 단독 기능(Android 없음)

**Performance Goals**: 감지 목록·상세 조회 응답 2초 이내(대상 장소가 날짜당 최대 10곳이라 상수 규모). 10분 주기 1회 실행은 진행 중 여행 수 × 남은 장소 수에 비례하며, 각 장소당 외부 호출 2건(각 5초 상한)을 초과하지 않는다

**Constraints**: 시각 판정은 서버 시각·`Asia/Seoul`(constitution III). 선택적 외부 데이터 실패는 해당 변수에만 격리(constitution IV, FR-009·FR-010). DETECT 3개 endpoint는 F006 소유권 검증을 재사용(constitution V, FR-020). 정밀 위치·이동 경로를 `detections`·log에 남기지 않음(constitution V, FR-023). 감지 실패가 F006 진행이나 F007 감지를 막지 않음(constitution I). 한 장소에 `ACTIVE` `detections`는 최대 1행(FR-013·FR-016)

**Scale/Scope**: 신규 endpoint 3개(DETECT-001·002·003), 신규 테이블 1개(migration 007), 신규 client 2개(`kma`, `seoul_citydata`), 신규 job 1개(`variable_detection`), 평가 orchestrator·변수별 평가기·정책 설정 모듈. F006 진행 전환 경로에 ETA 재계산 직후 재평가 훅 1곳 추가(F006 파일 수정 → 원 담당자 review). 대체 장소(F009)·일정 변경(F010)·푸시 알림(F011)·인앱 배너 표시는 제외

## UI Implementation & Validation

**해당 없음.** F008은 사용자 화면 요소를 구현하지 않는다. 감지 결과는 DETECT-001·002·003 API로만 제공하고, 진행 화면(ActiveTravelScreen)의 변수 경고 배너·날씨 안내 등 인앱 표시는 spec FR-021과 Clarifications(Session 2026-09-08)에 따라 F011 또는 별도 Frontend Issue 범위다. 따라서 theme token·재사용 component·`loading/empty/error/content` 상태·접근성·screenshot 검증 항목은 이 Feature에 적용되지 않는다. API 응답을 소비하는 화면을 만드는 후속 Feature가 `docs/design/ui-guidelines.md`와 Figma를 기준으로 그 검증을 수행한다.

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 감지는 읽기 전용 조언이며 도메인 상태를 바꾸지 않는다. 외부 데이터가 없거나 느려도 해당 변수만 빼고 평가를 계속하며(FR-009·FR-010), 세 변수 모두 실패하면 조용히 건너뛴다(오류 아님). 주기 작업 실패는 log만 남기고 다음 주기로 넘긴다(`auth_cleanup`과 동일). F006 수동 진행·조회는 F008과 무관하게 동작한다. | PASS |
| II. 계약 우선 SDD와 문서 동기화 | DETECT-001·002·003 경로·응답은 `api-spec.md` §7을 기준으로 하되, `status` enum을 `er-schema.md` §10에 맞추고 상세에 `visitBlocked`·`precipitationType`·`sensitivity`를 추가한다. **같은 PR에서** `api-spec.md` §7의 `PENDING_DECISION` 예시와 응답 표를 고쳐 동기화한다. 구조화된 폐점 시각·실내외 구분은 계약에 없으므로 research.md의 파생 규칙을 쓰고, F003/장소 계약에 구조화 필드 추가를 별도 요청으로 남긴다(임의 값 생성 금지, constitution IV·spec Assumptions). `detections` 구현은 `er-schema.md` §8.1·§10을 그대로 따른다. | PASS WITH DOC-SYNC CONDITION |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | 한 장소의 `ACTIVE` 감지 유일성은 `fingerprint`(=`{trip_day_id}:{item_id}`)의 partial unique index(`WHERE status='ACTIVE'`)로 보장하고, 생성은 upsert(충돌 시 갱신)로 처리해 주기·재평가가 겹쳐도 중복이 생기지 않는다(FR-016). 평가·갱신·종료는 각각 한 transaction이며 부분 상태를 남기지 않는다. `evaluation_snapshot`(변수별 원값·가용성·가중치·판정 근거), `detected_at`, `last_evaluated_at`, `eta`로 근거를 추적 가능하게 남긴다(FR-023). 모든 시각·경계 판정은 서버 시각·`Asia/Seoul`. | PASS |
| IV. 외부 의존성 실패 격리 | 기상청·서울시 호출은 각 5초·1회 재시도, 최종 실패 시 그 변수만 제외(`requirements.md` §3, FR-010). 한 변수 결손이 다른 변수·다른 장소·다른 여행의 평가를 실패시키지 않는다(FR-009). 타임아웃도 결손과 같게 처리한다. 세 변수 모두 없으면 감지 결과를 만들지 않고 오류로도 처리하지 않는다. | PASS |
| V. 보안·소유권·최소 데이터 | DETECT-001은 `trip → user`, DETECT-002·003은 `detection → trip_day → trip → user` 소유권을 검증하고 타인 데이터 요청은 403/404로 거절한다(FR-020). `detections`와 log에는 장소 식별자·판정 결과·변수 가용성만 남기고 사용자 정밀 위치·이동 경로를 저장하지 않는다(FR-023). 외부 호출에 사용자 좌표를 보내지 않고 장소 좌표만 사용한다. | PASS |
| 교차 계약 review | DETECT DTO(특히 `status` enum·상세 확장 필드), `api-spec.md` §7 동기화, `detections` migration, ETA 재계산 직후 재평가 훅을 BE 구현 담당(`jh`)이 맡고, DETECT 계약이 화면에서 쓸 만한지 FE 담당(F011 담당 예정, 현재 미정)이 문서 PR·`/speckit-analyze` 단계에서 review한다. F006 진행 파일 수정은 원 담당자(`jy`) review를 받는다. | PASS WITH REVIEW CONDITION |

**위반 없음.** Complexity Tracking 비움.

### Post-Design Re-check

Phase 1 산출물(data-model.md·contracts/detections.openapi.yaml·quickstart.md) 작성 후 재평가함. 신규 위반 없음. `detections` 스키마는 `er-schema.md` §8.1과 필드·제약이 일치하고, 조회 계약은 DETECT-001·002·003 경로를 유지하며 enum·확장 필드 차이는 doc-sync 조건으로 관리한다. 파생 규칙(폐점 시각 파싱, 실내외 분류, 종합 점수 가중치)은 값을 코드가 아닌 버전 관리 설정 모듈에 두어 "한곳에서 조정"(FR-011) 요건을 만족한다.

## Project Structure

### Documentation (this feature)

```text
specs/008-variable-detection/
├── plan.md              # 이 파일
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── contracts/
│   └── detections.openapi.yaml   # Phase 1 산출물 (DETECT-001·002·003)
├── checklists/
│   └── requirements.md  # /speckit-clarify 재검증 완료
└── tasks.md             # /speckit-tasks 산출물 (이 명령이 만들지 않음)
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── models/
│   │   └── detection.py              # 신규: Detection ORM (er-schema §8.1)
│   ├── schemas/
│   │   └── detection.py              # 신규: DETECT-001·002·003 공개 계약 + enum
│   ├── services/
│   │   └── detection/
│   │       ├── __init__.py
│   │       ├── evaluator.py          # 신규: 대상 선별 + 세 변수 평가 orchestrator + 생성/갱신/종료
│   │       ├── congestion.py         # 신규: 500m 지원지점 매핑 + 서울시 예보 → 혼잡 판정
│   │       ├── weather.py            # 신규: 기상청 예보 → 강수 판정 + 실내외 게이트
│   │       ├── operating_hours.py    # 신규: 폐점 시각 파생 + 방문 불가/폐점 임박 판정
│   │       ├── scoring.py            # 신규: 종합 위험 점수(가중치·비례 재정규화·반올림)
│   │       ├── policy.py             # 신규: 임계값·가중치·카테고리 민감도·실내외 매핑 (버전 관리 설정)
│   │       └── congestion_areas.json # 신규: 서울시 혼잡 지원 지점 좌표 (버전 관리 설정)
│   ├── clients/
│   │   ├── kma.py                    # 신규: 기상청 단기예보 client (5초·1회 재시도)
│   │   └── seoul_citydata.py         # 신규: 서울시 실시간 도시데이터 client (5초·1회 재시도)
│   ├── jobs/
│   │   └── variable_detection.py     # 신규: 10분 주기 루프 (run_auth_cleanup 패턴)
│   ├── api/v1/
│   │   └── detections.py             # 신규: DETECT-001·002·003 router
│   ├── api/v1/progress.py            # 수정: ETA 재계산 직후 재평가 훅 호출 (F006 파일 → jy review)
│   ├── core/config.py               # 수정: 기상청·서울시 base_url/key/timeout 설정 추가
│   └── main.py                      # 수정: lifespan에 variable_detection 작업 등록, router include
└── migrations/versions/
    └── 007_create_detections_table.py   # 신규
```

**Structure Decision**: 기존 단일 `api/` FastAPI 레이아웃을 그대로 쓴다. 변수별 평가 로직이 세 종류라 `services/detection/` 하위 패키지로 묶고, 조정 가능한 값은 전부 `policy.py`·`congestion_areas.json`에 모아 FR-011("한곳에서 관리")을 구조로 보장한다. 주기 작업은 신규 실행기 없이 `app/jobs/`의 기존 `asyncio` 루프 패턴을 재사용한다. Android 모듈은 건드리지 않는다.

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없어 비워 둔다.

## 담당 합의 (SDD 6절 5단계)

| 영역 | 담당 | 범위 |
|---|---|---|
| Backend | `jh`(유지환) | `detections` 모델·migration 007, 평가 orchestrator·변수 평가기·`policy`/`scoring`, 기상청·서울시 client, 10분 주기 job, DETECT-001·002·003, ETA 재평가 훅, `api-spec.md` §7 동기화 |
| Frontend | 필요 없음 | F008은 화면 요소를 구현하지 않는다. 감지 결과를 보여줄 화면은 F011 또는 별도 Frontend Issue. DETECT 계약 review만 FE 담당(미정)이 수행한다 |
| F006 파일 수정 review | `jy`(원 담당자) | `api/app/api/v1/progress.py`의 재평가 훅 추가 |

- 이 합의는 `/speckit-tasks`의 담당자 이니셜과 `/speckit-taskstoissues` 후 GitHub Issue assignee에 반영한다.
- Feature Owner는 `ts`(명세 산출물 담당), 구현 담당은 `jh`로 별개다.
