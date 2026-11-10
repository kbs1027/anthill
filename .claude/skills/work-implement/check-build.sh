#!/bin/bash
# 빌드가 되는지 확인한다(테스트는 돌리지 않는다).
# 사용: bash .claude/skills/work-implement/check-build.sh [backend|frontend]   (안 주면 둘 다)
# 하나라도 실패하면 종료 코드 1.
set -u

root=$(git rev-parse --show-toplevel) || exit 1
target=${1:-all}
results=()
fail=0

run() { # 이름, 명령…
  local name=$1
  shift
  echo
  echo "▶ $name"
  if "$@"; then results+=("통과 | $name"); else results+=("실패 | $name"); fail=1; fi
}

backend() { (cd "$root/backend" && ./gradlew build -x test); }
frontend() { (cd "$root/frontend" && { [ -d node_modules ] || npm ci; } && npx tsc -b); }

case "$target" in
  backend) run "백엔드 빌드 (gradlew build -x test)" backend ;;
  frontend) run "프론트엔드 타입 검사 (tsc -b)" frontend ;;
  all)
    run "백엔드 빌드 (gradlew build -x test)" backend
    run "프론트엔드 타입 검사 (tsc -b)" frontend
    ;;
  *) echo "사용: check-build.sh [backend|frontend]" >&2; exit 2 ;;
esac

echo
echo "=== 빌드 결과 ==="
printf '%s\n' "${results[@]}"
exit $fail
