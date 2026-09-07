# Specification Quality Checklist: 위치 기반 감지

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-08
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

- 2026-09-08 미해결 항목 2건을 팀 결정으로 닫았다.
  1. **FR-015a** — 무응답 대기는 후보 생성 시각부터 센다. 사용자가 질문을 봤는지와 무관하게 자동 확정하며, F011 이전에 못 본 채 바뀐 상태의 회복 수단은 F006 상태 수정이다.
  2. **FR-017a** — 자동 확정을 되돌린 장소는 재질문 간격과 같은 시간 동안 자동 감지를 쉬었다가 재개한다. 도착은 기존 질문 횟수 상한이, 출발은 "두 번째 되돌리기에서 그 날짜 중단"이 반복을 막는다.
- **review에서 확인이 필요한 항목**: 위 2번의 출발 쪽 제한(두 번째 되돌리기에서 중단)은 `requirements.md`에 대응 규칙이 없어 이 명세에서 새로 둔 것이다. 도착과 달리 출발에는 질문 횟수 상한이 없어 무한 반복을 막을 근거가 필요했다. 다른 규칙이 더 낫다면 `/speckit-clarify`에서 바꾼다.
- 감지 반경·대기 시간·재질문 간격·정확도 기준은 `docs/planning/requirements.md`와 `docs/planning/mvp.md`가 이미 확정한 값이라 이 명세에서 다시 정하지 않았다. 값이 바뀌면 두 문서를 먼저 고친다.
- UI 정본은 Figma Make `ActiveTravelScreen`(도착 확인 시트·되돌리기 토스트)과 `LocationPermissionScreen`이다. **출발 확인 시트는 Figma에 없어 추가가 필요**하며 UI-002에 기록했다.
