# Implementation Plan: 일정 구성

**Branch**: `004-itinerary-editing` (Spec Kit 논리 식별자; 현재 Git branch `docs/jy-f004-itinerary-spec`) | **Date**: 2026-09-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-itinerary-editing/spec.md`

## Summary

인증된 사용자가 여행의 날짜별 일정에 F003 장소를 추가하고 순서·체류 시간·구간 이동 수단을 편집해 저장한다. Backend는 ERD 5.2~5.4의 `trip_days`·`places`·`itinerary_items`를 migration 하나로 만들고, 한 날짜의 항목 전체를 `schedule_version` 조건부 갱신으로 저장하는 ITIN-001·002와 여행 전체 개요 ITIN-003을 제공한다. 장소 참조는 클라이언트가 보내는 F003 스냅샷으로 upsert하고, 경로 상태는 F005 전까지 `NOT_CALCULATED`다. F002 기간 축소는 실제 삭제 대상 수를 계산하고 앱에 동의 대화상자를 붙인다. Android는 `itinerary` 패키지에 편집 화면·ViewModel·API를 추가하고 F002 여행 상세에 날짜별 목록과 진입점을, F003 `placeGraph`에 `일정에 추가` 결과 반환을 연결한다.

## Technical Context

**Language/Version**: Backend Python 3.13; Android Kotlin 2.4.10

**Primary Dependencies**: Backend는 FastAPI 0.141.1, SQLAlchemy 2.0.52 async, Alembic, asyncpg, GeoAlchemy2(PostGIS `geography` 컬럼, 신규), PyJWT 재사용. Android는 Compose BOM 2026.08.00, Navigation Compose 2.10.0, Lifecycle 2.11.0(`SavedStateHandle`), Retrofit 3.0.0, kotlinx.serialization 1.11.0, Coil 3.6.0 재사용. 새 Android 의존성 없음.

**Storage**: PostgreSQL 18 + PostGIS. migration `003_create_itinerary_tables`가 `trip_days`, `places`, `itinerary_items`를 ERD 5.2~5.4 전체 컬럼으로 생성. `routes`는 F005.

**Testing**: Backend pytest, pytest-asyncio, HTTPX ASGI client, PostgreSQL integration·contract test(F002 `test_trip_contract.py` 수정 포함); Android JUnit, kotlinx-coroutines-test, MockWebServer, Compose UI test, Navigation test, AVD 실서버 수동 검증([quickstart.md](quickstart.md))

**Target Platform**: Docker 기반 AWS Linux Backend; Android 8.0 이상(`minSdk 26`, `targetSdk 36`, `compileSdk 37`)

**Project Type**: Android mobile app + REST API web service

**Performance Goals**: 일정 조회·저장 3초 이내(SC-002), 상세 진입 → 저장 5분 이내(SC-001). 하루 10곳 × 7일이라 페이징·가상화 불필요

**Constraints**: 소유권 검증 필수; 통째 저장 + `schedule_version` 충돌 감지; `Idempotency-Key` 필수와 재전송 무해(FR-009); 체류 30~360분·30분 단위; 마지막 항목 이동 수단 null; 하루 최대 10곳; 처리된 항목 잠금; 경로 계산·ETA·지도(F005)·상태 전환(F006)·대체 장소(F009/F010)는 범위 밖; 스크린리더 대응 범위 밖(2026-09-05 팀 결정)

**Scale/Scope**: Backend endpoint 3개 신설 + F002 PATCH 1개 수정, migration 1개; Android 편집 화면 1개 신설, 여행 상세·여행 수정 화면 2개 수정, F003 navigation 연결

## UI Implementation & Validation

**Design Sources**: Figma Make `Design UI from Reference`의 `ScheduleEditScreen`, `TripDetailScreen` 일정 영역, `EditTripScreen` 확인 대화상자가 정본이다([spec.md](spec.md) UI-011). `docs/design/ui-guidelines.md` 5절 크기, 9절 화면 상태, 10절 접근성 최저선을 적용한다. Figma 수정 4건(이동 수단 시트의 체류 시간 제거, 순서 변경 손잡이·버튼, 날짜 헤더 `장소 추가`, 기간 축소 확인 대화상자)은 구현 전 반영을 확인한다.

**Tokens & Components**: `GilpickTheme`, `LocalGilpickColors`·`Spacing`·`Sizing`·`Radius`를 그대로 쓰고 새 토큰은 추가하지 않는다. 날짜 탭·순서 번호 원·점선 `장소 추가` 버튼은 편집 화면과 여행 상세에서 함께 쓰일 때만 `ui/component`로 추출한다. 체류 시간 `−`·`+`는 F003 `AddToScheduleSheet`의 stepper 규칙(40dp 원, dialog 44dp)을 재사용한다. 장소 썸네일은 `RemoteImage`.

**State & Interaction**: [data-model.md](data-model.md) 6절 `ItineraryEditUiState`. 초안(`draft`)과 저장본(`savedVersion`)을 분리하고 `dirty`로 닫기 확인을 결정한다. `Loading`은 1초 규칙, `Empty`는 `장소 추가` 안내, `Failed`는 원인+`다시 시도`. 저장 중 `저장` 비활성. 409는 사용자 안내 없이 최신 version으로 최대 2회 재저장([research.md](research.md) 4절). 순서 변경은 위·아래 버튼 + 손잡이 끌기(9절). type-safe `ItineraryEditRoute(tripId, date, openSearch)`; F003 결과는 `SavedStateHandle`로 돌아온다(10절).

**Accessibility & Adaptive Layout**: 닫기·삭제·`−`·`+`·이동 버튼·손잡이에 `contentDescription`, 모든 터치 영역 48dp·간격 8dp. 처리 상태는 색+아이콘+문구. 360dp와 font scale 2.0에서 장소명 줄바꿈, 하단 `저장`은 `navigationBarsPadding`. TalkBack 공지·포커스 조정은 범위 밖이되 기존 semantics는 유지한다.

**Visual Validation**: [quickstart.md](quickstart.md) Android 수동 검증 6항목. 편집 화면 4상태, 10곳·긴 장소명, 처리된 항목 fixture, 취소 확인, 기간 축소 대화상자, 360dp·font scale 2.0 스크린샷을 PR에 기록한다.

## Constitution Check

*GATE: Phase 0 전 평가 및 Phase 1 후 재평가 완료.*

| 원칙 | 설계 대응 | Gate |
|---|---|---|
| I. 사용자 통제와 안전한 fallback | 저장 실패 시 초안 보존과 재시도, 닫기 확인, 기간 축소 동의 대화상자. 자동 재저장(Q4)은 사용자 결정으로 다른 기기 변경을 덮어쓰며 spec Assumptions에 근거를 기록했다. | PASS WITH RECORDED TRADEOFF |
| II. 계약 우선 SDD와 문서 동기화 | ITIN 계약을 feature OpenAPI로 확정하고 `api-spec.md` 5절·TRIP-004를 같은 PR에서 동기화. ERD는 그대로 구현. | PASS |
| III. 상태 변경의 일관성·멱등성·추적 가능성 | 통째 저장을 한 transaction, `schedule_version` 조건부 갱신, uuid5 항목 ID + no-op 판정으로 재전송 무해, 저장 시각·version 전후 log. | PASS |
| IV. 외부 의존성 실패 격리 | 저장은 외부 provider를 호출하지 않는다(클라이언트 스냅샷). 경로 계산은 F005로 분리하고 `NOT_CALCULATED`로 명시. | PASS |
| V. 보안·소유권·최소 데이터 | 모든 endpoint가 F002 소유권 검증 재사용. `places`에 평점·리뷰·원문 저장 금지. 위치는 장소 좌표만. | PASS |
| 교차 계약 review | ITIN DTO·오류 code·`place` 스냅샷·F002 PATCH 변경을 BE(ts/jh)·FE(jy/hs)가 구현 전에 확인. F002·F003 파일 수정은 원 담당자 review. | PASS WITH REVIEW CONDITION |

### Post-Design Re-check

- [data-model.md](data-model.md)는 ERD 5.2~5.4를 그대로 쓰고 새 테이블·컬럼을 추가하지 않는다.
- [contracts/itinerary.openapi.yaml](contracts/itinerary.openapi.yaml)은 F002 오류 code를 재사용하고 신설 code는 `INVALID_ITINERARY`·`ITINERARY_ITEM_LOCKED` 둘뿐이다.
- [quickstart.md](quickstart.md)가 멱등·충돌·잠금·기간 축소·소유권 시나리오를 모두 포함한다.
- Phase 1 이후 gate는 모두 PASS이며 새 예외는 없다.

## Project Structure

### Documentation (this feature)

```text
specs/004-itinerary-editing/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── itinerary.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md              # $speckit-tasks에서 생성
```

### Source Code (repository root)

```text
api/
├── app/
│   ├── api/v1/itinerary.py        # ITIN-001~003 endpoint, Idempotency-Key, 오류 매핑
│   ├── api/v1/trips.py            # (수정) PATCH 409 deletedItemCount 전달
│   ├── models/itinerary.py        # TripDay, Place, ItineraryItem (GeoAlchemy2 geography)
│   ├── schemas/itinerary.py       # SaveItem·PlaceSnapshot·DayItinerary·overview envelope
│   ├── services/itinerary.py      # 검증, places upsert, diff 저장, no-op·version, 잠금
│   ├── services/trip.py           # (수정) 기간 축소 삭제 대상 계산·삭제
│   └── main.py                    # itinerary router 등록
├── migrations/versions/003_create_itinerary_tables.py
└── tests/
    ├── contract/test_itinerary_contract.py
    ├── contract/test_trip_contract.py        # (수정) deletedItemCount·확인 생략
    ├── integration/test_itinerary_flow.py
    └── unit/test_itinerary_service.py

