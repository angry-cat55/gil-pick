@AGENTS.md

# Claude Code 안내

- 이 프로젝트의 공통 AI 개발 규칙은 `AGENTS.md`를 단일 기준(source of truth)으로 사용한다.
- 작업을 시작하기 전에 반드시 `AGENTS.md`를 읽는다.
- feature 작업이라면 구현·수정 전에 해당 feature의 `spec.md`, `plan.md`, `tasks.md`를 모두 읽고 범위, 계약, 선행 관계를 확인한다.
- 필요한 feature 산출물이 없거나 서로 충돌하면 내용을 임의로 만들거나 해석하지 말고 누락·충돌을 먼저 알린다.
- 저장소 공용 skill의 사용 기준은 `AGENTS.md` 9절 "저장소 공용 skill 사용"을 따른다.
- 공통 규칙을 이 파일에 중복해서 복사하지 않는다. 프로젝트 결정은 해당 feature 산출물 또는 팀이 확정한 constitution에 기록한다.
- 개인 설정은 `.claude/settings.local.json`에 두고 commit하지 않는다.
