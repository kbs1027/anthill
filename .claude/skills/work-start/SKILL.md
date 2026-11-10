---
name: work-start
description: 작업 0단계. main을 최신으로 받고 origin/main 기준으로 worktree를 만들어 그 안으로 들어간다.
argument-hint: <작업 설명 또는 작업이름>
disable-model-invocation: true
---

# 0단계. 시작: 최신 main에서 worktree 만들기

요청받은 작업: $ARGUMENTS

## 하는 일

1. **작업이름을 정한다.** 요청에서 영어 소문자와 하이픈으로 짧게 짓는다(예: `order-form`). 요청이 비어 있고 대화에서도 무슨 작업인지 알 수 없으면 사용자에게 묻는다.
2. **이미 이 작업의 worktree 안에 있는지 본다.** `git branch --show-current`가 `worktree-<작업이름>`이면 3번을 건너뛴다. 다른 작업의 worktree 안이면 그 사실을 알리고 어떻게 할지 묻는다.
3. **worktree를 만들고 들어간다.**
   1. `bash .claude/skills/work-start/new-worktree.sh <작업이름>`을 실행한다. main을 최신으로 받고, `origin/main` 기준으로 `.claude/worktrees/<작업이름>`에 `worktree-<작업이름>` 브랜치를 만들고, 최신 main 위에 있는지 확인한다.
   2. 스크립트가 실패하면(종료 코드가 0이 아니면) 멈추고 출력 내용을 사용자에게 알린다.
   3. 마지막 줄 `WORKTREE=<경로>`의 경로로 `EnterWorktree` 도구를 `path: <경로>`로 불러 세션을 그 안으로 옮긴다. 도구를 쓸 수 없으면 그 폴더로 이동해서 일한다.

이후 모든 수정은 worktree 안에서만 한다.

## 끝나면

worktree 경로, 브랜치 이름, 기준이 된 main 커밋을 알린다. 다음 단계는 `/work-plan <작업 설명>`이다.
