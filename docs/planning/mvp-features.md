# MVP Feature 목록

길픽 MVP 개발을 위한 Feature 단위 목록이다.

- 각 Feature의 상세 요구사항은 `specs/###-feature-name/spec.md` 형식의 경로(예: `specs/001-kakao-login/spec.md`)에서 관리한다.
- 이 문서는 전체 기능 범위, 우선순위, 의존성, 진행 상태를 확인하기 위한 용도로만 사용한다.
- 이미 확정된 MVP 범위를 벗어나는 기능은 임의로 추가하지 않는다.
- API 필드, 상세 예외 처리, acceptance criteria 등은 이 문서가 아닌 해당 Feature의 명세에서 관리한다.

## Feature 목록

| ID | Feature | 설명 | 선행 Feature | 상태 | Owner |
|---|---|---|---|---|---|
| F001 | 인증 | 카카오 로그인, 토큰 재발급, 현재 기기 로그아웃 | - | DONE | jh |
| F002 | 여행 관리 | 여행 생성·조회·수정·삭제와 목록 검색·필터 | F001 | DONE | hs |
| F003 | 장소 검색 | TourAPI 중심 장소 검색·상세와 음식·카페·쇼핑의 제한적 Google Places 보완 | F001 | DONE | ts |
| F004 | 일정 구성 | 날짜별 장소 추가와 순서·체류시간·이동수단 편집 | F002, F003 | IN_PROGRESS | jy |
| F005 | 경로 계산 | 일정 저장 시 이동 경로 자동 계산 및 지도 표시 | F004 | SPEC | jh |
| F006 | 여행 진행 | 여행 시작, ETA 관리, 수동 상태 전환과 당일 완료 | F004, F005 | TODO | - |
| F007 | 위치 기반 감지 | 위치 이벤트 기반 도착·출발 감지와 확인·되돌리기 | F006 | TODO | - |
| F008 | 여행 변수 감지 | 남은 일정의 혼잡도·날씨·운영시간 평가 | F006 | TODO | - |
| F009 | 대체 장소 추천 | F003의 장소·Google 보강 계약을 재사용해 변동 조건에 맞는 대체 후보 추천 | F003, F008 | TODO | - |
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
- Feature Owner는 해당 Feature의 `spec → clarify → plan → tasks` 준비를 책임진다. 사용자 화면 또는 UI 요소가 포함되면 `AGENTS.md`의 "UI가 포함된 Feature 산출물" 규칙에 따라 UI 가이드·시각 레퍼런스와 관련 skill을 확인하고 검증 가능한 UI 기준을 산출물에 반영한다.
- 실제 구현은 `tasks.md`의 task를 GitHub Issue로 나눈 뒤 팀원들이 분담한다.
- Feature 상태는 관련 산출물과 GitHub Issue·PR 상태가 바뀔 때 함께 갱신한다.

## 공통 환경 준비 Gate

환경 준비는 사용자 기능이 아니므로 별도 Feature 번호를 부여하지 않고 `chore` 또는 `infra` Issue로 관리한다. 각 Issue에는 대상 환경, 담당자, 비용 영향, secret 소유자, 완료 조건과 이를 기다리는 Feature task를 명시한다.

### G001 외부 연동 검증 Gate

- 실제 callback, App Link 또는 외부 API 종단간 검증이 필요한 Feature는 해당 검증 task를 시작하기 전에 공유 dev/staging 환경을 준비한다.
- 공유 환경에는 외부에서 접근 가능한 HTTPS Backend, PostgreSQL·PostGIS 연결, 환경변수·secret 주입, 필요한 callback·domain 등록이 포함된다.
- 외부 연동 자격은 다음 시점까지 준비한다.
  - F001: Kakao test app, Backend HTTPS callback, Android App Link domain과 `assetlinks.json`
  - F003: TourAPI, Google Places
  - F005: TMAP·ODsay
  - F008: 기상청·서울시 데이터
  - F009: F003에서 확정한 Google Places 계약 재사용
  - F011: Firebase·FCM
- 실제 환경이 필요한 task는 관련 환경 Issue를 `blocked by #번호`로 연결한다. mock·local test만 필요한 선행 task까지 불필요하게 차단하지 않는다.

### G002 MVP 운영 준비 Gate

- MVP 전체 종단간 검증과 배포 후보 확정 전까지 Backend 실행 환경, 운영 PostgreSQL·PostGIS, HTTPS domain·TLS, secret 관리, 로그 보존·접근 제한, backup·restore, monitoring과 배포 절차를 준비한다.
- CI/CD를 사용한다면 build·test·migration·배포 단계와 실패 시 중단·rollback 절차를 운영 환경 Issue에 기록한다. 수동 배포를 선택한 경우에도 같은 검증과 복구 절차를 문서화한다.
- AWS의 구체 서비스와 환경 수, 비용 상한은 인프라 Issue의 기술 결정으로 합의하며 이 문서에서 임의로 고정하지 않는다.
- 운영 자격과 실제 endpoint는 저장소에 기록하지 않고 승인된 secret 저장소와 배포 환경에서 주입한다.

## 관련 문서

- MVP 범위: `docs/planning/mvp.md`
- 요구사항: `docs/planning/requirements.md`
- 기능 명세: `docs/planning/functional-spec.md`
- 사용자 흐름: `docs/planning/user-flow.md`