android/app/src/main/java/com/gilpick/
├── MainActivity.kt                # ItineraryEditRoute 등록, 상세→편집·검색 진입, placeGraph 결과 연결
├── itinerary/
│   ├── ItineraryApi.kt            # DTO·TransportMode·ItineraryService(Retrofit)
│   ├── ItineraryRepository.kt     # AuthRepository.withAuthorizedCall, 오류 분류
│   ├── ItineraryEditViewModel.kt  # draft/saved 분리, SavedStateHandle, 자동 재저장
│   ├── ItineraryEditScreen.kt     # Figma ScheduleEditScreen, 대화상자·시트, 순서 변경
│   └── ItineraryLabels.kt         # 체류 시간·이동 수단·날짜 표시 문자열
├── trip/
│   ├── TripDetailScreen.kt        # (수정) 날짜별 일정 목록, 일정 편집·날짜별 장소 추가
│   ├── TripDetailViewModel.kt     # (수정) ITIN-003 개요 상태
│   ├── TripFormViewModel.kt       # (수정) CONFIRMATION_REQUIRED → 대화상자, confirm 재요청
│   └── TripFormScreen.kt          # (수정) 삭제될 장소 N곳 대화상자
└── place/PlaceNavigation.kt       # (수정) onAddToSchedule 결과 반환

