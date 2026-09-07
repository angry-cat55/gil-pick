# Implementation Plan: 위치 기반 감지

**Branch**: `docs/hs-f007-location-detection-spec` (Spec Kit 논리 식별자 `007-location-detection`) | **Date**: 2026-09-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-location-detection/spec.md`

## Summary

앱이 진행 중인 날짜의 대상 장소에만 지오펜스를 등록해 `DWELL`·`EXIT`·`REENTER`를 받고, 서버가 그 이벤트를 검증해 도착·출발 후보(`progress_transitions.status=PENDING_CONFIRMATION`)를 만든다. 사용자가 확인 시트에서 답하면 그 즉시, 답하지 않으면 `auto_finalize_at`이 지난 뒤 확정한다. 확정 시 상태 변경·ETA 재계산·당일 완료는 **F006의 전환 함수를 그대로 호출**해 규칙이 갈라지지 않게 한다. 자동 확정에만 `undo_deadline`을 두어 되돌리기를 제공하고, 되돌리기는 `affected_items` 전부와 날짜 상태를 복원한다.

주기 작업자를 두지 않는다(MVP 제외 항목). 확정은 그 날짜에 대한 다음 요청을 처리할 때 먼저 실행하는 **지연 확정**으로 처리하고, 앱이 `auto_finalize_at`에 로컬 알람으로 재조회를 유도한다. 질문 횟수 상한·재질문 시각·되돌린 뒤 재개 시각·`아직 머무는 중` 중단은 새 컬럼 없이 `progress_transitions` 이력에서 파생 계산한다. 신규 테이블은 `progress_events` 하나다.

## Technical Context

**Language/Version**: Backend Python 3.13, Android Kotlin/JVM 17

**Primary Dependencies**: FastAPI, SQLAlchemy 2 async, GeoAlchemy2, Alembic (모두 재사용); Jetpack Compose, Navigation Compose, Retrofit/OkHttp, **`play-services-location`의 Geofencing API**(F006이 이미 추가한 의존성). **새 의존성 없음**

**Storage**: PostgreSQL/PostGIS. 신규 `progress_events`(migration 006)와 `progress_transitions.trigger_event_id` FK 연결. F006 migration 005가 만든 `progress_transitions`의 F007용 컬럼(`decision`·`auto_finalize_at`·`undo_deadline`·`cancelled_at`·`undone_at`)은 그대로 사용하며 컬럼을 추가하지 않는다

**Testing**: pytest unit/contract/integration(PostgreSQL, 서버 시각 고정으로 만료 검증); JUnit4/coroutines-test/MockWebServer, Compose UI·screenshot test, `adb emu geo fix`로 지오펜스 발화 확인

**Target Platform**: Linux API 서버, Android minSdk 26/targetSdk 36

**Project Type**: Android 모바일 앱 + REST API

**Performance Goals**: 위치 이벤트 등록 응답 2초 이내; 확인 응답·되돌리기 2초 이내; 지연 확정을 포함해도 진행 조회 응답 2초 이내. 대상 장소가 날짜당 최대 10곳이라 후보·이력 조회는 상수 규모

**Constraints**: 확정·되돌리기·복합 전환은 한 transaction(constitution III); 시각 판정은 서버 시각·`Asia/Seoul`; 위치 좌표를 log에 남기지 않음(constitution V); 자동 감지 실패가 F006 수동 진행을 막지 않음(constitution I); 앱당 지오펜스 20개(10장소 × 2종류)

**Scale/Scope**: Backend endpoint 3개 신설(PROG-003·004·005) + F006 PROG-001 응답 확장, migration 1개; Android는 `progress` package에 지오펜스 등록·이벤트 전송·확인 시트·되돌리기 토스트 추가. 변수 감지(F008), 대체 장소·일정 변경(F009·F010), 푸시 알림(F011) 제외

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`, Figma Make 사본 `ActiveTravelScreen.tsx`(도착 확인 시트·되돌리기 토스트 정본), `LocationPermissionScreen.tsx`(권한 안내). F006이 구현 범위에서 제외한 요소 중 **도착 확인 시트와 되돌리기 토스트가 F007 범위**다.

**Figma 확인·추가 필요 항목**: 저장소 사본에는 도착 확인 시트(`북촌한옥마을에 도착하셨나요?`, 감지 근거 문구, `4분 뒤 자동으로 도착 처리돼요` + 카운트다운, `네, 도착했어요`·`아직이에요`)와 되돌리기 토스트(문구 + 남은 초 + `되돌리기`)가 있다. **출발 확인 시트는 없다.** F006이 상태 수정 시트를 추가할 때 쓴 절차와 같게, 구현 전에 Figma `ActiveTravelScreen`에 도착 시트와 같은 구조의 출발 확인 시트(`출발했어요`·`아직 머무는 중`)를 추가하고 사본을 갱신한 뒤 그 모양을 따른다(spec UI-002). 자동 처리 표시(UI-004)와 자동 감지 꺼짐 안내(UI-005)도 Figma에 없으면 같은 절차로 추가한다.

