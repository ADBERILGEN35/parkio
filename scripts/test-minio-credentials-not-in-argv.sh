#!/usr/bin/env bash
#
# Credential incident 2026-10-13 (MinIO review M1): the MinIO root credentials must never be a process argument.
# An `-e NAME=value` or a positional `mc alias set ALIAS URL USER PASSWORD` sits in /proc/<pid>/cmdline of the host
# docker CLI, or of mc inside the container (a host process too), for the whole run, readable by every local account.
#
# 1. scripts/backup-minio.sh runs against a fake `docker` that records every argv and the stdin of `docker run`:
#    the synthetic user and password must appear in no argv, and must reach the container on stdin;
# 2. scripts/restore-hosted-beta.sh's MinIO restore must use the same form (static check of its docker run).
#
# Usage: ./scripts/test-minio-credentials-not-in-argv.sh   (no Docker, no network, synthetic values only)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-argv.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
FAILS=0
ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILS=$((FAILS + 1)); }

USER_VALUE="argvuser$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
PASS_VALUE="argvsecret$(od -An -N18 -tx1 /dev/urandom | tr -d ' \n')"
mkdir -p "${WORK}/bin" "${WORK}/dest"
cat > "${WORK}/env" <<ENV
MINIO_ROOT_USER=${USER_VALUE}
MINIO_ROOT_PASSWORD="${PASS_VALUE}"
MINIO_MC_IMAGE=example.invalid/mc@sha256:0000000000000000000000000000000000000000000000000000000000000000
ENV
cat > "${WORK}/bin/docker" <<'FAKE'
#!/usr/bin/env bash
# fake docker: record argv (one NUL-separated record per call), answer what backup-minio.sh asks
printf '%s\0' "$@" >> "${FAKE_LOG}/argv"
printf '\n' >> "${FAKE_LOG}/argv"
case "$1" in
  inspect)
    for a in "$@"; do [ "$a" = "--format" ] && { echo "parkio-backend"; exit 0; }; done
    exit 0 ;;
  run)
    # read stdin only when docker would pass it (-i); otherwise the old form would wait on the caller's stdin
    case " $* " in *" -i "*) cat > "${FAKE_LOG}/run-stdin" ;; *) : > "${FAKE_LOG}/run-stdin" ;; esac
    printf '%s\n' "${@: -1}" > "${FAKE_LOG}/run-script"
    echo 0
    exit 0 ;;
esac
exit 0
FAKE
chmod +x "${WORK}/bin/docker"

# 1. backup-minio.sh
out="$(FAKE_LOG="${WORK}" PATH="${WORK}/bin:${PATH}" PARKIO_ENV_FILE="${WORK}/env" "${ROOT}/scripts/backup-minio.sh" "${WORK}/dest" 2>&1)" \
  && ok "backup-minio.sh runs against the fake docker" || fail "backup-minio.sh runs against the fake docker: ${out}"
if [ -s "${WORK}/argv" ] && ! grep -a -q -F -e "${PASS_VALUE}" -e "${USER_VALUE}" "${WORK}/argv"; then
  ok "backup-minio.sh: neither the user nor the password appears in any docker argument"
else
  fail "backup-minio.sh: a credential appears in a docker argument (or docker was never called)"
fi
if [ -f "${WORK}/run-stdin" ] && [ "$(sed -n 1p "${WORK}/run-stdin")" = "${USER_VALUE}" ] && [ "$(sed -n 2p "${WORK}/run-stdin")" = "${PASS_VALUE}" ]; then
  ok "backup-minio.sh: docker run receives the user and the password on stdin"
else
  fail "backup-minio.sh: docker run did not receive the pair on stdin"
fi
if grep -q -E 'mc alias set local http://minio:9000 >/dev/null' "${WORK}/run-script" && ! grep -q 'MINIO_ROOT_PASSWORD' "${WORK}/run-script"; then
  ok "backup-minio.sh: mc reads the keys from stdin (no positional credential in its script)"
else
  fail "backup-minio.sh: mc's script still passes a credential on its command line"
fi
if tr '\0' '\n' < "${WORK}/argv" | grep -q -x -- '-i'; then ok "backup-minio.sh: docker run keeps stdin open (-i)"; else fail "backup-minio.sh: docker run lacks -i"; fi

# 2. restore-hosted-beta.sh (static: its MinIO restore needs a manifest and a destination to run)
R="${ROOT}/scripts/restore-hosted-beta.sh"
if ! grep -q -E -- '-e "MINIO_ROOT_(USER|PASSWORD)=|"\$MINIO_ROOT_PASSWORD"|-e "MINIO_ROOT_PASSWORD' "${R}" \
   && grep -q -E "printf '%s\\\\n%s\\\\n' \"\\\$\{minio_user\}\" \"\\\$\{minio_password\}\" \| MSYS_NO_PATHCONV=1 docker run --rm -i" "${R}" \
   && grep -q -E 'mc alias set local "\$ENDPOINT"$' "${R}"; then
  ok "restore-hosted-beta.sh: the MinIO restore passes the pair on stdin and in no argument"
else
  fail "restore-hosted-beta.sh: the MinIO restore still puts a credential in an argument"
fi

echo "=== 6 checks, ${FAILS} FAIL ==="
[ "${FAILS}" -eq 0 ]
