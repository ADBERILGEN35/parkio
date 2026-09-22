#!/usr/bin/env bash
set -euo pipefail
# Report MapTiler key presence without revealing value
for f in /opt/parkio/docker/.env /opt/parkio/docker/.env.azure-hosted-beta /opt/parkio/docker/.env.hosted-beta; do
  if [ -f "$f" ]; then
    if grep -Eq '^VITE_MAPTILER_KEY=.+$' "$f"; then
      echo "FILE=$f MAPTILER_KEY=PRESENT"
    else
      echo "FILE=$f MAPTILER_KEY=MISSING_OR_EMPTY"
    fi
  fi
done
# Also check compose env on running web (baked, not runtime env usually)
docker exec parkio-web sh -c 'test -d /usr/share/nginx/html/assets && echo WEB_ASSETS=OK || echo WEB_ASSETS=MISSING'