**Tokens & Components**: 기존 `com.gilpick.ui.theme` token만 사용하고 새 token을 만들지 않는다. 재사용: F006 `ActiveTravelScreen` 구조와 `StatusSheet`의 `ModalBottomSheet` 패턴, F006 `ProgressLabels`의 상태·시각 문구, F002/F004의 상태 칩 규칙(문구+아이콘 병기). 신규: `ArrivalDepartureConfirmSheet`(두 종류를 한 composable로, 행동 라벨만 다름), `UndoToast`. 두 요소 모두 진행 화면 안에 두고 별도 화면을 만들지 않는다.

**State & Interaction**: F006 `ProgressUiState.Content`에 `pendingCandidate`와 `undoable`을 더한다. 두 값 모두 서버 응답에서만 오고 앱이 추정하지 않는다(F006 FR-021과 같은 규칙). 확인 응답·되돌리기 요청 중에는 해당 요소의 버튼만 잠그고 진행 화면의 나머지는 그대로 둔다. 실패해도 후보를 임의로 취소하지 않고 원인과 다시 시도를 보인다. 남은 시간은 서버가 준 `autoFinalizeAt`·`undoDeadline`과 기기 시각으로 1초 단위 표시만 하고 만료 판정은 하지 않는다. `autoFinalizeAt`과 `undoDeadline`에 맞춰 로컬 알람을 걸어 그 시각에 진행을 다시 조회한다.

**Accessibility & Adaptive Layout**: 터치 48×48dp·간격 8dp, 아이콘 전용 버튼 `contentDescription`, 자동 처리 여부는 색+문구 병기. 360dp·최대 글자 배율에서 시트 질문과 근거 문구가 줄바꿈으로 들어가도록 하고 두 행동 버튼을 세로로 쌓는다. 토스트는 문구가 길어지면 두 줄까지 허용하고 `되돌리기`를 잘라내지 않는다. 시트는 `navigationBarsPadding`을 지킨다.

**Visual Validation**: `Pixel_9_Pro`(API 36)에서 도착 확인·출발 확인·자동 확정 후 되돌리기·되돌리기 만료·자동 감지 꺼짐 안내를 360dp와 최대 글자 배율로 screenshot 한다. 시트는 F004·F006 방식대로 내용 composable을 inline 렌더해 캡처한다. 지오펜스 실제 발화는 `adb emu geo fix`로 확인한다.

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가 완료.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 위치 이벤트만으로 상태를 즉시 바꾸지 않고 후보 → 확인 → 확정 단계를 거친다. 무응답 확정에는 되돌리기가 붙는다. 권한·정확도 부족은 자동 감지만 끄고 F006 수동 진행을 그대로 둔다(FR-024). | PASS |
| II. 계약 우선 SDD와 문서 동기화 | PROG-003·004·005를 feature OpenAPI로 확정하고 `api-spec.md`의 해당 절과 F006 PROG-001 응답 확장을 같은 PR에서 동기화한다. ERD 7.1·7.2와 enum 10절을 그대로 구현한다. | PASS |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | 이벤트는 `client_event_id` UNIQUE로, 확인·되돌리기는 `Idempotency-Key`로 멱등 처리한다. 복합 전환·확정·되돌리기는 한 transaction이다. 후보 생성·취소·확정·되돌리기를 모두 `progress_transitions`에 기록하고 `source`로 자동·수동을 구분한다. 시각 판정은 서버 시각이다. | PASS |
| IV. 외부 의존성 실패 격리 | 외부 provider를 호출하지 않는다. 확정 시 필요한 ETA 재계산은 F005 저장 경로를 재사용하고, 계산 불가 구간은 F006 규칙대로 `정보 없음`으로 둔다. 지오펜스 등록 실패는 자동 감지만 꺼지게 하고 진행을 막지 않는다. | PASS |
| V. 보안·소유권·최소 데이터 | 세 endpoint 모두 F006 소유권 검증을 재사용한다. 좌표는 판정 근거로만 저장하고 이동 경로를 축적하지 않으며(FR-026), `progress_events`는 `trip_days` cascade로 삭제된다. log에는 이벤트 ID와 판정 결과만 남기고 좌표를 남기지 않는다. | PASS |
| 교차 계약 review | PROG-003·004·005 DTO, `detectionTargets`·`pendingCandidate`·`undoable` 응답 확장, 파생 판정 규칙을 BE(ts)·FE(hs)가 구현 전에 함께 확인한다. F006 파일 수정은 원 담당자(jy) review를 받는다. | PASS WITH REVIEW CONDITION |

### Post-Design Re-check

