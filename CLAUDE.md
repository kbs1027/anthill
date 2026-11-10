# anthill 작업 규칙

구조·실행·테스트 방법은 [README.md](README.md)에 있다.

## 반드시 지키는 것

1. **작업은 최신 main에서 만든 worktree 안에서 한다.** 작업을 시작할 때 `git fetch origin main`을 한 뒤 `origin/main` 기준으로 worktree를 새로 만든다. 원래 폴더에서 바로 고치지 않는다.
2. **main에는 직접 푸시하지 않는다.** 작업 브랜치를 푸시하고 PR로만 합친다. 훅이나 GitHub 보호 규칙이 막으면 우회하지 않는다(`--no-verify`, 강제 푸시, 보호 규칙 해제 금지).

구현 작업의 절차는 명령으로 실행한다. 전체는 `/work <작업 설명>`, 단계별로는 `/work-start`, `/work-plan`, `/work-implement`, `/work-test`, `/work-e2e`, `/work-report`.
