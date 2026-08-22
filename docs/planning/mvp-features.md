# MVP Feature 목록

길픽 MVP 개발을 위한 Feature 단위 목록이다.

- 각 Feature의 상세 요구사항은 `specs/###-feature-name/spec.md` 형식의 경로(예: `specs/001-kakao-login/spec.md`)에서 관리한다.
- 이 문서는 전체 기능 범위, 우선순위, 의존성, 진행 상태를 확인하기 위한 용도로만 사용한다.
- 이미 확정된 MVP 범위를 벗어나는 기능은 임의로 추가하지 않는다.
- API 필드, 상세 예외 처리, acceptance criteria 등은 이 문서가 아닌 해당 Feature의 명세에서 관리한다.

## Feature 목록

| ID | Feature | 설명 | 선행 Feature | 상태 | Owner |
|---|---|---|---|---|---|
| F001 | 인증 | 카카오 로그인, 토큰 재발급, 현재 기기 로그아웃 | - | TODO | - |
| F002 | 여행 관리 | 여행 생성·조회·수정·삭제와 목록 검색·필터 | F001 | TODO | - |
| F003 | 장소 검색 | TourAPI 기반 관광지 검색 및 상세 조회 | F001 | TODO | - |
| F004 | 일정 구성 | 날짜별 장소 추가와 순서·체류시간·이동수단 편집 | F002, F003 | TODO | - |
| F005 | 경로 계산 | 일정 기반 이동 경로 계산·재계산 및 지도 표시 | F004 | TODO | - |
| F006 | 여행 진행 | 여행 시작, ETA 관리, 수동 상태 전환과 당일 완료 | F004, F005 | TODO | - |
| F007 | 위치 기반 감지 | 위치 이벤트 기반 도착·출발 감지와 확인·되돌리기 | F006 | TODO | - |
| F008 | 여행 변수 감지 | 남은 일정의 혼잡도·날씨·운영시간 평가 | F006 | TODO | - |
| F009 | 대체 장소 추천 | 변수 발생 시 조건에 맞는 대체 장소 후보 추천 | F003, F008 | TODO | - |
| F010 | 일정 변경 | 대체 장소 미리보기·승인 후 일정과 경로 변경·되돌리기 | F004, F005, F009 | TODO | - |
| F011 | 알림 | 도착·출발 확인과 장소 변경 제안 알림 | F006, F007, F008 | TODO | - |
| F012 | 사용자 설정 | 장소 변경 제안 알림 설정과 정책 문서·로그아웃 진입 | F001 | TODO | - |

## 상태 정의

| 상태 | 의미 |
|---|---|
| `TODO` | 상세 설계 전 |
| `SPEC` | `spec`·`clarify`·`plan`·`tasks` 작성 중 |
| `READY` | `tasks` 작성까지 완료되어 구현 대기 중 |
| `IN_PROGRESS` | GitHub Issue 단위 구현 중 |
| `VERIFY` | 구현 완료 후 명세·테스트 결과 검증 중 |
| `DONE` | 관련 작업이 `main`에 merge 완료됨 |

## Feature 진행 규칙

- 현재 구현 중인 Feature보다 앞서 상세 설계할 수 있는 후속 Feature는 최대 1개이다.
- 다음 Feature는 선행 Feature, 현재 구현 상태, GitHub Issue 상황을 확인한 뒤 선택한다.
- 뒤쪽 Feature를 여러 개 미리 `spec`·`plan`·`tasks`까지 상세화하지 않는다.
- Feature Owner는 해당 Feature의 `spec → clarify → plan → tasks` 준비를 책임진다.
- 실제 구현은 `tasks.md`의 task를 GitHub Issue로 나눈 뒤 팀원들이 분담한다.
- Feature 상태는 관련 산출물과 GitHub Issue·PR 상태가 바뀔 때 함께 갱신한다.

## 관련 문서

- MVP 범위: `docs/planning/mvp.md`
- 요구사항: `docs/planning/requirements.md`
- 기능 명세: `docs/planning/functional-spec.md`
- 사용자 흐름: `docs/planning/user-flow.md`
