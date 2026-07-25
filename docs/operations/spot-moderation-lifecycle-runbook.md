# Spot Moderation Lifecycle Runbook

How a parking spot travels from submission to visibility, what can go wrong, and how to
validate the pipeline on hosted beta.

## The rule this design exists to enforce

**A spot's advertised visibility window is never consumed while it waits on moderation.**

Previously `expires_at` was stamped at creation (`created_at + 10m`) and never recomputed,
so the moderation pipeline spent the user's visibility: a spot approved at T+9m was visible
for one minute, and a spot approved after T+10m was published already expired. Worse, the
lazy-expiry path ran on ordinary reads, so an owner simply opening their own pending spot
expired it — after which the arriving verdict was silently discarded, because the spot was
no longer in a pending status.

**Second rule: never publish an availability report that is already too old to trust.**

Even a correct late approval must not grant a fresh TTL for a stale observation. Approvals
past `max-publishable-age` (default 30 minutes from `created_at`) become `REVIEW_FAILED`
with reason `STALE_BEFORE_PUBLICATION`.

## Timeout policy (defaults)

| Window | Env | Default | Purpose |
|---|---|---|---|
| Active / advertised TTL | `PARKIO_SPOT_ACTIVE_DURATION` | `10m` | User-visible lifetime after publication |
| AI validation timeout | `PARKIO_MODERATION_VALIDATION_TIMEOUT` | `2m` | First AI gate budget |
| AI retry backoff | `PARKIO_MODERATION_VALIDATION_RETRY_BACKOFF` | `1m` | Added per retry attempt × attempt number |
| Max AI attempts | `PARKIO_MODERATION_MAX_VALIDATION_ATTEMPTS` | `3` | Then `REVIEW_FAILED` |
| Human review timeout | `PARKIO_MODERATION_REVIEW_TIMEOUT` | `15m` | After AI → `PENDING_REVIEW` |
| Max publishable age | `PARKIO_MODERATION_MAX_PUBLISHABLE_AGE` | `30m` | Hard ceiling from submission |

Worst-case AI path under defaults: `2m + (1+2+3)m ≈ 8m`, well under the 30m publishable
ceiling. Human review has 15m after uncertain AI, still inside that ceiling when the AI
path is healthy.

These windows are **not** shared with other moderation domains (user reports, media, etc.).

## States

```
                          ┌──────────────► REJECTED (terminal)
                          │
PENDING_VALIDATION ───────┼──────────────► ACTIVE ──► VERIFIED / SUSPICIOUS
   │  (AI gate)           │                  ▲          │
   │                      │   TTL starts ────┘          ▼
   │                      │   here, once,            FILLED / EXPIRED (terminal)
   │                      │   only if still fresh
   ▼                      │
PENDING_REVIEW ───────────┘
   │  (human)
   ▼
REVIEW_FAILED (terminal)
  reasons: REVIEW_TIMEOUT | RETRIES_EXHAUSTED | STALE_BEFORE_PUBLICATION
```

- `activated_at` is the publication instant and the TTL start. It is `NULL` while pending
  and is written **exactly once**; that is what makes duplicate approvals idempotent.
- `expires_at` is **NULL while pending** (column is nullable after V16). It is set only at
  publication. Clients must never render a countdown for pending / null expiry.
- AI approval and human moderator approval share the same `publishFromPending` domain path
  — one TTL calculation, one freshness check.
- `REVIEW_FAILED` is the explicit alternative to "pending forever" or "publish stale".
  It is a platform failure, not a contributor penalty — no points are deducted and the
  owner is invited to resubmit.

## Guarantees and where they are enforced

| Guarantee | Enforced by |
|---|---|
| Pending spots never expire | `ParkingSpot.markExpired` / `isTimeExpired` refuse pending / null `expiresAt`; expiry batch query filters to `ACTIVE/VERIFIED/SUSPICIOUS`; `expireIfElapsed` skips pending |
| TTL starts exactly once | `ParkingSpot.startLifetime` returns early when `activatedAt != null` |
| Delayed but still-fresh approval grants the full window | `startLifetime` computes `now + activeDuration` when still publishable |
| Approval past max age never publishes | `publishFromPending` → `REVIEW_FAILED` / `STALE_BEFORE_PUBLICATION` |
| Duplicate events change nothing twice | consumer inbox dedupes by `eventId`; domain transitions only fire from pending statuses |
| Stale events cannot overwrite newer state | `isStaleModerationEvent` compares the event's `occurredAt` against `moderation_decided_at` |
| Rejected/expired/failed never become visible | those statuses are terminal; every publish path guards on `status.isPendingModeration()` |
| Moderation never hangs forever | `ModerationTimeoutJob` → bounded retries → `REVIEW_FAILED` |
| Event-driven architecture preserved | retries and failures are published through the outbox onto `parkio.parking.spot`; no service is called directly |

## V16 migration safety

V16 is additive (new columns + nullable `expires_at` + indexes). It does **not**
auto-rescue historical `EXPIRED` rows that transitioned directly from pending — that
mutation is too easy to get wrong against incomplete history and would risk republishing
stale availability.

**Before deploy**, run the read-only diagnostic:

[`sql/v16-moderation-lifecycle-predeploy-diagnostic.sql`](sql/v16-moderation-lifecycle-predeploy-diagnostic.sql)

**After review**, if fresh incorrectly-expired rows still exist within max-publishable-age,
apply the separately reviewed remediation:

