#!/bin/bash
# 전체 테스트를 돌린다. 백엔드 통합 테스트는 Testcontainers를 쓰므로 Docker가 켜져 있어야 한다.
# 사용: bash .claude/skills/work-test/run-tests.sh [backend|frontend]   (안 주면 둘 다)
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

backend() {
  if ! docker info >/dev/null 2>&1; then
    echo "Docker가 꺼져 있다. 백엔드 통합 테스트를 돌릴 수 없다." >&2
    return 1
  fi
  (cd "$root/backend" && ./gradlew test)
}
frontend() { (cd "$root/frontend" && { [ -d node_modules ] || npm ci; } && npx vitest run && npx tsc -b); }

case "$target" in
  backend) run "백엔드 테스트 (gradlew test)" backend ;;
  frontend) run "프론트엔드 테스트 (vitest run, tsc -b)" frontend ;;
  all)
    run "백엔드 테스트 (gradlew test)" backend
    run "프론트엔드 테스트 (vitest run, tsc -b)" frontend
    ;;
  *) echo "사용: run-tests.sh [backend|frontend]" >&2; exit 2 ;;
esac

echo
echo "=== 테스트 결과 ==="
printf '%s\n' "${results[@]}"
exit $fail
