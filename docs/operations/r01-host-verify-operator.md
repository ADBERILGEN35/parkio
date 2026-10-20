# R-01 host verify — operator command (checksum-enforced)

## Where to run
**Computer / terminal:** an operator workstation that can complete SSH to the configured
production host, then a **login shell on that host** as the configured deploy user with
cwd under `/opt/parkio`.

Use the established host alias / SSH config for this environment (do not embed host
addresses or account names in this document). Example: `Host parkio-prod` in the
operator SSH config providing `User` / `HostName` from the private inventory.

## Two different checksums
| Object | Full SHA-256 |
|--------|----------------|
| **Helper** `scripts/ops/r01-verify-real-e2e-accounts-checksum-enforced.sh` | `59008cd5a2812eefb2998f20546711a029db5e4fe1d37758d2941fb47c9bf30e` |
| **Target** `scripts/ops/verify-real-e2e-accounts.sh` (enforced inside helper) | `4d3db3f28acd223f22f461b9b3f0df112a4472ba123caf2bf98bf7588fe53c8a` |

Re-verify both digests against the reviewed `api` revision before execution.

## Staged target when the tree path is absent or dirty
If `/opt/parkio/scripts/ops/verify-real-e2e-accounts.sh` is missing or must not be
overwritten, stage the reviewed bytes at a **new** path (for example
`verify-real-e2e-accounts.sh.reviewed-r01`) and run:

    TARGET_OVERRIDE=/opt/parkio/scripts/ops/verify-real-e2e-accounts.sh.reviewed-r01 \
      PARKIO_ROOT=/opt/parkio \
      bash /path/to/r01-verify-real-e2e-accounts-checksum-enforced.sh

`TARGET_OVERRIDE` is applied before the existence check. The unsupported `TARGET=` name
is ignored.

## Complete command (checksum-enforced helper download)
Set `HELPER_COMMIT` to the reviewed commit whose helper blob matches the SHA-256 table.
The command verifies the **helper** before bash, stops on mismatch, does not touch dirty
trees, and prints no secrets.

    HELPER_COMMIT='<reviewed-commit-sha>'
    HELPER_PATH='scripts/ops/r01-verify-real-e2e-accounts-checksum-enforced.sh'
    HELPER_SHA256='59008cd5a2812eefb2998f20546711a029db5e4fe1d37758d2941fb47c9bf30e'
    RAW="https://raw.githubusercontent.com/ADBERILGEN35/parkio/${HELPER_COMMIT}/${HELPER_PATH}"

    ssh -o BatchMode=yes -o StrictHostKeyChecking=yes parkio-prod "bash -s" <<EOF
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
    bash "\$TMP"
    EOF

Env discovery uses the live compose labels / selected `docker/.env.*` overlay. The helper
enforces the **target** script SHA-256 before calling it.
