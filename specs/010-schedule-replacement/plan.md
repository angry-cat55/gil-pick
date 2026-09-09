# Implementation Plan: 일정 변경

**Branch**: `docs/hs-f010-schedule-replacement-spec` | **Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-schedule-replacement/spec.md`

## Summary

F009가 넘긴 대체 장소 선택을 받아 **변경 경로 미리보기**를 만들고, 승인하면 일정 장소·경로·이력을 하나의 transaction으로 바꾸고, 30초 안에 되돌릴 수 있게 한다.

설계의 중심은 **경로 계산을 미리보기 시점에 끝내는 것**이다. 기존 `RouteService`는 provider 호출을 transaction 밖에 두도록 설계돼 있어 승인 중에 경로를 계산하면 constitution III의 원자성 요구와 충돌한다. 계산을 앞으로 당기면 승인 transaction은 DB 작업만 남아 전부 성공하거나 전부 실패하고, `spec.md` Q1 결정(경로 재계산 실패 시 승인 전체 취소)이 보상 로직 없이 성립한다.

장소 교체는 `itinerary_items` 행을 유지하고 `place_id`만 바꾼다. `item_id`가 유지되므로 F006 진행 상태, F007 감지 대상, F008 `fingerprint`가 모두 그대로 유효하고 순서·체류시간·이동수단이 자동으로 보존된다.

Backend는 신규 테이블 2개(`route_previews`·`place_replacements`)와 `services/replacement.py`, endpoint 4개(REPL-001~004)를 만들고 F006 PROG-001 응답에 되돌릴 수 있는 장소 변경을 더한다. Android는 새 패키지 `com.gilpick.replacement`에 미리보기 화면을 만들고, 되돌리기는 진행 화면의 기존 `UndoToast` 자리를 공유한다.

결정 근거는 [research.md](research.md), 계약은 [contracts/replacements.openapi.yaml](contracts/replacements.openapi.yaml), 상태 모델은 [data-model.md](data-model.md), 검증 절차는 [quickstart.md](quickstart.md)다.

## Technical Context

**Language/Version**: Backend Python 3.12 / Android Kotlin 2.x

**Primary Dependencies**: FastAPI, SQLAlchemy 2 async, Alembic, PostgreSQL/PostGIS / Jetpack Compose, Navigation Compose, Retrofit·OkHttp, kotlinx.serialization

**Storage**: PostgreSQL. 신규 테이블 `route_previews`·`place_replacements`. 기존 테이블 컬럼 추가 없음

**Testing**: pytest(contract·integration·unit) / JUnit4, kotlinx-coroutines-test, MockWebServer, Compose UI test

**Target Platform**: Linux 서버 / Android minSdk 26, targetSdk 36

**Project Type**: Mobile + API

**Performance Goals**: 미리보기 생성은 경로 provider 호출을 포함해 3초 안에 응답(SC-001). 승인·되돌리기는 외부 호출이 없어 DB transaction 시간만 든다

**Constraints**: 승인·되돌리기는 부분 성공 금지(constitution III). 만료 판정은 서버 시각. 되돌리기 30초는 `docs/planning/requirements.md` REPL-02 확정값

**Scale/Scope**: 신규 endpoint 4개 + PROG-001 응답 확장 1건, 신규 테이블 2개, migration 1개, 신규 Backend 서비스 1개(`services/replacement.py`). Android 신규 패키지 1개(파일 6개) + `progress` 4파일 수정. F009 병합 선행. F011 제외

## UI Implementation & Validation

**Design Sources**: `docs/design/ui-guidelines.md`(3·5·9·10·11절), Figma Make 저장소 사본 `docs/design/figma-make/src/screens/RoutePreviewScreen.tsx`(비교 표·기존/변경 범례·`변경 승인`·`다른 후보 보기`), `RouteRecalculatingScreen.tsx`(계산 진행), `ActiveTravelScreen.tsx`(되돌리기 토스트 자리).

**Figma에 추가가 필요한 3건**(`research.md` 10절): `RoutePreviewScreen`의 승인 진행 중·승인 실패 상태, `ActiveTravelScreen`의 장소 변경 되돌리기 토스트, 비교 항목 `정보 없음` 표시. F007 T003과 같은 방식으로 사람이 Figma에 추가한 뒤 MCP로 원본을 받아 사본을 갱신한다. **없는 화면을 코드에서 임의로 만들지 않는다.**

**Tokens & Components**: `com.gilpick.ui.theme`의 기존 token만 쓴다. 비교 항목의 나아짐/나빠짐은 `colors.success`·`colors.warning`을 쓰되 색 단독으로 알리지 않고 문구·기호를 함께 둔다(UI-002, 가이드라인 10절). 되돌리기는 F007 `UndoToast`를 재사용하고 문구만 다르게 한다. 새 token은 추가하지 않는다.

**State & Interaction**: `PreviewUiState`는 `Loading`(1초 지연)·`Error`·`Content`. `empty`는 후보 없음을 F009가 처리하므로 두지 않는다(UI-004). 승인 실패는 `approveFailure`로 원인별 다음 행동을 나눈다(UI-005). 진행 화면 되돌리기는 표시 지점 하나에서 남은 시간이 짧은 장소 변경(30초)을 자동 확정(5분)보다 먼저 보인다(UI-006a).

**Accessibility & Adaptive Layout**: 모든 행동 48dp 이상·8dp 이상 간격(UI-008). 비교 표는 360dp 폭과 글자 최대 배율에서 항목명·이전 값·이후 값이 잘리지 않아야 한다(UI-009). 지도의 기존/변경 경로는 문구 범례로도 구분한다(UI-001).

**Visual Validation**: AVD `Pixel_9_Pro`(API 36)에서 미리보기 `content`·`error`, 승인 실패, 승인 직후 되돌리기 가능, 되돌리기 만료 다섯 상태를 screenshot으로 남긴다(UI-010). 360dp + `font_scale 2.0` 재실행을 포함한다.

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 설계 후 재확인.*

| 원칙 | 이 Feature의 준수 방식 | 판정 |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 후보 선택만으로 일정을 바꾸지 않고 미리보기·승인을 거친다(FR-001). 승인에는 30초 되돌리기가 있다(FR-015). 되돌릴 수 없게 된 뒤에도 F004 일정 편집으로 바꿀 수 있음을 안내한다(FR-019). 경로 계산 실패 시 기존 일정을 보존하고 `다시 시도`·`다른 후보 보기`를 제공한다(FR-007) | PASS |
| II. 계약 우선 SDD와 문서 동기화 | 계약을 `contracts/replacements.openapi.yaml`로 먼저 확정했다. api-spec REPL 절의 `itineraryVersion`을 `scheduleVersion`으로 고치고, F008·F009의 감지 결과 상태 전이에 `RESOLVED → ACTIVE`·`RESOLVED → INVALIDATED`를 더하는 동기화를 같은 변경 범위에 넣는다(`data-model.md` 5절) | PASS |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | 승인·되돌리기는 외부 호출 없는 단일 transaction이다(`research.md` 2절). 미리보기 생성·승인은 `Idempotency-Key`를 받고 F006 저장소를 재사용한다. `schedule_version`으로 충돌을 감지하고 후속 변경도 같은 값으로 판정한다. `place_replacements`가 변경 전후 장소·승인 시각·되돌린 시각을 남긴다. 만료 판정은 서버 시각 | PASS |
| IV. 외부 의존성 실패 격리 | 경로 계산 실패는 미리보기 단계에서만 일어나고 일정을 건드리지 않는다. 운영 마감 시각을 확보하지 못하면 그 비교 항목만 `null`로 두고 나머지를 제공하며 값을 지어내지 않는다(FR-002) | PASS |
| V. 보안·소유권·최소 데이터 | 네 endpoint 모두 인증과 여행 소유권을 검증한다(FR-021). `route_previews`는 계산 결과와 비교 값만 담고 위치 이력을 쌓지 않는다. 만료된 미리보기는 승인에 쓰이지 않는다 | PASS |

**Phase 1 재확인**: 설계 후에도 위반 없음. `Complexity Tracking`은 비워 둔다.

- 신규 테이블 2개는 각각 다른 수명을 가진다. `route_previews`는 승인 전 임시 계산 결과이고 `place_replacements`는 영구 이력이다. 하나로 합치면 승인되지 않은 미리보기가 이력에 섞인다.
- 기존 테이블에 컬럼을 추가하지 않았다. `itinerary_items.place_id` 교체만으로 F006·F007·F008과의 연결이 유지된다.

## Project Structure

### Documentation (this feature)

```text
specs/010-schedule-replacement/
├── plan.md              # 이 파일
├── spec.md
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── replacements.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md             # $speckit-tasks 산출물 (아직 없음)
```

### Source Code (repository root)

```text
api/
├── alembic/versions/
│   └── 00X_add_route_previews_and_place_replacements.py   # 신규 테이블 2개
├── app/
│   ├── models/
│   │   └── replacement.py          # RoutePreview, PlaceReplacement
│   ├── schemas/
│   │   └── replacement.py          # REPL-001~004 요청·응답, UndoableReplacement
│   ├── services/
│   │   └── replacement.py          # 미리보기 생성·승인·거절·되돌리기
│   └── api/v1/
│       └── replacements.py         # REPL-001~004 라우터
└── tests/
    ├── contract/test_replacement_contract.py
    ├── integration/test_replacement_flow.py
    └── unit/test_replacement_rules.py

