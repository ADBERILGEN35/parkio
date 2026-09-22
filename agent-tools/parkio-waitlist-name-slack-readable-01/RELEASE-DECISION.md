# RELEASE DECISION PACKAGE — Waitlist full name + readable Slack

**PR:** https://github.com/ADBERILGEN35/parkio/pull/84 (draft)  
**Branch:** `feat/waitlist-full-name-readable-slack`  
**Decision head (pre-this-commit):** `d1f8d0120fa0a496f574a86fa7456929af327a63`  
**Base:** `origin/api`  
**Scope:** draft only — no merge, no prod migrate/deploy, no real Slack/email, no secret/NR changes, registration CLOSED, provider=resend.

## Identities

| Item | Value |
| --- | --- |
| Draft PR | https://github.com/ADBERILGEN35/parkio/pull/84 |
| Feature commits | `f1de140f`, `d1f8d012`, + follow-up release-prep commit |
| Marketing deploy path | Hostinger `web/marketing/` → parkio.dev (separate from app web) |
| Admin UI deploy path | GHCR web image pin / app.parkio.dev (separate step) |
| Gateway / Flyway | V4 → additive V5 (`full_name` nullable + indexes) |
| Slack biz webhook | unchanged (`PARKIO_SLACK_BIZ_*`) |
| Alertmanager webhook | unchanged (`PARKIO_ALERT_*`) |

## Configuration delta (safe defaults)

| Knob | Default | Final go-live |
| --- | --- | --- |
| `parkio.waitlist.full-name-required` | `false` (compat) | **`true` after Hostinger marketing upload** |
| `parkio.waitlist.ops-notifications.contract-version` | **`1`** | **`2` after dual-read relay is live** |
| Ops notifications enabled | `false` | unchanged by this PR |

Invalid supplied names are rejected even when the requirement flag is off. Missing name is accepted only while the flag is off.

## Exact v2 allowlist

`contractVersion`, `eventId`, `eventType`, `occurredAt`, `environment`, `producer`, `dedupKey`, `fullName` (string\|null), `confirmedTotal` (int\|null), `confirmedTodayIstanbul` (int\|null), `countsSnapshotAt` (ISO UTC seconds\|null). Unknown keys rejected. Counts are **export-time** Istanbul-day snapshots; frozen after inbox handoff.

## Rollout order

1. Dual-read slack_biz relay (v1+v2)  
2. Gateway migrate V5 + API with `full-name-required=false`, `contract-version=1`  
3. Flip gateway `contract-version=2` (producer activation switch)  
4. Hostinger marketing upload (required Ad soyad)  
5. Flip `full-name-required=true`  
6. App web/admin pin (name column) if not already shipped with gateway/web bundle  

## Rollback

- `full-name-required=false`; marketing rollback; `contract-version=1`; relay still accepts v1; leave nullable `full_name` in place.

## Tests / evidence (local)

| Suite | Result |
| --- | --- |
| `run_waitlist_acceptance.py` | PASS 23 (W20–W23 v2/adversarial/unknown-field) |
| Gateway WaitlistFullName + WaitlistCsv + OutboxTest | PASS |
| Marketing Playwright | fixed to fill `#waitlist-full-name` (verify in CI) |
| Admin missing-name vitest | present (`AdminWaitlistPage.test.tsx`) |
| Prometheus `alerts.test.yml` | absolute runbook URLs aligned |
| Previews | `waitlist_*.txt`, `alertmanager-preview-*.txt` incl. mixed/grouped |

## CI status (record at package time)

Re-check PR checks after push. Prior head had Frontend marketing waitlist + Observability runbook URL mismatches (addressed in this prep). Backend/Security were still running at last poll.

## Remaining blockers before merge/deploy authorization

- Terminal green applicable CI on final PR head  
- Explicit operator approval for merge + ordered rollout  
- No production Flyway/migrate/deploy/restart in this step  
- Image digests / Trivy for whichever images are published later (not in this draft step)
