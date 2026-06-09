# 02 — Domain Rules

Canonical business rules for Parkio. These are the source of truth for enums,
state machines and scoring. Values marked _(tunable)_ are defaults that may be
configured per environment — keep them in config, not hardcoded in `domain` logic.

## Parking spot statuses

State machine for a spot submission:

| Status          | Meaning                                                        |
|-----------------|----------------------------------------------------------------|
| `DRAFT`         | Created locally, not yet submitted.                            |
| `SUBMITTED`     | Uploaded; awaiting AI advisory + moderation.                   |
| `AI_REVIEWED`   | AI advisory attached (still not final).                        |
| `VERIFIED`      | Accepted as a valid, available spot.                           |
| `CLAIMED`       | A user announced intent to take the spot.                      |
| `CLAIM_CONFIRMED` | Claim succeeded (basis for the larger reward).               |
| `EXPIRED`       | Validity window elapsed without claim.                         |
| `REJECTED`      | Failed moderation / invalid.                                   |
| `WITHDRAWN`     | Removed by the contributor.                                    |

Allowed transitions (high level):
`DRAFT → SUBMITTED → AI_REVIEWED → VERIFIED → CLAIMED → CLAIM_CONFIRMED`,
with `SUBMITTED/AI_REVIEWED → REJECTED`, `VERIFIED/CLAIMED → EXPIRED`,
and `* → WITHDRAWN` by owner before claim.

## Parking validity duration

A spot is only useful while likely still empty.

- Each verified spot has a **validity window** (`validUntil = createdAt + TTL`).
- Default TTL _(tunable)_ depends on `parkingContext` (e.g. street spots shorter
  than private-lot spots).
- On expiry the spot moves to `EXPIRED` and is excluded from search results.
- A claim must occur **before** `validUntil`; claims after expiry are rejected.

## Vehicle types (vehicle fit)

A spot declares which vehicle sizes fit. Larger sizes imply smaller ones fit unless
stated otherwise:

`MOTORCYCLE`, `SMALL_CAR`, `SEDAN`, `SUV`, `VAN`, `TRUCK`.

## Parking context

Where the spot is:

`STREET_FREE`, `STREET_PAID`, `PUBLIC_LOT`, `PRIVATE_LOT`, `RESIDENTIAL`,
`DISABLED_RESERVED`, `LOADING_ZONE`, `OTHER`.

## Legal status & violation-risk reasons

**Legal status:** `LEGAL`, `RESTRICTED`, `ILLEGAL`, `UNKNOWN`.

**Violation-risk reasons** (zero or more, advisory flags that raise risk):
`NO_PARKING_SIGN`, `FIRE_HYDRANT`, `DRIVEWAY`, `BUS_STOP`, `CROSSWALK`,
`DISABLED_ONLY`, `LOADING_ONLY`, `TIME_RESTRICTED`, `TOW_AWAY_ZONE`,
`PRIVATE_PROPERTY`.

Spots with `ILLEGAL` status or high-risk reasons should not be promoted as
recommended parking; moderation may reject them.

A community `ILLEGAL_OR_RISKY` verification is an unconfirmed signal: it lowers
confidence and moves the spot to `SUSPICIOUS`, but does not reject the spot or
penalize its owner. `REJECTED` and the associated penalty require an
authoritative moderator action.

## Scoring

All scoring lives in **`gamification-service`**. Other services emit events; they
never compute another service's scores.

### Points _(tunable defaults)_
- Upload submitted: **small** reward (e.g. `+5`).
- Spot verified: **larger** reward (e.g. `+25`).
- Successful claim confirmed (contributor): **largest** reward (e.g. `+40`).
- Rejected/abusive submission: penalty (e.g. `-10`).

### Level
- Derived from cumulative points via thresholds (e.g. L1 0, L2 100, L3 300, …)
  _(tunable)_.
- Higher level unlocks access benefits (priority search, higher daily upload caps).
- Level **never decreases** from point loss below a threshold already reached
  (monotonic), unless a penalty explicitly demotes — see `08`/moderation.

### Trust Score
- Range `0–100`, reflects **long-term reliability**.
- Increases with verified/claimed contributions; decreases with rejections,
  confirmed reports, and penalties.
- Gates sensitive actions (e.g. auto-verify eligibility, reduced moderation).

### Contribution Score
- Reflects **recent** community value over a rolling window (e.g. last 30 days).
- **Decays over time** so inactivity lowers it; used for ranking/leaderboards.

## Ranking criteria

Leaderboards/ranking combine, in priority order:
1. **Contribution Score** (recent value) — primary.
2. **Trust Score** (reliability) — tiebreaker / multiplier.
3. **Level** — secondary tiebreaker.
4. Total verified spots — final tiebreaker.

Penalized/suspended users are excluded from public ranking.

## Moderation & penalties

- Users can **report** spots (wrong location, illegal, fake, occupied, offensive).
- Moderation outcomes: `DISMISS`, `WARN`, `REJECT_SPOT`, `PENALIZE_USER`,
  `SUSPEND_USER`, `BAN_USER`.
- Penalties: point deduction, Trust Score reduction, temporary upload limits,
  suspension, ban. Repeated violations escalate.
- Moderation actions emit events consumed by `gamification`, `user`, and
  `notification` services.

## AI validation = advisor, not decider

- `ai-validation-service` produces an **advisory** result (confidence, detected
  legal-status signals, violation-risk flags, "is this an empty parking spot?").
- AI output **never** auto-rejects or auto-bans on its own. It informs moderation
  and may auto-**suggest** verification only above a high confidence threshold
  combined with sufficient contributor Trust Score _(tunable)_.
- Final decisions belong to **moderation rules / human moderators**, not the model.
