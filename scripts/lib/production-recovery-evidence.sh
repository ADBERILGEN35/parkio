#!/usr/bin/env bash
# Default-off production recovery *evidence validation* adapter (#367).
#
# Reuses the stage-4 verifier (scripts/lib/recovery-evidence.py) against synthetic or
# operator-supplied bundles. This adapter NEVER:
#   - flips verifiedCoverage to a true production claim
#   - authorizes production decrypt/apply/expose
#   - bypasses parkio_restore_refuse_unverified_production
#
# Enablement flag (default off): PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=1
# Exit codes: 0 = evidence trusted under the adapter (still not production coverage);
#             3 = BLOCKED / refused; 2 = usage.
set -euo pipefail

parkio_production_recovery_evidence_enabled() {
  [ "${PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER:-0}" = "1" ]
}

# Evaluate a bundle+trust pair. Arguments:
#   --bundle PATH --trust PATH --attempt UUID --dataset ID --target-identity ID --out PATH
parkio_production_recovery_evidence_evaluate() {
  local root="${PARKIO_REPO_ROOT:-}"
  if [ -z "${root}" ]; then
    root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  fi

  if ! parkio_production_recovery_evidence_enabled; then
    echo "REFUSED P-DEFAULT-OFF: production recovery evidence adapter is disabled." >&2
    echo "Set PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=1 only for authorized synthetic/validation runs." >&2
    echo "verifiedCoverage remains false; production restore stays BLOCKED." >&2
    return 3
  fi

  local bundle="" trust="" attempt="" dataset="" target="" out=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --bundle) bundle="${2:-}"; shift 2 ;;
      --trust) trust="${2:-}"; shift 2 ;;
      --attempt) attempt="${2:-}"; shift 2 ;;
      --dataset) dataset="${2:-}"; shift 2 ;;
      --target-identity) target="${2:-}"; shift 2 ;;
      --out) out="${2:-}"; shift 2 ;;
      *)
        echo "ERROR: unknown argument: $1" >&2
        return 2
        ;;
    esac
  done

  if [ -z "${bundle}" ] || [ -z "${trust}" ] || [ -z "${attempt}" ] \
      || [ -z "${dataset}" ] || [ -z "${target}" ] || [ -z "${out}" ]; then
    echo "REFUSED P-MISS: --bundle, --trust, --attempt, --dataset, --target-identity and --out are required." >&2
    return 3
  fi
  if [ ! -f "${bundle}" ]; then
    echo "REFUSED P-MISS: erasure evidence bundle is missing: ${bundle}" >&2
    return 3
  fi
  if [ ! -f "${trust}" ]; then
    echo "REFUSED P-KEY-CUSTODY: trust document is missing: ${trust}" >&2
    return 3
  fi

  local out_dir
  out_dir="$(dirname "${out}")"
  if [ ! -d "${out_dir}" ]; then
    echo "ERROR: --out directory does not exist: ${out_dir}" >&2
    return 2
  fi

  local rc=0
  python3 "${root}/scripts/lib/recovery-evidence.py" verify \
    --bundle "${bundle}" --trust "${trust}" --attempt "${attempt}" \
    --dataset "${dataset}" --target-identity "${target}" --out "${out}" || rc=$?

  if [ "${rc}" -eq 3 ]; then
    echo "REFUSED: erasure evidence is not trusted (P-CORR / P-STALE / P-INCOMPLETE / P-KEY-CUSTODY)." >&2
    echo "verifiedCoverage remains false; nothing may be decrypted or applied on the production path." >&2
    return 3
  elif [ "${rc}" -ne 0 ]; then
    echo "ERROR: erasure evidence could not be checked (rc=${rc})." >&2
    return 2
  fi

  echo "ADAPTER_OK: evidence trusted under the default-off validation adapter."
  echo "verifiedCoverage=false"
  echo "production_restore=BLOCKED"
  echo "This verdict is not production coverage and does not authorize restore-hosted-beta apply."
  return 0
}
