#!/usr/bin/env bash
# D0.2.1 - read-only, value-free snapshot of the running Compose project on parkio-civo-prod.
#
# Release <PIN> = 22f9699038cdae15ffff5dd7433c4a64aed0a951
# (RELEASE-EXECUTION-20261008.md, amendments A1-A5).
#
# Run as civo on the host:
#   bash -n /tmp/d021-state-snapshot.sh && bash /tmp/d021-state-snapshot.sh; echo "exit=$?"
#
# Writes only evidence files under $HOME/parkio-release-20261009 (outside both checkouts).
# Changes no container, mount, image, volume, checkout or system file.
# Prints no environment value, rendered configuration or cron command body.
# Stops with exit 1 when an expected input is missing.
set -euo pipefail
export LC_ALL=C

PIN=22f9699038cdae15ffff5dd7433c4a64aed0a951
PROJECT=parkio
PROJECT_FILTER=label=com.docker.compose.project=parkio
LIVE_DIR=/opt/parkio
RELEASE_DIR=/opt/parkio-release
LIVE_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta
TEXTFILE_LIVE=/opt/parkio/docker/prometheus/textfile
TEXTFILE_RELEASE=/opt/parkio-release/docker/prometheus/textfile
EXPECTED_PROJECT_DIR=/opt/parkio/docker
EXPECTED_LIVE_HEAD=bf9cad51
OUT="$HOME/parkio-release-20261009"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

stop() {
  echo "STOP: $*"
  exit 1
}

# 1. Inputs
for dir in "$LIVE_DIR" "$RELEASE_DIR" "$TEXTFILE_LIVE" "$TEXTFILE_RELEASE"; do
  [ -d "$dir" ] || stop "missing directory $dir"
done
[ -f "$LIVE_ENV_FILE" ] || stop "missing env file $LIVE_ENV_FILE"
RELEASE_HEAD="$(git -C "$RELEASE_DIR" rev-parse HEAD)"
[ "$RELEASE_HEAD" = "$PIN" ] || stop "$RELEASE_DIR is at $RELEASE_HEAD, not $PIN"
command -v docker >/dev/null || stop "docker not found"
docker compose version >/dev/null 2>&1 || stop "docker compose plugin not found"
install -d -m 700 "$OUT"

# 2. Versions and container counts
echo "script=d021-state-snapshot stamp=$STAMP pin=${PIN:0:12}"
echo "docker_server=$(docker version --format '{{.Server.Version}}')"
echo "compose=$(docker compose version --short)"
echo "storage_driver=$(docker info --format '{{.Driver}}')"
TOTAL="$(docker ps -aq --filter "$PROJECT_FILTER" | wc -l)"
RUNNING="$(docker ps -q --filter "$PROJECT_FILTER" | wc -l)"
[ "$TOTAL" -gt 0 ] || stop "no container carries $PROJECT_FILTER"
echo "project=$PROJECT containers_total=$TOTAL running=$RUNNING"
STATE_FMT='{{.Names}} {{.State}}'
docker ps -a --filter "$PROJECT_FILTER" --format "$STATE_FMT" \
  | awk '$2 != "running" { print "  not running:", $1, $2 }'
OTHER_FMT='{{.Names}} {{.Label "com.docker.compose.project"}}'
OTHER_AWK='$2 != p { print "  parkio-named container outside the project:", $1, ($2 == "" ? "(no label)" : $2) }'
docker ps -a --filter name=parkio --format "$OTHER_FMT" | awk -v p="$PROJECT" "$OTHER_AWK"

# 3. Per-container record: service, name, state, image ref, image id, compose image label,
#    compose config hash, created. Kept in a file only.
IDS="$(docker ps -aq --filter "$PROJECT_FILTER")"
REC_FMT='{{index .Config.Labels "com.docker.compose.service"}} {{.Name}} {{.State.Status}}'
REC_FMT+=' {{.Config.Image}} {{.Image}} {{index .Config.Labels "com.docker.compose.image"}}'
REC_FMT+=' {{index .Config.Labels "com.docker.compose.config-hash"}} {{.Created}}'
REC_FILE="$OUT/D021-containers-$STAMP.txt"
# shellcheck disable=SC2086  # IDS is a whitespace-separated list of container ids
docker inspect --format "$REC_FMT" $IDS | sort > "$REC_FILE"
echo "per-container record: $(wc -l < "$REC_FILE") lines in $REC_FILE"

# 4. Project directory and compose files recorded on the containers
LABEL_FMT='{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
LABEL_FMT+=' {{index .Config.Labels "com.docker.compose.project.config_files"}}'
LABEL_FILE="$OUT/D021-project-labels-$STAMP.txt"
# shellcheck disable=SC2086
docker inspect --format "$LABEL_FMT" $IDS | sort | uniq -c > "$LABEL_FILE"
LABEL_AWK='{
  n = split($3, f, ","); s = ""
  for (i = 1; i <= n; i++) { k = split(f[i], p, "/"); s = s (i > 1 ? "," : "") p[k] }
  print "  " $1 " container(s): dir=" $2 ($2 == want ? " (expected)" : " (UNEXPECTED)") " files=" s
}'
echo "project directory and compose files recorded on the containers:"
awk -v want="$EXPECTED_PROJECT_DIR" "$LABEL_AWK" "$LABEL_FILE"

