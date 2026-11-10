#!/bin/bash
# Claude가 실행하려는 Bash 명령을 미리 보고, main으로 가는 git push를 막는다.
# .claude/settings.json의 PreToolUse 훅이 부른다. 입력은 stdin의 JSON(tool_input.command, cwd).
# 막을 때는 종료 코드 2와 함께 이유를 stderr에 쓴다. 사람의 푸시는 .githooks/pre-push와 GitHub 보호 규칙이 막는다.

input=$(cat)
case "$input" in *push*) ;; *) exit 0 ;; esac
command -v jq >/dev/null 2>&1 || exit 0

cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty')
cwd=$(printf '%s' "$input" | jq -r '.cwd // empty')

# "git [옵션들] push [인자들]" 꼴만 본다(커밋 메시지 안의 push 같은 글자는 보지 않는다).
git_opts='([[:space:]]+(-[Cc][[:space:]]+[^[:space:]]+|--?[A-Za-z][A-Za-z-]*(=[^[:space:]]+)?))*'
push_re="(^|[;&|(]|[[:space:]])git${git_opts}[[:space:]]+push([[:space:]]+[^;&|]*)?"
[[ $cmd =~ $push_re ]] || exit 0
args=${BASH_REMATCH[5]}

deny() {
  echo "막음: main에는 직접 푸시할 수 없습니다. 작업 브랜치를 푸시하고 PR을 여세요. (CLAUDE.md '반드시 지키는 것' 2번)" >&2
  exit 2
}

# 옵션이 아닌 인자: 첫째는 원격 이름, 나머지는 보낼 대상이다.
targets=()
for word in $args; do
  word=${word//[\"\']/}
  case "$word" in -*|'') continue ;; esac
  targets+=("$word")
done

if [ "${#targets[@]}" -ge 2 ]; then
  # 대상을 적은 경우: main, HEAD:main, +main, refs/heads/main 처럼 main으로 가는 것이 하나라도 있으면 막는다.
  for target in "${targets[@]:1}"; do
    [[ $target =~ ^\+?([^:]*:)?(refs/heads/)?main$ ]] && deny
  done
  exit 0
fi

# 대상을 안 적은 경우: 지금 브랜치가 그대로 올라간다. 명령이 "cd 경로 && ..."로 시작하면 그 폴더 기준으로 본다.
dir=$cwd
if [[ $cmd =~ ^[[:space:]]*cd[[:space:]]+([^;&|[:space:]]+) ]]; then
  moved=${BASH_REMATCH[1]//[\"\']/}
  dir=$(cd "$cwd" 2>/dev/null && cd "${moved/#\~/$HOME}" 2>/dev/null && pwd) || dir=$cwd
fi
branch=$(git -C "${dir:-.}" rev-parse --abbrev-ref HEAD 2>/dev/null)
[ "$branch" = "main" ] && deny
exit 0
