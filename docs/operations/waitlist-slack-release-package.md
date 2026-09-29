# Release package — waitlist Slack operational notifications (PR #74)

**Verdict:** source preparation is ready for terminal CI and human review.
**Not authorised for activation.** No production mutation, package install,
real Slack/email send, merge, deployment, secret change or feature activation
was performed. Registration remains closed and New Relic was not restarted or
changed.

Design and runbook: [waitlist-slack-notifications.md](waitlist-slack-notifications.md).
Deferred work: [waitlist-slack-follow-ups.md](waitlist-slack-follow-ups.md).

## Identities

| Item | Exact identity |
|---|---|
| Reconciled base | `origin/api` @ `e851f133c90330572f26ecc0eeeb0acfb92db1ed` (merged PR #78 and its PR #79 auth release pin) |
| Final code-bearing source / candidate build source | `008a6b2077f64d82a83da6d88e7bb6efeeafb498` |
| Local gateway candidate | `parkio-gateway-waitlist-candidate:008a6b2077f6`; image/index id and local digest `sha256:16f7d5249763008c0856c0c1c3310eb8b17137ae50069b99dc09a489504651a1`; OCI revision `008a6b20…`; `linux/amd64`; `USER parkio`; real `services/gateway-service/Dockerfile`; not pushed |
| Deployable candidate | **Not produced.** The release pipeline must build it from the eventual merge SHA. Accept only a digest whose OCI revision equals that SHA. |
| Live rollback target (read-only verified) | `ghcr.io/adberilgen35/parkio/gateway-service@sha256:8b8a08ba974aeec91ec690ada406552a474c2319880752d4d4ea5ff1798028aa`; revision `83fa625b9e37d6c0819862459d5570e305dd5121`; `linux/amd64` |
| Live gateway database | `postgres:16-alpine` (`sha256:cf78e76683b9…`), PostgreSQL `16.15`; Flyway history `1:true,2:true,3:true`; V4 absent before release |

The repository/host GMP pin still names stale digest `5c66e0fb…`; it is not
the rollback target. The running digest above is authoritative for rollback.

## Exact-image security

Trivy `v1.22.0`, vulnerability scanner, no `--ignore-unfixed`, scanned the
exact local candidate `sha256:16f7d524…` after the final code merge:

- HIGH/CRITICAL policy view: `0 HIGH`, `0 CRITICAL`, including unfixed.
- Full severity inventory: `80` package occurrences — `76 MEDIUM`, `4 LOW`,
  `0 UNKNOWN`, `0 HIGH`, `0 CRITICAL`.
- `61` occurrences are unfixed (`57 MEDIUM`, `4 LOW`; 47 unique advisory ids).
  `19` occurrences have a fixed version available (18 unique advisory ids).
- Target split: Ubuntu 26.04 packages `73`; `app.jar` dependencies `7`;
  `/usr/bin/pebble` `0`.

These unfixed MEDIUM/LOW findings are disclosed, not waived as absent. The
local digest is evidence only; the eventual release-built digest needs its own
terminal scan.

## Acceptance

| Check | Result / scope |
|---|---|
| Gateway unit tests at pre-merge code tip `89e58aef…` | `212/212`, 0 skipped/failures/errors |
| PostgreSQL integration test | `6/6`, PostgreSQL 16.15, V1–V4, real `JdbcTransactionManager`; includes savepoint failure isolation and a failing negative control with savepoint removed |
| Final candidate Docker build at `008a6b20…` | PASS; `bootJar`, 16 tasks, clean OCI revision/platform identity |
| Waitlist relay acceptance after base reconciliation | `19/19`, including queue-full and low-disk admission refusal/resume with pending work preserved |
| Existing relay acceptance | `15/15` |
| Existing relay reliability | `13 PASS`, `0 FAIL`, `1 NOT_EXECUTED` (live Kafka, unchanged) |
| Production Compose integration against `origin/api` | PASS; 32 services and all auth/web/GMP images unchanged; only two default-off gateway env keys; activation overlay changes only gateway group/mount/export-dir and refuses a missing gid |
| Disposable Civo systemd acceptance | `18/18`; hidden-input mock webhook install, non-Slack refusal, Alertmanager-webhook refusal, secret unchanged after refusals, root `0600`, waitlist-only units, registration path refused; consumer exposure `0.6 SAFE`, worker `1.3 OK` |
| Final candidate → relay → mock Slack e2e | `11/11`; synthetic data/internal Docker network; includes crash recovery, disabled paths and privacy scan |
| **Current live rollback image on V4** | PASS (E09): `8b8a08ba…` became healthy on PostgreSQL 16.15, validated four migrations, reported schema 4 newer than its V3 code, applied nothing, confirmation succeeded, and history stayed `1:true,2:true,3:true,4:true`; final candidate roll-forward passed (E10) |

Evidence bundles:

- `agent-tools/parkio-waitlist-slack-release-prep-01/20260922T114456Z/`
- `agent-tools/parkio-waitlist-slack-release-prep-02/20260922T161335Z/`
- `agent-tools/parkio-waitlist-slack-release-prep-03/20260922T164000Z/e2e/`

The base merge changed auth-owned files only. Gateway/relay implementation was
unchanged, so focused relay/Compose checks and a final candidate build were
rerun; the unchanged PostgreSQL/systemd results were reused. Terminal PR CI on
the pushed final documentation head must still be recorded in the PR handoff.

## Effective default-off runtime wiring

- Production file set passes only
  `PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=false` and the dedicated
  environment key. No export directory, group or inbox mount exists by default.
- `docker/docker-compose.waitlist-ops-inbox.yml` is deliberately outside
  `docker/compose.production.files`. An operator adds it only during the
  separately authorised configure-relay phase; it requires the dedicated host
  group gid and uses `create_host_path: false`.
- The gateway has no Slack/webhook secret and never contacts Slack.
- `install-relay.sh` installs only the waitlist consumer and single worker under
  `parkio-slackbiz`; it refuses legacy registration/Kafka settings and unexpected
  `parkio-slack-biz-*` units.
- The repository owner/operator installs the webhook with
  `install-webhook.sh` through hidden input. It is atomically stored only in
  `/etc/parkio/slack-biz.secret.env` as `root:root 0600`; installation does not
  enable delivery.

## Backlog, retention and monitoring

- Terminal gateway rows: 30 days; inbox `.acked`: 24 hours; rejected envelopes:
  deleted by default (optional bounded 72-hour forensic retention); relay
  terminal/dedup: 168 hours; DLT: 720 hours.
- Pending work is never silently purged. Relay admission refuses at 5,000
  pending rows or below 256 MiB free and leaves files in the inbox. Gateway
  export defers at 5,000 inbox files or below 512 MiB free and leaves rows
  `PENDING` without consuming an attempt. This can delay or ultimately forfeit
  the Slack notification if an operator explicitly discards the backlog, but
  it does not roll back an already committed confirmation.
- Pending gateway rows remain count-unbounded by design (~250 B each); if the
  PostgreSQL filesystem itself fills, confirmations can fail. Existing host
  disk/inode/read-only alerts and the new gateway pending/backpressure metrics
  provide the operational warning path. Dedicated metric alerts remain WSN-F5.
- The legacy registration consumer remains OFF and uninstalled. Its raw
  rejected-file/logging issue is tracked separately as WSN-F4.

## Rollout and rollback gates

1. Deploy the release-built gateway **disabled**, without the inbox overlay;
   verify health and Flyway V4.
2. Reconcile the host checkout, install the waitlist-only relay package, add
   group/mount wiring, and verify queueing while Slack delivery remains off.
3. The repository owner separately installs the webhook and explicitly enables
   the worker. Activation is not implied by deployment.
4. Disable Slack delivery by setting the relay flag false and restarting only
   its worker. Stop production by setting the gateway flag false and recreating
   only gateway-service. Neither action restarts unrelated applications or New
   Relic.
5. Code rollback uses live digest `8b8a08ba…`; V4 remains in place and unused.

## Remaining release gates

- Terminal required/applicable CI must pass on the final pushed PR head.
- The eventual release-built gateway digest must carry the merge SHA and pass
  exact-image scanning.
- Before any production action, reconcile `/opt/parkio` host drift and replace
  the stale gateway pin with the release digest while preserving auth/web pins.
- Production installation, deploy, configuration and activation remain separate
  operator decisions outside this source-preparation handoff.
