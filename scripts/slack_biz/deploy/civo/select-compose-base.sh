#!/usr/bin/env bash
# U09: the comparison base for validate-compose-integration.sh. It was chosen under the owner's batch
# authorization of 2026-10-05, and the owner may override it:
#   - the merge-base of the candidate (HEAD) with API_REF (default origin/api);
#   - HEAD^1, the previous api commit, when that merge-base is HEAD itself (the candidate is api).
# A pull request into api therefore compares with api's tip, because its merge commit has api as its
# first parent. A base whose tree equals the candidate's fails: comparing the candidate with itself
# proves nothing.
#
#   scripts/slack_biz/deploy/civo/select-compose-base.sh [API_REF]
#
# Prints the base commit on stdout and how it was chosen on stderr. Needs full history (fetch-depth: 0).
set -euo pipefail
API_REF="${1:-origin/api}"
head="$(git rev-parse --verify HEAD)"
base="$(git merge-base HEAD "$API_REF")" || { echo "FAIL: no merge-base between HEAD and $API_REF" >&2; exit 1; }
if [ "$base" = "$head" ]; then
  base="$(git rev-parse --verify --quiet 'HEAD^1')" \
    || { echo "FAIL: HEAD is $API_REF itself and has no parent to compare with" >&2; exit 1; }
  reason="HEAD is $API_REF itself, so its previous commit (HEAD^1)"
else
  reason="the merge-base of HEAD and $API_REF"
fi
if [ "$(git rev-parse "$base^{tree}")" = "$(git rev-parse 'HEAD^{tree}')" ]; then
  echo "FAIL: comparison base $base has the same tree as the candidate $head; nothing would be compared" >&2
  exit 1
fi
echo "comparison base: $base ($reason)" >&2
echo "$base"
