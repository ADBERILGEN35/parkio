# PRIV-001 — Account and user-data erasure

Operational privacy workflow for authenticated self-service account deletion.
This document describes **implemented behavior**. It is not a GDPR/KVKK legal
opinion.

Feature flag (default **off**): `parkio.privacy.account-erasure.enabled`
(`PARKIO_ACCOUNT_ERASURE_ENABLED`). Flag off rejects **new** requests with
`ACCOUNT_ERASURE_DISABLED`. In-flight erasures continue via Kafka retries.
Completed erasures are never reversed.

## Lifecycle

`ACTIVE` → `ERASURE_IN_PROGRESS` (login blocked, refresh revoked, session epoch
bumped) → `ERASED` (email replaced with `erased-{uuid}@invalid.localhost`,
password randomized) after **all** participant acks.

User-visible statuses: `IN_PROGRESS`, `COMPLETE`, `FAILED_RETRYING`.

Coordinator: **auth-service**. It does not write other service databases.

## API

- `DELETE /api/v1/account` — JWT + password confirmation; user id from token only
- `GET /api/v1/account/deletion-status`
- Gateway route `account-erasure` (`/api/v1/account/**`) is authenticated
- Internal: `POST /internal/erasure/acks`, `POST /internal/erasure/replay`
  (`X-Gateway-Auth`)

## Durable command

Outbox event `UserErasureRequested` → Kafka `parkio.privacy.erasure` (14d).
Payload: `eventId`, `erasureRequestId`, `authUserId`, `occurredAt` (no email).
Participants ack over HTTP (and optionally Kafka `UserErasureAcknowledged`).
Incomplete work stays `FAILED_RETRYING` / `IN_PROGRESS`. Never mark `COMPLETE`
without every participant `SUCCESS` ack.

Participants: `user`, `parking`, `media`, `moderation`, `gamification`,
`notification`, `analytics`, `ai-validation`.

Idempotency: repeat `DELETE` returns existing status; handlers tombstone-first;
acks upsert by `(erasure_request_id, service_name)`.

## Policy matrix (summary)

| Area | Policy |
|------|--------|
| Auth credentials / refresh / reset tokens | Hard delete / revoke; user row retained as ERASED tombstone without recoverable email |
| User profile, Saved Places, favourites, recents, preferences, vehicle | Hard delete |
| ParkingSession | Hard delete (including active) |
| Community spots | Retain shared fact; `owner_user_id` → sentinel `00000000-0000-4000-8000-000000000001` |
| User-owned media | Delete metadata + object storage |
| Moderation cases | Retain; identities → sentinel |
| Gamification progress | Delete / de-identify |
| Notification tokens/prefs | Hard delete |
| Analytics rows with `user_id` | Delete |
| Ranking evaluations | No user id — retain aggregates |
| Gateway waitlist | Email-only, not `authUserId` — out of this workflow |
| SPA telemetry | Designed without user id / coords / facility ids |
| Backups | Not mutated. 14-day retention. Ledger `erasure-tombstones.json` in stamp |

## Per-service handlers

Each participant inserts `erased_user_tombstones` first, then mutates its own DB,
then `POST /internal/erasure/acks` with `X-Gateway-Auth`. HTTP failure throws so
Kafka retries. Sentinel: `00000000-0000-4000-8000-000000000001`.

| Service | Group | Local action |
|---------|-------|----------------|
| user | `parkio.user.erasure` | Hard-delete profile, places, favourites, recents, prefs, vehicle, trust projection |
| parking | `parkio.parking.erasure` | Hard-delete sessions, search/view logs, verifications, idempotency rows; spots retained with sentinel owner and `media_id` null; trust/fraud/reward subject ids anonymized |
| media | `parkio.media.erasure` | Delete object then soft-delete metadata for `owner_user_id` |
| moderation | `parkio.moderation.erasure` | Retain cases/reports/appeals/violations; rewrite reporter/owner/target-USER ids to sentinel. Staff `moderator_id` on decisions left as audit |
| gamification | `parkio.gamification.erasure` | Delete progress/trust/contribution snapshots; anonymize `point_transactions` |
| notification | `parkio.notification.erasure` | Delete tokens, prefs, notifications, delivery attempts |
| analytics | `parkio.analytics.erasure` | Delete `analytics_events` and `user_analytics_snapshots`; daily/parking aggregates untouched |
| ai-validation | `parkio.ai-validation.erasure` | `requested_by_user_id` → sentinel |

Left unchanged (no account user id): ranking evaluation tables, municipal operator VARCHAR, analytics daily/parking snapshots, AI findings without requester id.

## Resurrection prevention

Tombstones store **only** `auth_user_id` + `erased_at`. Retention must exceed
backup retention (14d) so a restored dump can be locked again.

`scripts/backup-databases.sh` exports `erasure-tombstones.json`.
`scripts/lib/erasure-tombstones.sh` `parkio_replay_erasure_tombstones` applies
the ledger onto a restore target and sets matching `ACTIVE` auth users to
`ERASURE_IN_PROGRESS`. Operators then rely on auth `POST /internal/erasure/replay`
(or Kafka republish) to finish downstream erase.

Same email may register **after** `ERASED` as a **new UUID**. Tombstones prevent
`UserRegistered` from recreating the old profile.

## Observability

Metrics (no user-id labels): `parkio.erasure.requested`, `.completed`,
`.failed`, timer `.duration`, gauge `.stuck` (1h SLA). Alert `AccountErasureStuck`.

Operational SLA: complete within **1 hour** under normal load. Not a legal SLA.

## Non-goals

Physical mobile acceptance, production deploy, PP-01, mutating backup files,
blind cross-DB SQL from one service, deleting shared parking facts, claiming
regulatory compliance by documentation alone.
