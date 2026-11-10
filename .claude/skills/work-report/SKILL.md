---
name: work-report
description: 작업 6단계. 캡처한 화면으로 보고서를 쓴다. 변경이면 전·후 비교, 생성이면 어떻게 구현됐는지를 화면과 함께 설명한다.
argument-hint: "[덧붙일 말]"
disable-model-invocation: true
---

# 6단계. 보고서

사용자가 덧붙인 말: $ARGUMENTS

## 먼저 확인

- `git branch --show-current`가 `worktree-<작업이름>` 꼴인지 본다. 아니면 멈추고 사용자에게 알린다.
- 작업폴더(`docs/work/` 아래 `-<작업이름>`으로 끝나는 폴더)에서 `plan.md`, `e2e.mjs`, `shots/before/`, `shots/after/`를 확인한다. `shots/after/`가 비어 있으면 멈추고 `/work-e2e`부터 하라고 알린다.
- E2E 결과는 `shots/after/result.json`에서 읽는다. 테스트 결과가 이 대화에 없으면 `bash .claude/skills/work-test/run-tests.sh`를 다시 실행해서 얻는다. 기억이나 짐작으로 숫자를 쓰지 않는다.

## 하는 일

1. [report-template.md](report-template.md) 틀로 `<작업폴더>/report.md`를 쓴다. 계획서의 "종류"에 따라 화면 절이 달라진다.
   - **변경**이면 바뀐 화면마다 전·후 캡처를 나란히 놓고 달라진 곳을 짚는다.
   - **생성**이면 사용자가 쓰는 순서대로 화면을 보여 주고, 화면마다 뒤에서 어떻게 동작하는지 설명한다.
2. 보고서에 넣는 캡처는 직접 열어 보고(Read 도구) 설명과 화면이 맞는지 확인한다. 이미지 경로는 `report.md` 기준 상대 경로로 쓴다(`shots/after/01-….png`).
3. 숫자와 결과는 실제로 나온 출력만 쓴다. 실패했거나 건너뛴 것, 계획과 달라진 것은 그대로 적는다.

## 끝나면

보고서 링크와 결과 요약을 알린다. 커밋·푸시·PR은 사용자가 요청하면 한다. 푸시는 작업 브랜치로만 하고 main에는 PR로만 합친다. PR이 합쳐진 뒤 사용자가 요청하면 worktree를 지운다(`ExitWorktree` 또는 `git worktree remove .claude/worktrees/<작업이름>`).
