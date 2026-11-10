#!/bin/bash
# main을 최신으로 받고, origin/main 기준으로 작업용 worktree를 만든다.
# 사용: bash .claude/skills/work-start/new-worktree.sh <작업이름>
#   → <저장소>/.claude/worktrees/<작업이름> 폴더, worktree-<작업이름> 브랜치
# 이미 있으면 새로 만들지 않고 그 worktree가 최신 main 위에 있는지만 확인한다.
# 마지막 줄에 "WORKTREE=<절대 경로>"를 출력한다.
set -u

name=${1:-}
if ! [[ $name =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "작업이름은 영어 소문자·숫자·하이픈으로 쓴다 (예: order-form). 받은 값: '$name'" >&2
  exit 2
fi

# 다른 worktree 안에서 실행해도 원래 저장소 아래에 만든다.
common=$(git rev-parse --path-format=absolute --git-common-dir) || exit 1
root=$(dirname "$common")
path="$root/.claude/worktrees/$name"
branch="worktree-$name"

echo "main을 최신으로 받는다…"
git -C "$root" fetch origin main || { echo "origin/main을 받지 못했다. 최신 main이 아닐 수 있어 멈춘다." >&2; exit 1; }

if [ -e "$path" ]; then
  echo "이미 있는 worktree를 쓴다: $path"
  if [ "$(git -C "$path" branch --show-current)" != "$branch" ]; then
    echo "그 폴더의 브랜치가 $branch 가 아니다. 직접 확인이 필요하다." >&2
    exit 1
  fi
else
  git -C "$root" worktree add "$path" -b "$branch" origin/main || exit 1
fi

if git -C "$path" merge-base --is-ancestor origin/main HEAD; then
  echo "최신 main 위에 있다: $(git -C "$root" log --oneline -1 origin/main)"
else
  echo "이 worktree는 최신 main 위에 있지 않다. origin/main: $(git -C "$root" log --oneline -1 origin/main)" >&2
  echo "WORKTREE=$path"
  exit 3
fi
echo "WORKTREE=$path"
