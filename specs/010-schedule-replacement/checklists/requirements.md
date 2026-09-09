# Specification Quality Checklist: 일정 변경

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-09
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 초안 1회차에서 `[NEEDS CLARIFICATION]` 3건이 남았고, 사용자 결정으로 모두 해소해 2회차에서 전 항목 통과했다.
  - Q1 (FR-014, US2 시나리오 7) → **승인 전체 취소**. 경로를 얻지 못하면 장소도 바꾸지 않는다. Figma Make `RouteRecalculatingScreen` 문구와 `functional-spec.md` 5.3 원자성 요구를 함께 만족한다. plan 단계에서 계산 시점을 미리보기로 옮겨(`research.md` 2절) 승인에 외부 호출이 없게 했고, 그에 맞춰 FR-014·SC-007·US2 시나리오 2·7을 2026-09-09 갱신했다.
  - Q2 (FR-018, US3 시나리오 5) → **감지 결과를 사용자 결정 전 상태로 복귀**. 되돌린 뒤 배너와 후보 조회 대상에 다시 포함된다.
  - Q3 (UI-006) → **진행 화면에서 F007 되돌리기와 같은 자리·같은 형식**. 둘이 동시에 가능하면 남은 시간이 짧은 장소 변경 되돌리기를 먼저 보인다(UI-006a).

## 후속 문서 동기화 (plan 단계에서 확정)

- **F008·F009 감지 결과 상태 전이**: Q2 결정에 따라 `RESOLVED` → 사용자 결정 전 상태로 되돌아가는 전이가 필요하다. F009 `data-model.md`는 `RESOLVED`(F010 승인)만 정의하고 역방향이 없으며, F008 재감지 규칙(`fingerprint` ACTIVE partial unique)과 충돌하지 않는지 확인해야 한다.
- **`docs/design/api-spec.md` REPL-001~004**: 미리보기 비교 항목, 감지 결과 상태 연동, 되돌리기 거절 사유가 현재 예시에 없다. 또한 REPL-001 요청의 일정 버전 필드가 `itineraryVersion`으로 적혀 있어 F004·F006의 `scheduleVersion`과 이름이 어긋난다.
- **F007 되돌리기 토스트**: UI-006a의 우선순위 규칙을 반영하려면 진행 화면의 되돌리기 표시 지점을 F010과 공유하도록 조정해야 한다. F006 소유 파일이므로 `jy` review 대상이다.
