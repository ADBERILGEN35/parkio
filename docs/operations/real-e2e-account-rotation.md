# Real-e2e seeded accounts: verification and rotation / session revocation (owner decision 2026-10-08 item 7)

The runbooks used to document one shared password for the seeded `user@`, `moderator@` and
`admin@real-e2e.parkio.local` accounts, and the seeding and hosted smoke tools carried it as a default.
As of 2026-10-08 the value is removed from current source: `scripts/seed-real-e2e.sh`,
`scripts/smoke-hosted-beta.sh`, `scripts/staging/run-critical-journeys.sh`,
`scripts/prod-muni-01/smoke-municipal.sh` and the k6 scripts take the password only from the
environment (no default), and the browser tests use their own disposable fixture
(`frontend/apps/web/e2e/fixtures/credentials.ts`). This does not remediate an account that was
seeded with the old value on a hosted stack, and it does not erase the repository's history.

## 1. Verification (read-only, operator, on the host)

    cd /opt/parkio   # the compose project directory
    PARKIO_ENV_FILE=docker/.env scripts/ops/verify-real-e2e-accounts.sh -f docker/docker-compose.yml [-f <the host's overlay files>]

The script only `SELECT`s from the auth database through the `postgres-auth` container and prints,
per account on the `real-e2e.parkio.local` domain: e-mail, status, created/updated time, roles and
the number of active refresh tokens. It prints no hash, token or personal data. Exit 0: no such
account; exit 10: at least one exists, then the documented password must be treated as live and
section 2 applies; exit 1: the query itself failed (compose or psql error), nothing can be concluded,
fix the invocation and repeat. Record the output (it contains nothing secret) with the task evidence.

## 2. Rotation and session revocation (needs separate authorization; reviewable before it runs)

For each account the verification lists, in this order, inside one operator session. `<id>` is the
account's `auth_users.id`: `GET /api/v1/admin/users?email=<email>` (ADMIN bearer) returns it, or
`SELECT id FROM auth_users WHERE email = '<email>'` on the host.

1. **Revoke sessions first.** Revoke every active refresh token of the account and bump its session
   epoch, so access tokens already minted are rejected at the gateway within its cache TTL:
   - preferred: `POST /api/v1/admin/users/<id>/revoke-sessions` with the JSON body
     `{"reason": "real-e2e credential rotation <date>"}` (ADMIN bearer; `reason` must not be blank).
     It bumps the epoch, revokes every refresh token of the account (`ADMIN_REVOKED`) and writes an
     audit event; or
   - fallback, on the host, through `postgres-auth` (transaction; take the `verify` output before and after):

         UPDATE refresh_tokens SET revoked = true WHERE user_id = '<id>' AND NOT revoked;
         UPDATE auth_users SET session_epoch = session_epoch + 1, updated_at = now() WHERE id = '<id>';

2. **Remove or rotate the account.** Preferred for `admin@` and `moderator@` (a test account has no
   business being an ADMIN on a hosted stack), acceptable for `user@`: remove the seeded accounts with
   the existing scoped cleanup, on the host, with the seeded `PARKIO_REAL_*_EMAIL` variables exported:
   `PARKIO_ENV_FILE=docker/.env ./scripts/cleanup-real-e2e.sh --accounts --target hosted-beta`. It
   deletes only the seeded auth users and their profiles, looked up by their test e-mails (there is no
   admin delete endpoint, and the self-service `DELETE /api/v1/account` needs the account's own
   password). Otherwise rotate: generate a new password from the secret store
   (`openssl rand -base64 24`), export it as `PARKIO_REAL_USER_PASSWORD` (and the moderator/admin
   variables for those accounts) and run
   `PARKIO_ENV_FILE=docker/.env ./scripts/seed-real-e2e.sh --target hosted-beta --update-passwords`
   on the host. The seed script neither bumps the epoch nor revokes tokens, which is why step 1 comes
   first. Store the new value only in the secret store (and, for the deploy workflow's smoke, in the
   repository secret `HOSTED_BETA_REAL_USER_PASSWORD`); never reuse the old value and never write the
   new one into the repository, a runbook or a chat.
3. **Re-verify.** Run section 1 again: the accounts are gone, or exist with 0 active refresh tokens
   and an `updated_at` after step 1.
4. **Evidence.** Keep both verification outputs and the audit event ids with the task; note the host
   and the time. Nothing in this procedure touches real user accounts (the filter is the test domain).

Rollback: step 1 cannot be undone and does not need to be (the accounts are test accounts; a real
user is never matched). Step 2's deletion is final; a rotation can be repeated.

## 3. What stays open after this

- The repository history still contains the old value (historical scanner findings are kept, not
  excepted, until the owner's confirmation that no hosted account carries it).
- The seeding tool now refuses to run without `PARKIO_REAL_*_PASSWORD`; anyone seeding a hosted stack
  must generate unique passwords and keep them in the secret store.