[`sql/v16-moderation-lifecycle-remediation.sql`](sql/v16-moderation-lifecycle-remediation.sql)

## Metrics

All on parking-service `/actuator/prometheus`.

| Metric | Meaning | Alert |
|---|---|---|
| `parkio_parking_expired_before_approved_count` | live invariant violations | **any non-zero → page** |
| `parkio_parking_expired_before_approved_total` | stored rows expired without publication | **any non-zero → page** |
| `parkio_parking_moderation_pending_oldest_seconds` | longest-waiting submission | `> review-timeout` |
| `parkio_parking_moderation_pending_count` | backlog depth | sustained growth |
| `parkio_parking_moderation_queue_latency_seconds` | submission → verdict, tagged by outcome | p95 regression |
| `parkio_parking_moderation_processing_duration_seconds` | verdict handling cost, tagged by outcome | p95 regression |
| `parkio_parking_moderation_retry_count` | bounded AI-gate retries, tagged by attempt | sustained non-zero |
| `parkio_parking_moderation_timeout_count` | deadlines breached, tagged by status | sustained non-zero |
| `parkio_parking_moderation_failed_count` | spots moved to `REVIEW_FAILED`, tagged by reason | any sustained rate |
| `parkio_parking_consumer_dlq_count` | records dead-lettered to `parkio.dlt.parking` | any non-zero |

A rising DLQ count is the leading indicator: dead-lettered verdicts leave spots pending,
which the timeout job then picks up as retries and eventually failures.

## Structured logs

Every transition logs one line from `ParkingApplicationService` carrying `spotId`,
`moderationRequestId` (the upstream event id), the `from->to` transition, `reason`,
`attempt`, `queueLatencyMs` and `processingMs`. No secrets or user content are logged.

```bash
# Trace one spot end to end
docker compose logs parking-service | grep '<spot-uuid>'

# Everything that failed review in the last deploy
docker compose logs parking-service | grep 'Moderation failed terminally'

# The invariant alarm — this should return nothing, ever
docker compose logs parking-service | grep 'Invariant violated'
```

## Hosted-beta validation

Use a disposable account. `<API>` is `https://<PARKIO_DOMAIN>`, `$TOKEN` a bearer token
for that account.

1. **Stall moderation.** Stop the AI consumer so no verdict can arrive:

   ```bash
   docker compose stop ai-validation-service
   ```

2. **Create a spot** (upload media first, then):

   ```bash
   curl -fsS -X POST "$API/api/v1/parking/spots" \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"mediaId":"<media-uuid>","latitude":41.0082,"longitude":28.9784,
          "suitableVehicleTypes":["SEDAN"],"parkingContext":"STREET_PARKING",
          "legalStatus":"LEGAL"}' | jq '{id, status, expiresAt}'
   ```

   Expect `status: "PENDING_VALIDATION"` and `expiresAt: null`.

3. **Wait past the old 10-minute window (but under 30m), then confirm it did not expire.**
   Poll the owner view — this is the exact path that used to expire the spot:

   ```bash
   curl -fsS "$API/api/v1/parking/my-spots/<spot-id>" \
     -H "Authorization: Bearer $TOKEN" | jq '{status, expiresAt}'
   ```

   Expect `status` still `PENDING_VALIDATION`, `expiresAt` still `null`, and
   `parkio_parking_expired_before_approved_count` still `0`.

4. **Approve it** and confirm the TTL starts only now:

   ```bash
   docker compose start ai-validation-service
   # …or, for the human path, resolve the spot's moderation case with APPROVE.
   curl -fsS "$API/api/v1/parking/my-spots/<spot-id>" \
     -H "Authorization: Bearer $TOKEN" | jq '{status, expiresAt}'
   ```

   Expect `status: "ACTIVE"` and `expiresAt ≈ now + PARKIO_SPOT_ACTIVE_DURATION` — a full
   window, measured from approval, for approvals still within max-publishable-age.

5. **Timeout and retry.** On a scratch deployment, set
   `PARKIO_MODERATION_VALIDATION_TIMEOUT=1m`, `PARKIO_MODERATION_MAX_VALIDATION_ATTEMPTS=2`
   and `PARKIO_MODERATION_REVIEW_TIMEOUT=2m`, keep `ai-validation-service` stopped, create a
   spot and watch:

   ```bash
   docker compose logs -f parking-service | grep -E 'Moderation retry|Moderation failed'
   ```

   Expect two retry lines, then one terminal failure, then `status: "REVIEW_FAILED"`.
   Confirm the apps show "Review failed" rather than an indefinite "Under review".

6. **Cleanup.** Delete the disposable account's spots, restore the moderation env values,
   redeploy, and confirm `parkio_parking_moderation_pending_oldest_seconds` returns to
   normal.

## Recovering a stalled pipeline

1. Check `parkio_parking_consumer_dlq_count` and redrive per
   [`dlq-redrive-runbook.md`](dlq-redrive-runbook.md).
2. Confirm `ai-validation-service` is healthy and its outbox relay is publishing
   (`parkio_outbox_publish_success`, `parkio_outbox_deadlettered`).
3. The timeout job will retry overdue spots on its own; nothing needs to be re-driven by
   hand to prevent indefinite pending.
4. Spots already in `REVIEW_FAILED` are terminal by design. They are not resurrected
   automatically — the owner resubmits, which is deliberate: republishing a spot whose
   physical availability is stale would be wrong.
