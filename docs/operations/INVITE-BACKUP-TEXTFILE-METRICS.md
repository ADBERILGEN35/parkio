# Invite-production backup textfile delivery

01E-A1 prepares source only. Live scrape verification is **pending deployment**.
No backup, scheduler activation, container recreation or Slack probe is authorized here.

## One persistent non-secret path

- Host directory: `/var/lib/parkio/observability/textfile`
- Backup output: `/var/lib/parkio/observability/textfile/parkio_backup.prom`
- Node-exporter read-only bind: the host directory to `/textfile-collector`
- Collector flag: `--collector.textfile.directory=/textfile-collector`

Both invite public and dark overlays replace the mount at that container target.
Other exporter mounts and all memory/CPU limits are unchanged. No release SHA,
`current` symlink or backup payload rotation changes this host path.

The root-run backup unit and wrapper select the same directory. The common
writer supports an absolute path (relative paths still work for other profiles).
The new Python standard-library helper is included in the checksummed scheduler
payload. It writes a hidden `.tmp` in the target directory, flushes/fsyncs/closes,
then atomically renames to `parkio_backup.prom` and fsyncs the directory. The leaf
directory is 0755 and the metric file 0644 despite the unit's secret-safe 0077
umask. Only non-secret numeric gauges and validated scope/bucket labels appear.

## Failure and freshness contract

All eight canonical `parkio_backup_*` names remain unchanged. The timestamp is
the completion time of the attempt. The scheduled wrapper publishes failure on
early Key Vault/preflight/backup errors, replacing old green state; a detailed
failure already written by the same run is preserved. Failed DB count remains
zero if no database dump was attempted. Metrics publication failure cannot turn
a failed backup into a successful exit.

Existing failure, stale, offsite and encryption rules now also match
`scope="invite-production"`. Their thresholds and other scopes are unchanged:
stale means older than 90000 seconds, with the existing one-hour `for` duration.
A stopped timer leaves the last completion timestamp frozen, so previous
success cannot remain green forever. Source tests exercise this with promtool.
No new synthetic Slack alert is sent. Initial live acceptance must explicitly
require series presence; absence must never be interpreted as healthy backup.

## Future authorized rollout / rollback

Deploy the matching node-exporter overlay, Prometheus rules, backup helper and
scheduler payload together under a separate approval. Preserve the timer's
existing schedule/enabled state; verify it explicitly after any payload install
because the existing installer does not authorize enabling the timer itself.
Wait for an authorized/scheduled backup, then check the host file, collector
scrape and all eight Prometheus series, completion age, encryption and offsite
signals. Do not certify delivery from a successful backup exit alone.

Rolling back only one half of writer/mount wiring recreates the original blind
spot. Any reviewed rollback must keep writer and collector aligned and record
the resulting live telemetry status. Do not remove encrypted backups or change
encryption, offsite target, retention, DB inventory, restore policy or cadence.
