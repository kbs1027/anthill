---
name: work
description: 구현 작업의 전체 절차를 처음부터 끝까지 순서대로 실행한다. worktree 생성 → 계획서 → 사용자 확정 → 구현 → 테스트 → E2E → 보고서. 단계 하나만 하려면 /work-start, /work-plan, /work-implement, /work-test, /work-e2e, /work-report를 쓴다.
argument-hint: <작업 설명>
disable-model-invocation: true
---

# 작업 절차 (전체)

요청받은 작업: $ARGUMENTS

아래 단계를 위에서부터 순서대로 한다. 단계에 들어갈 때마다 그 단계의 파일을 읽고 그대로 따른다. 단계 파일의 "끝나면"에 적힌 다음 명령 안내는 사용자가 단계를 따로 부를 때를 위한 것이므로, 여기서는 안내만 하고 멈추지 말고 다음 단계로 이어서 간다.

| 순서 | 단계 | 명령 | 절차 파일 | 멈추는 곳 |
|---|---|---|---|---|
| 0 | 시작 | `/work-start` | `.claude/skills/work-start/SKILL.md` | |
| 1·2 | 계획과 확정 | `/work-plan` | `.claude/skills/work-plan/SKILL.md` | **사용자가 계획서를 확정할 때까지** |
| 3 | 구현 | `/work-implement` | `.claude/skills/work-implement/SKILL.md` | |
| 4 | 테스트 | `/work-test` | `.claude/skills/work-test/SKILL.md` | **사용자가 테스트 목록을 확인할 때까지** |
| 5 | E2E | `/work-e2e` | `.claude/skills/work-e2e/SKILL.md` | |
| 6 | 보고서 | `/work-report` | `.claude/skills/work-report/SKILL.md` | |

멈추는 곳 두 군데에서는 반드시 사용자의 답을 기다린다. 그 밖에는 묻지 않고 이어서 한다. 단계 파일이 멈추라고 한 다른 경우(계획과 크게 달라질 때, 사용자가 띄워 둔 것을 내려야 할 때)에도 멈춘다.

## 모든 단계가 함께 쓰는 약속

- **작업이름**: 영어 소문자와 하이픈으로 짧게 짓는다(예: `order-form`). worktree 브랜치는 `worktree-<작업이름>`이다.
- **작업폴더**: `docs/work/YYYY-MM-DD-<작업이름>/`. 날짜는 작업을 시작한 날이다. 산출물은 모두 여기에 두고 코드와 함께 커밋한다.

  ```text
  docs/work/2026-11-10-order-form/
  ├── plan.md          계획서 (/work-plan)
  ├── e2e.mjs          E2E 시나리오: 화면 조작과 캡처 (/work-implement, /work-e2e)
  ├── shots/before/    고치기 전 화면 (/work-implement)
  ├── shots/after/     고친 뒤 화면 (/work-e2e)
  └── report.md        보고서 (/work-report)
  ```

- 문서와 주석은 한국어로, 전문 용어보다 쉬운 말로 쓴다.
- 커밋·푸시·PR은 사용자가 요청할 때 한다. 푸시는 작업 브랜치로만 하고 main에는 PR로만 합친다.
