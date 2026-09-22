# RELEASE DECISION PACKAGE — Waitlist full name + readable Slack

**PR:** https://github.com/ADBERILGEN35/parkio/pull/84 (draft)  
**Branch:** `feat/waitlist-full-name-readable-slack`  
**Decision head:** `05041de85bcd749d7b0f8a4df4ad1dad98c2350c` (final PR tip)  
**Base:** `origin/api`  
**Scope:** draft only — no merge, no prod migrate/deploy, no real Slack/email, no secret/NR changes, registration CLOSED, provider=resend.

## Identities

| Item | Value |
| --- | --- |
| Draft PR | https://github.com/ADBERILGEN35/parkio/pull/84 |
| Feature commits | `f1de140f`, `d1f8d012`, `cbd8dc23`, `c5264dc8`, `05041de8` |
| Marketing deploy path | Hostinger `web/marketing/` → parkio.dev (separate from app web) |
| Admin UI deploy path | GHCR web image pin / app.parkio.dev (separate step) |
| Gateway / Flyway | V4 → additive V5 (`full_name` nullable + indexes + outbox `interest_id`) |
| Slack biz webhook | unchanged (`PARKIO_SLACK_BIZ_*`) |
| Alertmanager webhook | unchanged (`PARKIO_ALERT_*`) |
| Gateway container scan | Trivy green on tip (Security CI) |

## Configuration delta (safe defaults)

| Knob | Default | Final go-live |
| --- | --- | --- |
| `parkio.waitlist.full-name-required` | `false` (compat) | **`true` after Hostinger marketing upload** |
| `parkio.waitlist.ops-notifications.contract-version` | **`1`** | **`2` after dual-read relay is live** |
| Ops notifications enabled | `false` | unchanged by this PR |

Invalid supplied names are rejected even when the requirement flag is off. Missing name is accepted only while the flag is off. Legacy null names preserved; confirmed re-subscriptions do not overwrite names.

## Exact v2 allowlist

`contractVersion`, `eventId`, `eventType`, `occurredAt`, `environment`, `producer`, `dedupKey`, `fullName` (string\|null), `confirmedTotal` (int\|null), `confirmedTodayIstanbul` (int\|null), `countsSnapshotAt` (ISO UTC seconds\|null). Unknown keys rejected. Counts are **export-time** Europe/Istanbul day-bound snapshots; envelope frozen after inbox handoff; count-query failure exports nulls (never invented zero).

## Rollout order

1. Dual-read slack_biz relay (v1+v2) — must be live before any v2 producer  
2. Gateway migrate V5 + API with `full-name-required=false`, `contract-version=1`  
3. Flip gateway `contract-version=2` (producer activation switch)  
4. Hostinger marketing upload (required Ad soyad) — separate from app web/admin  
5. Flip `full-name-required=true`  
6. App web/admin pin (name column) if not already shipped with gateway/web bundle  

Queued historical v1 envelopes remain valid under dual-read. Rollback: `full-name-required=false`; marketing rollback; `contract-version=1`; relay still accepts v1; leave nullable `full_name`.

## Alert presentation

Turkish AM titles distinguish warning / critical / mixed (firing+resolved) / fully resolved. Mixed groups are never labelled entirely resolved. Expressions, thresholds, routing, grouping, inhibition, repeat intervals, and webhook separation unchanged. Runbook links are absolute GitHub URLs.

## Tests / evidence

| Suite | Result |
| --- | --- |
| `run_waitlist_acceptance.py` | PASS 23 (W20–W23 v2/adversarial/unknown-field) |
| Gateway WaitlistFullName + WaitlistCsv + OutboxTest | PASS (local); Backend CI green |
| PostgresIT Flyway V5 | PASS (Backend integration CI) |
| Marketing Playwright | PASS (Frontend CI; fills `#waitlist-full-name`) |
| Admin missing-name vitest | present (`AdminWaitlistPage.test.tsx`) |
| Prometheus unit tests | PASS (Observability CI; absolute runbooks) |
| Previews | `waitlist_*.txt`, `alertmanager-preview-{warning,critical,grouped,mixed,resolved}.txt`, `form-before-after.md` |

Evidence dir: `agent-tools/parkio-waitlist-name-slack-readable-01/`.

## CI status (final tip `05041de8`)

**Green (applicable):** Backend CI · Backend integration (PostgresIT V5) · Frontend CI · Observability · slack_biz acceptance · Security (CodeQL + Trivy container scans incl. gateway) · Performance k6 · Mobile-v2 · Backup restore · Staging safety.

**Runtime validation:** one failure from Maven Central HTTP 429 building unrelated `ai-validation-service`; re-run in progress (not a waitlist defect). Dedicated Integration + Backend CI already green.

**Advisory:** Legacy mobile CI fails (pre-existing advisory lane).

**Image digests:** scans green on tip; record publish digests only at authorized deploy.

## Remaining blockers before merge/deploy authorization

- Confirm Runtime re-run green (or explicitly waive Maven 429 infra flake)
- Explicit operator approval for merge + ordered rollout
- No production Flyway/migrate/deploy/restart/secrets/Slack/NR in this step
- Candidate image digests recorded only when images are published