api/app/services/progress.py        # PROG-001 응답에 undoableReplacement 추가
api/app/schemas/progress.py         # 같은 필드
api/app/services/detection/…        # 되돌리기의 감지 결과 복귀(fingerprint 충돌 처리)

android/app/src/main/java/com/gilpick/
├── replacement/                                  # 신규 패키지
│   ├── ReplacementApi.kt           # DTO·enum·Retrofit service
│   ├── ReplacementRepository.kt    # withAuthorizedCall, 오류 분류, Idempotency-Key
│   ├── PreviewUiState.kt
│   ├── PreviewViewModel.kt
│   ├── RoutePreviewScreen.kt       # Figma RoutePreviewScreen
│   └── ReplacementNavigation.kt    # RoutePreviewRoute, previewGraph
├── progress/
│   ├── ProgressApi.kt              # PROG-001에 undoableReplacement
│   ├── ProgressUiState.kt          # replacementUndo·우선순위 판단
│   ├── ProgressViewModel.kt        # undoReplacement()
│   └── UndoToast.kt                # 장소 변경 문구 추가
└── MainActivity.kt                 # alternativeGraph onSelectPlace → RoutePreviewRoute

android/app/src/test/java/com/gilpick/replacement/     # 단위 test
android/app/src/androidTest/java/com/gilpick/replacement/  # UI test
```

**Structure Decision**: 기존 `api/`(FastAPI 단일 레이아웃)와 `android/app` 구조를 그대로 쓴다.

Backend는 F007 `services/detection/`처럼 하위 패키지로 나눌 만큼 로직이 크지 않아 `services/replacement.py` 한 파일로 둔다. 미리보기 생성·승인·거절·되돌리기 네 동작이고 점수 계산이나 외부 provider 조합이 없다.

Android는 `progress` 패키지가 이미 F006·F007로 커서 미리보기 화면을 `replacement` 패키지로 분리하고, 되돌리기만 `progress`에 둔다. F009가 `alternative` 패키지를 같은 방식으로 나눈 것과 일관된다.

**F009 선행**: `com.gilpick.alternative`와 `alternativeGraph(onSelectPlace)` 콜백은 F009 구현(PR #333)에서 만들어진다. Android 작업은 그 병합 이후에 시작한다. Backend 작업은 F009 후보 토큰 검증 함수(`verify_candidate_token`)에만 의존하므로 그보다 먼저 시작할 수 있다.

## Complexity Tracking

> Constitution Check에 위반이 없어 비워 둔다.
