#!/usr/bin/env bash
# Non-secret operator acknowledgement, not a replacement for human authorization.
# Must execute before reading delivery secrets or creating acceptance resources.
set -euo pipefail
if [ "${GITHUB_EVENT_NAME:-}" != "workflow_dispatch" ] || \
   [ "${PARKIO_CONFIRM_REAL_SLACK_DELIVERY:-}" != "ALERTING-REAL-SLACK-ACCEPTANCE" ]; then
  echo "BLOCKED: real Slack acceptance requires manual dispatch and exact explicit confirmation" >&2
  exit 2
fi
echo "PASS: explicit manual real-Slack acceptance intent"
