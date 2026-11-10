---
name: work-e2e
description: 작업 5단계. 전체를 띄우고 실제 사용자처럼 화면을 조작해 계획서의 시나리오를 확인하고, 단계마다 화면을 캡처한다.
argument-hint: "[덧붙일 말]"
disable-model-invocation: true
---

# 5단계. E2E: 실제 사용자처럼 확인한다

사용자가 덧붙인 말: $ARGUMENTS

## 먼저 확인

- `git branch --show-current`가 `worktree-<작업이름>` 꼴인지 본다. 아니면 멈추고 사용자에게 알린다.
- 작업폴더(`docs/work/` 아래 `-<작업이름>`으로 끝나는 폴더)의 `plan.md` 7절 "E2E 시나리오와 캡처할 화면"을 읽는다. 계획서가 없으면 무엇을 확인할지 사용자에게 묻는다.

## 띄우기

1. **띄우기 전에 이미 떠 있는 것을 확인한다.** `bash .claude/skills/work-e2e/check-running.sh`를 실행한다. 모든 폴더가 `anthill`이라는 같은 프로젝트 이름과 포트를 쓰기 때문에, 여기서 띄우면 사용자가 다른 폴더에서 띄워 둔 것이 교체된다. 떠 있는 것이 있으면 내려도 되는지 먼저 묻는다.
2. **worktree 최상위에서 전체를 띄운다.** 실행 방법은 README의 "전체를 컨테이너로 실행하기"를 따른다(`docker compose up -d --build`, 화면은 http://localhost:3000). 처음 띄우면 시세를 채우는 데 30초쯤 걸린다.

## 시나리오

1. `<작업폴더>/e2e.mjs`가 없으면 틀을 복사한다: `cp .claude/skills/work-e2e/e2e-template.mjs <작업폴더>/e2e.mjs`
2. 계획서 7절의 표를 한 줄에 `step` 하나로 옮긴다. 쓰는 법은 틀의 주석과 `scripts/lib/browser.mjs`의 주석에 있다.
   - 단계마다 기대하는 결과를 화면의 값으로 확인하고, 다르면 오류를 던져 실패로 만든다. 캡처만 하고 지나가지 않는다.
   - 이번 작업으로 새로 생기는 화면은 `afterOnly: true`로 표시한다.
3. 실행한다: `node <작업폴더>/e2e.mjs after`. 구현 전 "전" 화면은 `before`로 실행한다(`/work-implement`가 한다).
   - "전"과 "후"는 같은 시나리오 파일로 캡처한다. 구현으로 선택자가 바뀌었으면 시나리오를 고치되 보는 화면은 같게 한다.

## 확인

1. **`shots/after/result.json`을 읽는다.** 실패한 단계와 `pageErrors`(화면에서 난 오류)를 본다. 화면 오류가 이번 작업 때문인지는 `shots/before/result.json`과 비교해 판단한다.
2. **캡처한 PNG를 하나씩 직접 열어 본다**(Read 도구). 기대한 화면이 맞는지, 깨진 곳이나 빈 화면은 없는지 확인한다.
3. **화면이 없는 변경**(백엔드 내부 동작)은 그 결과가 드러나는 화면을 캡처하고, 드러나는 화면이 없으면 API 응답이나 로그를 `<작업폴더>/shots/after/`에 `.txt`로 남긴다.
4. **실패하면 원인을 고치고** 전체 테스트(`bash .claude/skills/work-test/run-tests.sh`)와 E2E를 다시 돌린다.
5. **끝나면 이 작업으로 띄운 것을 내린다**(`docker compose down`). 사용자가 원래 띄워 두었던 것을 내렸다면 그 사실을 알린다.

## 끝나면

시나리오 단계별 결과(통과·실패)와 캡처 파일 위치를 알린다. 실패가 남았으면 그대로 알린다. 다음 단계는 `/work-report`이다.