- [data-model.md](data-model.md)는 신규 테이블 하나(`progress_events`)만 만들고 `progress_transitions`에 컬럼을 추가하지 않는다. ERD 7.1·7.2 범위 안이다.
- [contracts/detection.openapi.yaml](contracts/detection.openapi.yaml)은 F006 오류 code를 재사용하고 신설 code는 `TRANSITION_NOT_PENDING`·`INVALID_DECISION`·`UNDO_WINDOW_EXPIRED`·`TRANSITION_NOT_UNDOABLE` 넷이다.
- [quickstart.md](quickstart.md)가 후보 생성·거절·상한·자동 확정·되돌리기·재개·재진입·오판·복합 전환·멱등·권한 축소 시나리오를 모두 포함한다.
- Phase 1 이후 gate는 모두 PASS이며 새 예외는 없다. 지연 확정의 trade-off는 아래 Complexity Tracking이 아니라 `research.md` 2절에 기록으로 남겼다. 원칙 위반이 아니라 MVP 제외 항목을 지키기 위한 선택이기 때문이다.

## Project Structure

### Documentation (this feature)

```text
specs/007-location-detection/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── detection.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md              # $speckit-tasks에서 생성
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── api/v1/progress.py         # (수정) PROG-003·004·005 endpoint 추가
│   ├── models/progress.py         # (수정) ProgressEvent 추가, transition F007 필드 사용
│   ├── schemas/progress.py        # (수정) 이벤트·후보·결정·되돌리기 DTO, detectionTargets
│   ├── services/detection.py      # 이벤트 검증, 후보 생성·취소, 파생 판정, 감지 대상 산출
│   ├── services/progress.py       # (수정) 지연 확정 진입점, 확정·되돌리기에서 F006 전환 재사용
│   └── main.py                    # 변경 없음(progress router 재사용)
├── migrations/versions/006_create_progress_events.py
└── tests/
    ├── contract/test_detection_contract.py
    ├── integration/test_detection_flow.py
    └── unit/test_detection_service.py

android/app/src/main/java/com/gilpick/
├── progress/
│   ├── DetectionApi.kt            # 이벤트·결정·되돌리기 DTO와 Retrofit 계약
│   ├── DetectionRepository.kt     # AuthRepository.withAuthorizedCall, 오류 분류
│   ├── GeofenceManager.kt         # detectionTargets ↔ 등록 상태 차이 반영
│   ├── GeofenceReceiver.kt        # broadcast 수신 → 이벤트 전송
│   ├── ProgressViewModel.kt       # (수정) pendingCandidate·undoable 상태와 행동
│   ├── ActiveTravelScreen.kt      # (수정) 확인 시트·되돌리기 토스트 배치
│   ├── ConfirmSheet.kt            # 도착·출발 공용 확인 시트
│   └── UndoToast.kt
└── AndroidManifest.xml            # ACCESS_BACKGROUND_LOCATION, receiver 등록

android/app/src/test/java/com/gilpick/progress/       # ViewModel·Repository·GeofenceManager unit test
android/app/src/androidTest/java/com/gilpick/progress/ # 시트·토스트 UI test, screenshot
```

**Structure Decision**: F006이 만든 `progress` package와 `api/app/services/progress.py`를 그대로 확장한다. 감지 판정만 `services/detection.py`로 분리해 "언제 전환할지"(F007)와 "전환하면 무엇이 바뀌는지"(F006)의 경계를 파일로도 드러낸다. Android도 별도 package를 만들지 않고 `progress`에 둔다. 진행 화면 하나에서 쓰이고 F006 상태와 같은 ViewModel을 공유하기 때문이다.

## 담당 영역 (plan 완료 보고용)

- **Backend (`ts`)**: migration 006, `ProgressEvent` 모델과 스키마, `services/detection.py`(이벤트 검증·후보 생성·취소·파생 판정·감지 대상 산출), 지연 확정 진입점, PROG-003·004·005 endpoint, F006 PROG-001 응답 확장, contract·integration·unit test, `api-spec.md` 동기화.
- **Frontend Android (`hs`)**: 백그라운드 위치 권한 2단계 요청과 안내, `GeofenceManager`·`GeofenceReceiver`, 이벤트 전송 repository, 확인 시트·되돌리기 토스트와 `ProgressViewModel` 확장, 자동 처리 표시, Figma 출발 확인 시트 추가와 사본 갱신, unit·UI·screenshot test, AVD 검증.
- **통합**: 구현 전 계약 교차 review(PROG-003·004·005 DTO와 PROG-001 응답 확장, 파생 판정 규칙), 실서버 종단간 검증, F006 파일 수정에 대한 원 담당자(jy) review.

## Complexity Tracking

Constitution 위반은 없다. 신규 테이블은 `progress_events` 하나뿐이고, 다섯 가지 파생 판정에 컬럼을 추가하지 않아 상태 이중화를 피했다. 지오펜스는 F006이 이미 추가한 의존성 안에서 처리하며 새 라이브러리를 넣지 않는다. 후보 만료 처리에 작업 큐를 도입하지 않고 지연 확정으로 해결한 이유와 그 trade-off는 `research.md` 2절에 기록했다.