# 5. Bind mounts (paths only)
BIND_FMT='{{.Name}}{{range .Mounts}}{{if eq .Type "bind"}} {{.Source}}=>{{.Destination}}{{end}}{{end}}'
BIND_FILE="$OUT/D021-binds-$STAMP.txt"
# shellcheck disable=SC2086
docker inspect --format "$BIND_FMT" $IDS | sed 's#^/##' | sort > "$BIND_FILE"
BIND_AWK='{ n = 0; for (i = 2; i <= NF; i++) if (index($i, d) == 1) n++; if (n) printf "  %s=%d", $1, n }
END { print "" }'
echo "containers with bind sources under $LIVE_DIR/ (count):"
awk -v d="$LIVE_DIR/" "$BIND_AWK" "$BIND_FILE"

# 6. node-exporter textfile collector bind
NE_ID="$(docker ps -aq --filter "$PROJECT_FILTER" --filter label=com.docker.compose.service=node-exporter)"
if [ -z "$NE_ID" ]; then
  echo "node-exporter: no container in the project"
else
  NE_STATE="$(docker inspect --format '{{.State.Status}}' "$NE_ID")"
  NE_FMT='{{range .Mounts}}{{if eq .Destination "/textfile-collector"}}{{.Source}}{{end}}{{end}}'
  NE_SRC="$(docker inspect --format "$NE_FMT" "$NE_ID")"
  if [ "$NE_SRC" = "$TEXTFILE_LIVE" ]; then
    NE_MATCH="equals TEXTFILE_LIVE"
  else
    NE_MATCH="DIFFERS from TEXTFILE_LIVE"
  fi
  echo "node-exporter: state=$NE_STATE textfile bind source=${NE_SRC:-none} ($NE_MATCH)"
fi

# 7. Textfile directories: names and metadata only, never file contents
for dir in "$TEXTFILE_LIVE" "$TEXTFILE_RELEASE"; do
  stat -c '%n type=%F owner=%U:%G mode=%a dev:inode=%d:%i' "$dir"
  echo "  entries: $(ls -A "$dir" | tr '\n' ' ')"
done
echo "live textfile entries (mode owner size mtime name):"
ls -lA --time-style=+%FT%T%z "$TEXTFILE_LIVE" | awk 'NR > 1 { print "  " $1, $3 ":" $4, $5, $6, $7 }'
KEY_LINES="$(grep -c '^PARKIO_PROMETHEUS_TEXTFILE_DIR=' "$LIVE_ENV_FILE" || true)"
echo "env key PARKIO_PROMETHEUS_TEXTFILE_DIR lines: $KEY_LINES"

# 8. Scheduled jobs: source, line, schedule and script name only (never the command body)
CRON_AWK='
  /^[[:space:]]*(#|$)/ { next }
  $1 ~ /^[A-Za-z_][A-Za-z0-9_]*=/ { next }
  tolower($0) !~ /parkio|backup|textfile/ { next }
  {
    if ($1 ~ /^@/) { sched = $1; first = 2 }
    else { sched = $1 " " $2 " " $3 " " $4 " " $5; first = 6 }
    if (hasuser == 1) first++
    job = "(no script name)"
    for (i = first; i <= NF; i++) {
      t = $i; gsub(/["\047;()&|<>]/, "", t)
      if (t !~ /=/ && t ~ /\.(sh|py)$/) { n = split(t, p, "/"); job = p[n]; break }
    }
    print "  " src " line " FNR ": schedule=\"" sched "\" job=" job
  }'
cron_jobs() {
  awk -v src="$1" -v hasuser="$2" "$CRON_AWK"
}
SCHED_FILE="$OUT/D021-schedules-$STAMP.txt"
: > "$SCHED_FILE"
{ crontab -l 2>/dev/null || true; } | cron_jobs "crontab(civo)" 0 >> "$SCHED_FILE"
if [ -r /etc/crontab ]; then
  cron_jobs /etc/crontab 1 < /etc/crontab >> "$SCHED_FILE"
fi
for f in /etc/cron.d/*; do
  if [ -f "$f" ] && [ -r "$f" ]; then
    cron_jobs "$f" 1 < "$f" >> "$SCHED_FILE"
  fi
done
TIMER_AWK='{ u = $(NF - 1); $NF = ""; $(NF - 1) = ""; print "  timer " u ": " $0 }'
{ systemctl list-timers --all --no-pager --no-legend 2>/dev/null | grep -iE 'parkio|backup' || true; } \
  | awk "$TIMER_AWK" >> "$SCHED_FILE"
echo "scheduled jobs mentioning parkio/backup/textfile (root's own crontab not read):"
if [ -s "$SCHED_FILE" ]; then
  cat "$SCHED_FILE"
else
  echo "  (none found)"
fi

# 9. Checkouts (git status without optional index writes)
LIVE_HEAD="$(git -C "$LIVE_DIR" rev-parse --short=8 HEAD)"
LIVE_CHANGES="$(git --no-optional-locks -C "$LIVE_DIR" status --porcelain | wc -l)"
RELEASE_CHANGES="$(git --no-optional-locks -C "$RELEASE_DIR" status --porcelain | wc -l)"
echo "live checkout: HEAD=$LIVE_HEAD (expected $EXPECTED_LIVE_HEAD) changes=$LIVE_CHANGES (expected 100)"
echo "release checkout: HEAD=${RELEASE_HEAD:0:8} changes=$RELEASE_CHANGES (expected 0)"
[ "$RELEASE_CHANGES" -eq 0 ] || stop "$RELEASE_DIR is not clean"
echo "D0.2.1 PASS; evidence in $OUT"
