#!/bin/bash
# 전체를 띄우기 전에, 이미 떠 있는 anthill 컨테이너와 쓰이고 있는 포트를 보여 준다.
# 모든 폴더가 같은 프로젝트 이름(anthill)과 포트를 쓰므로, 떠 있는 것이 있으면 여기서 띄울 때 교체된다.
# 사용: bash .claude/skills/work-e2e/check-running.sh   (아무것도 없으면 종료 코드 0, 있으면 1)

found=0

containers=$(docker ps --filter "label=com.docker.compose.project=anthill" --format '{{.Names}}\t{{.Status}}\t{{.Label "com.docker.compose.project.working_dir"}}' 2>/dev/null)
if [ -n "$containers" ]; then
  found=1
  echo "떠 있는 anthill 컨테이너 (이름 / 상태 / 띄운 폴더):"
  echo "$containers" | sed 's/^/  /'
else
  echo "떠 있는 anthill 컨테이너: 없음"
fi

echo "포트:"
for port in 3000 5173 8080 8081 8082 8083 5432; do
  owner=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1 " (pid " $2 ")"}')
  if [ -n "$owner" ]; then
    found=1
    echo "  $port 사용 중: $owner"
  else
    echo "  $port 비어 있음"
  fi
done

exit $found
