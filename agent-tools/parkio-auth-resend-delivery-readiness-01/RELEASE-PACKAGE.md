# AUTH RESEND DELIVERY READINESS — RELEASE PACKAGE

**Status:** Preparation complete. Awaiting release decision.  
**No merge, production deploy, provider switch, DNS mutation, real email send, production account creation, or application restart was performed.**

Branch: `prep/auth-resend-delivery-readiness`  
Base: freshly fetched `origin/api` @ `b29de145567bf6672fa62e9ac155d00a62130184` (includes PR #76/#77 pins)  
Worktree: `parkio-wt-auth-resend-ready`  
Scope: **auth-service only** (no frontend, gateway, Slack, compose pins, shared CI, or New Relic changes)

---

## 0. Separation of concerns

| Track | Status |
|---|---|
| PR #68 branded TR/EN templates + V23 | **Deployed** (do not recreate) |
| PR #76/#77 pending-profile phone fix | **Deployed** (preserve; out of scope) |
| PR #74 waitlist Slack | Owned by Claude; untouched |
| Gateway waitlist Resend | Distinct (`PARKIO_WAITLIST_*`); **not** used as auth proof |
| This package | Reversible prep for later **auth** verification → Resend |

---

## 1. Current production state (read-only 2026-09-22)

Evidence: `prod-readonly-probe-v3.txt`, `prod-readonly-probe-db-v2.txt`

| Item | Observed |
|---|---|
| Auth image | `ghcr.io/adberilgen35/parkio/auth-service@sha256:c9875b416b9a9db9d597fa2e81ab9b5cc9858738b7bea7175c5fac5f00606ac1` |
| Auth status | running |
| `PARKIO_EMAIL_PROVIDER` | **`logging`** |
| `PARKIO_EMAIL_VERIFICATION_LOG_TOKEN` | `false` |
| `PARKIO_PASSWORD_RESET_LOG_TOKEN` | `false` |
| `PARKIO_EMAIL_FROM` | `Parkio <verify@parkio.dev>` |
| `PARKIO_EMAIL_REPLY_TO` | `support@parkio.dev` |
| Verification / reset URLs | `https://app.parkio.dev/verify-email` / `…/reset-password` |
| `PARKIO_RESEND_API_KEY` | **SET** (length observed only; value not printed) |
| Registration | **CLOSED** (`POST /api/v1/auth/register` → `403 REGISTRATION_CLOSED`) |
| Flyway 22 / 23 | both **success** (`preferred_locale` present, default `'tr'`) |
| Pending unverified accounts | **0** |
| `PARKIO_REGISTRATION_*` / `SPRING_PROFILES_*` / `PARKIO_ENVIRONMENT` | **not set** in auth container (defaults: CLOSED, invite-creation disabled) |
| New Relic continuous | `parkio-nr-log-continuous-fluent-bit-nr-pilot-1` + budget-gate **healthy** |

### Access limitations (honest)

- SSH to `civo@api.parkio.dev` works for docker inspect / exec / public HTTP probes.
- Auth DB user is `parkio_auth` (not `parkio`); early probes failed until that was corrected.
- Resend dashboard domain status, API key permissions, and inbox delivery were **not** inspected (no Resend console access in this task; no real send authorized).
- Do **not** assume gateway waitlist Resend config proves auth sender/domain readiness. Auth uses `PARKIO_EMAIL_*` / `verify@parkio.dev`; waitlist uses `PARKIO_WAITLIST_*` / typically `info@parkio.dev`.

---

## 2. Source changes in this prep (narrow)

| File | Change |
|---|---|
| `ResendEmailSender.java` | Resend `Idempotency-Key` (`auth/<template>/<emailHash>/<tokenFingerprint>`, ≤256 chars, 24h provider retention); HTTP status class in safe failure messages (`auth` / `rate_limited` / `provider_5xx` / `client_4xx`); no raw token/API key in logs. **Same sender serves verification and password-reset.** |
| `ResendEmailSenderTest.java` | Coverage for Idempotency-Key, 401, 403, 429, 5xx, mock timeout/transport failure, no secret leakage |
| `ResendEmailSenderBoundedTimeoutTest.java` | Finite default connect/read timeouts; **real** hanging-socket read-timeout bound (distinct from mock `SocketTimeoutException`) |
| `AuthController.java` | Public `resend-verification` / `forgot-password` catch `EmailDeliveryException` **after** service exit (TX already rolled back) → always `202` / `200` |
| `GlobalExceptionHandler.java` | Map uncaught `EmailDeliveryException` → `503 EMAIL_DELIVERY_UNAVAILABLE` for register / admin (not public enumeration paths) |
| `PublicEmailDeliveryEnumerationHttpTest.java` | Endpoint-level proof: eligible / unknown / ineligible share identical public responses under provider failure |
| `EmailDeliveryTransactionalPostgresIT.java` | Real Spring `@Transactional` + PostgreSQL rollback evidence (not Mockito-only) |
| `services/auth-service/README.md` | Correct idempotency scope; document public enumeration + rollback semantics for verification **and** password-reset |

**Preserved:** PT24H verification TTL, PT1H reset TTL, single-use tokens, resend cooldown, stored `preferred_locale`, enumeration-safe public resend/forgot-password, PRIV-001A synthetic skip, fail-closed config validation, no delivery webhooks, no unbounded retries, no email-outbox redesign.

### Failed / ambiguous send → account & token state

| Outcome | State after request |
|---|---|
| Provider 4xx/5xx or transport error | `EmailDeliveryException`; `@Transactional` **rolls back** — register: no account, no verification hash, no `UserRegistered` outbox row; resend: previous verification hash kept; forgot-password: prior active reset token kept (consume+new insert rolled back) |
| Provider HTTP 2xx | Token hash (and pending account on register) **committed**. This is **API acceptance**, not inbox proof |
| Timeout after provider may have accepted | Transaction **rolls back**. Any delivered message carries a rolled-back token and cannot verify; treat as failed send |

**Idempotency (corrected):** Key deduplicates retries of the **same** template + recipient + raw token only. A new registration/resend that mints another token uses a **new** key.

**Password-reset impact:** `ResendEmailSender` implements both verification and reset ports. Public forgot-password stays enumeration-safe under provider failure; admin/register still surface `503`. Rollback semantics above apply to reset tokens as proven in Postgres IT.

Auth does **not** auto-retry.

---

## 3. Isolated acceptance (no real email / no prod accounts)

### Unit / HTTP (H2 SpringBootTest where applicable)

```
ResendEmailSenderTest                         14 / 14 PASS
ResendEmailSenderBoundedTimeoutTest            2 / 2 PASS
EmailDeliveryConfigTest                       10 / 10 PASS
AuthTransactionalEmailTemplatesTest            5 / 5 PASS
LoggingEmailVerificationSenderTest             1 / 1 PASS
AuthApplicationServiceTest                    51 / 51 PASS
AuthLoginMetricsTest                           6 / 6 PASS
PublicEmailDeliveryEnumerationHttpTest         3 / 3 PASS
```

### Transaction evidence (PostgreSQL Testcontainers — `integrationTest`)

```
EmailDeliveryTransactionalPostgresIT           4 / 4 PASS
```

Proven against real DB + Spring TX boundary:

- failed registration → no `auth_users` row, no `UserRegistered` outbox payload
- failed resend → previous verification hash preserved; attempted new token cannot verify
- failed password-reset send → prior active reset token preserved (single active row)
- ambiguous send-failure after attempted new token → rolled-back token not usable

Covered behaviours (synthetic / mock provider):

- register → payload creation (TR/EN HTML+text) → verify; resend uses stored locale; unsupported locale → TR
- expired / invalid / reused tokens; CLOSED registration gate
- provider rejection (401/403/429/5xx), mock transport failure **and** bounded real read-timeout
- public enumeration uniformity under delivery failure; register still `503`
- metrics; no token/API key in logs; PRIV-001A synthetic skip

**Not claimed:** Resend provider acceptance in production, or inbox delivery.

---

## 4. Real-delivery acceptance procedure (later, authorized operator)

### Three distinct proofs

1. **Mock acceptance** — unit/integration above (done in this prep).
2. **Provider acceptance** — Resend HTTP 2xx + non-sensitive auth log `Transactional email accepted; provider=resend…` + `email_sent` metric (after switch).
3. **Inbox proof** — human confirms TR and EN messages arrive with correct branding, plain text, and working verification links.

### Supported path with registration CLOSED?

**Blocker:** Public registration is CLOSED, invite creation is disabled (env unset → false), and there are **0** pending unverified accounts. Therefore:

- Open `POST /api/v1/auth/register` cannot mint a real recipient.
- Admin resend requires an existing pending user (none present).
- Internal invite mint (`POST /internal/auth/registration-invites`) requires `PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true` **and** a configured operator token; registration mode must be **`INVITE`** (not CLOSED) for the invite to be consumable.
- Manual token minting, DB row edits, role grants, and new privileged endpoints are **out of policy**.

**Smallest separate decision needed (one of):**

1. **Brief INVITE window** for a single operator-controlled inbox proof: enable invite creation + set `PARKIO_REGISTRATION_MODE=invite` long enough to register one TR and one EN mailbox (or one account then locale-specific flows if allowed), then return to CLOSED and disable invite creation; **or**
2. **Pre-seed one pending test account** via a separately approved operator process that already exists outside this prep (if/when available) and use **admin resend** after provider switch.

Until that decision, inbox proof cannot be completed while keeping CLOSED with no pending users.

### When a supported account path exists — checklist

1. Confirm `parkio.dev` (or the exact FROM domain) is **verified** in Resend for auth sends from `verify@parkio.dev`.
2. Keep public registration CLOSED unless the INVITE decision above is explicitly approved.
3. Switch provider (see §6) on the chosen auth image.
4. Trigger verification send via the approved path only.
5. Confirm inbox: subject/branding, HTML + plain text, link host `app.parkio.dev`, `lang=tr|en`, waitlist distinction copy present.
6. Complete verify-email; confirm account activates; confirm reuse/expired behaviour still safe.
7. Trigger resend; confirm stored locale; confirm cooldown.
8. Confirm `POST /api/v1/auth/register` without invite still returns `REGISTRATION_CLOSED` when mode is CLOSED.

---

## 5. Configuration delta (describe only — do not apply)

### Already present in prod (no secret install required if key remains valid)

| Key | Current | Required for Resend |
|---|---|---|
| `PARKIO_RESEND_API_KEY` | SET | Keep hidden secret; rotate only if invalid |
| `PARKIO_EMAIL_FROM` | `Parkio <verify@parkio.dev>` | Domain must be verified in Resend |
| `PARKIO_EMAIL_REPLY_TO` | `support@parkio.dev` | Keep |
| Verification/reset HTTPS URLs | set | Keep public https (fail-closed) |
| Log-token flags | `false` | Keep `false` |

### Provider switch (config-only on a given image)

```dotenv
# BEFORE (current)
PARKIO_EMAIL_PROVIDER=logging

# AFTER (authorized switch only)
PARKIO_EMAIL_PROVIDER=resend
```

No other shared Compose/CI/NR file changes are required for the switch itself. Auth-service restart is required for env change (authorized only after release decision).

### Hidden secret installation (if key missing/rotated)

1. Place `PARKIO_RESEND_API_KEY` in the host secret/env store used by auth-service (same mechanism as today).
2. Do **not** commit the value; do not print it in logs or tickets.
3. Confirm length/presence with redacted probe only.
4. Confirm FROM domain verified in Resend dashboard before switch.

### Spring profile note

Prod auth container currently has **no** `SPRING_PROFILES_ACTIVE`. Fail-closed rules for missing API key / FROM / localhost links still apply whenever `provider=resend`. The stronger “prod profile forbids logging” guard applies only if `prod` / `production` / `hosted-beta` Spring profiles are activated later.

---

## 6. Rollout order (when approved)

### Path A — code candidate first (recommended for this prep)

This branch adds Idempotency-Key + safer failure mapping. Prefer deploying the **new auth candidate** while still on `logging`, then switch provider in a second step.

1. Merge + pin auth candidate (separate deploy authorization).
2. Restart auth only; keep `PARKIO_EMAIL_PROVIDER=logging`; keep CLOSED; leave NR continuous running.
3. Read-only checks: digest, health, registration CLOSED, log-token false, V23 intact.
4. **Separate decision:** set `PARKIO_EMAIL_PROVIDER=resend` and restart auth only.
5. Provider-acceptance smoke (non-prod or approved invite path).
6. Inbox proof after the CLOSED-path decision in §4.

### Path B — config-only on deployed PR #68 image

If operators explicitly choose not to take this branch first: switch `PARKIO_EMAIL_PROVIDER=resend` on digest `c9875b41…` is mechanically possible (key/FROM/URLs already set). **Trade-off:** no Idempotency-Key, weaker diagnostics, public enumeration leak under delivery `503`, no password-reset rollback evidence from this prep. Prefer Path A.

### Rollback to logging

1. Set `PARKIO_EMAIL_PROVIDER=logging`.
2. Restart auth only.
3. Outstanding verification **and** password-reset links issued while on Resend remain valid until TTL/single-use rules expire — rollback does **not** invalidate already-committed token hashes.
4. Links that never committed (failed/ambiguous send) were never valid.
5. Do not touch NR continuous, gateway, or web pins during auth email rollback.

---

## 7. Auth candidate identity

| Artifact | Value |
|---|---|
| Branch tip | *(filled after final commit + rebuild)* |
| Local candidate tag | `parkio/auth-service:prep-auth-resend-<shortsha>` |
| Image ID (manifest list) | *(filled after rebuild from tip)* |
| Source equivalence | Prior local image was built from working tree before commit `78e0a938`; docs tip `d21413c9` did not change runtime jars. **Final candidate is rebuilt from the tip that includes enumeration + Postgres IT sources** so image source ≡ PR head. |
| GHCR digest | *not pushed* (local candidate only until release decision) |
| Trivy (no `--ignore-unfixed`) | See §7.1 |

Rebuild is required because runtime code changed. Do not rebuild merely to flip the provider string.

### 7.1 Exact-image Trivy (HIGH,CRITICAL — unfixed included)

*(filled after final scan)*

---

## 8. Frontend dependency (record only — not changed here)

None required for the provider switch: PR #68 web verify-email `lang` handling is already deployed. Pending-profile phone behaviour (PR #76) is unrelated and must remain.

---

## 9. Concrete blockers / decisions requested

1. **Release decision:** merge/deploy Path A auth candidate (still on logging)?  
2. **Provider switch decision:** flip `PARKIO_EMAIL_PROVIDER=logging` → `resend` (separate).  
3. **Inbox-proof path decision:** brief INVITE window **or** other approved pending-account path — required because CLOSED + 0 pending + invite creation disabled blocks real TR/EN inbox proof.  
4. **Domain confirmation:** operator confirms `parkio.dev` (for `verify@parkio.dev`) is verified in the Resend account owning `PARKIO_RESEND_API_KEY` (not assumed from waitlist).

No additional approval gates invented beyond these concrete items.

---

## 10. Terminal CI on final head

*(filled after push of final tip)*
