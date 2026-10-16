# R-01 host verify — operator command (checksum-enforced)

## Where to run
**Computer / terminal:** an operator workstation that can complete SSH to production (`civo@74.220.31.27`), then a **login shell on that host** as `civo` with cwd under `/opt/parkio`.  
**Not** the Cursor Windows/WSL agent in this session (TCP/22 works; SSH KEX is reset).

## Two different checksums
| Object | Full SHA-256 |
|--------|----------------|
| **Helper** `scripts/ops/r01-verify-real-e2e-accounts-checksum-enforced.sh` | `ebdf28c55b82540488efbded0b4ee31cd21c12ad9e58ade7b39721455d1aa7c0` |
| **Target** `scripts/ops/verify-real-e2e-accounts.sh` (enforced inside helper) | `4d3db3f28acd223f22f461b9b3f0df112a4472ba123caf2bf98bf7588fe53c8a` |

## Complete command
Replace `HELPER_COMMIT` with the git commit that added these files (recorded in the PR / handoff after push). The command verifies the **helper** before `bash`, stops on mismatch, does not touch dirty trees, and prints no secrets.

```bash
HELPER_COMMIT='HELPER_COMMIT_PLACEHOLDER'
HELPER_PATH='scripts/ops/r01-verify-real-e2e-accounts-checksum-enforced.sh'
HELPER_SHA256='ebdf28c55b82540488efbded0b4ee31cd21c12ad9e58ade7b39721455d1aa7c0'
RAW="https://raw.githubusercontent.com/ADBERILGEN35/parkio/${HELPER_COMMIT}/${HELPER_PATH}"

ssh -o BatchMode=yes -o StrictHostKeyChecking=yes civo@74.220.31.27 "bash -s" <<EOF
set -euo pipefail
TMP=\$(mktemp)
trap 'rm -f "\$TMP"' EXIT
curl -fsSL '$RAW' -o "\$TMP"
ACTUAL=\$(sha256sum "\$TMP" | awk '{print \$1}')
echo "helper_sha256_actual=\$ACTUAL"
echo "helper_sha256_expected=$HELPER_SHA256"
if [ "\$ACTUAL" != "$HELPER_SHA256" ]; then
  echo "ERROR: helper checksum mismatch — refusing to execute" >&2
  exit 3
fi
chmod 0700 "\$TMP"
# Runs on host; discovers docker/.env.azure-hosted-beta + overlays; verifies TARGET script sha256
# 4d3db3f28acd223f22f461b9b3f0df112a4472ba123caf2bf98bf7588fe53c8a before verify-real-e2e-accounts.sh
bash "\$TMP"
EOF
```
