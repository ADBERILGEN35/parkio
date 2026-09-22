#!/usr/bin/env bash
set -euo pipefail
echo "HOST=$(hostname)"
echo "PROBED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "SSH_USER=$(whoami)"
echo "STRICT_HOST_KEY_CHECKING=yes"

echo "==== PARKING CONTAINER ===="
docker inspect parkio-parking-service-1 --format 'Name={{.Name}} Image={{.Config.Image}} ImageId={{.Image}} Created={{.Created}} Status={{.State.Status}}'
PID=$(docker inspect parkio-parking-service-1 --format '{{.Image}}')
echo "==== PARKING IMAGE ===="
docker image inspect "$PID" --format 'Id={{.Id}} RepoTags={{json .RepoTags}} RepoDigests={{json .RepoDigests}}'

echo "==== WEB CONTAINER ===="
if docker inspect parkio-web >/dev/null 2>&1; then
  WEB=parkio-web
else
  WEB=parkio-web-1
fi
docker inspect "$WEB" --format 'Name={{.Name}} Image={{.Config.Image}} ImageId={{.Image}} Created={{.Created}} Status={{.State.Status}}'
WID=$(docker inspect "$WEB" --format '{{.Image}}')
echo "==== WEB IMAGE ===="
docker image inspect "$WID" --format 'Id={{.Id}} RepoTags={{json .RepoTags}} RepoDigests={{json .RepoDigests}}'

echo "==== COMPOSE PINS ===="
PIN=$(find /home/civo /opt /srv -name 'docker-compose.gmp-release-pins.yml' 2>/dev/null | head -1 || true)
if [ -n "${PIN:-}" ]; then
  echo "PIN_FILE=$PIN"
  grep -nE 'parking-service|^\s+web:|gateway-service' -A3 "$PIN" | head -80
else
  echo "PIN_FILE=NOT_FOUND"
fi