android/app/src/test/java/com/gilpick/itinerary/     # ViewModel·Repository unit test
android/app/src/androidTest/java/com/gilpick/itinerary/  # 편집 화면·navigation UI test
```

**Structure Decision**: 기존 router → service → model, Retrofit → repository → ViewModel → stateless screen 흐름을 유지한다. 일정 저장은 `itinerary.py` service가 검증·upsert·diff·version을 한 transaction에서 처리하며 항목 단위 service를 따로 두지 않는다. F002·F003 파일 수정은 최소 범위로 두고 각 Issue에서 소유권을 명시한다.

## Phase 0 Research Decisions

[research.md](research.md)에 12개 결정을 기록했다. 미해결 기술 항목은 없다. Backend 담당자 확인 항목(migration 범위, F002 PATCH 동작 변경, uuid5·no-op 멱등 규칙)은 12절에 정리했고 tasks의 교차 review task에서 닫는다.

## Phase 1 Design Outputs

- 저장 구조·검증·UI state: [data-model.md](data-model.md)
- REST 계약: [contracts/itinerary.openapi.yaml](contracts/itinerary.openapi.yaml)
- 종단간 검증 절차: [quickstart.md](quickstart.md)

## 담당 영역 (plan 완료 보고용, 배정 미정)

- **Backend**: migration 003, itinerary 모델·스키마·service·endpoint 3개, F002 `update_trip` 기간 축소 수정, contract·integration·unit test, `api-spec.md` 동기화.
- **Frontend Android**: itinerary 패키지(API·Repository·ViewModel·편집 화면), 여행 상세 일정 목록·진입점, 여행 수정 삭제 확인 대화상자, F003 결과 반환 연결, unit·UI test, AVD 검증.
- **통합**: 구현 전 계약 교차 review(ITIN DTO·오류 code·`place` 스냅샷·PATCH 변경), 실서버 종단간 검증, Figma 4건 반영 확인.

## Complexity Tracking

**Constitution I 예외 기록**: FR-008의 안내 없는 자동 재저장은 사용자가 누른 `저장`을 완료하는 동작이지만, 다른 기기가 먼저 저장한 변경을 확인 없이 덮어쓴다. 팀 결정(spec Clarifications 2026-09-05)으로 채택했고 근거는 spec Assumptions에 있다. 종료 조건: 실제 사용에서 덮어쓰기로 인한 문제가 확인되면 "다른 곳에서 바뀌었습니다" 안내 후 사용자가 `저장`을 다시 누르는 방식으로 바꾼다. 이 예외는 문서 PR 본문에 함께 기록한다.

Constitution 위반은 없다. GeoAlchemy2는 ERD의 PostGIS `geography` 컬럼을 ORM으로 다루기 위한 최소 추가이며, 순서 변경 끌기는 라이브러리 없이 foundation gesture로 구현하고 버튼이 기능을 보장한다. 항목 단위 API, idempotency 저장 테이블, 경로 계산 선반영은 도입하지 않는다.
