# V2 waitlist recovery strategy (bounded, non-destructive)

## Hard limit
After Flyway `V2__waitlist_double_opt_in` is applied to production `parkio_gateway`,
rolling back to the **pre-V2** gateway image digest currently pinned in
`docker/docker-compose.gmp-release-pins.yml` is **not** a safe waitlist recovery.
Retaining that digest alone is insufficient. Do not down-migrate.

## Disable public waitlist writes / form (fast containment)
Technical options (choose explicitly at incident time; none executed here):

1. **Static form off (Hostinger):** upload a marketing tree with waitlist UI hidden or
   `parkio-waitlist-mode` set so the browser does not POST (or remove `#waitlist` CTA).
2. **API deny:** remove/disable public POST allowlist for `/api/v1/waitlist*` on a
   V2-capable hotfix gateway, or remove `https://parkio.dev` from
   `PARKIO_CORS_ALLOWED_ORIGINS` so browsers cannot call the API.
3. **Provider halt:** set email provider to a fail-closed configuration only if it
   does not silently accept logging as “delivery”; prefer API/form disable first.

## V2-compatible recovery artifact strategy
1. **Before** production migration: build, scan, and publish a gateway image from
   the approved waitlist SHA (PR #58 head or merge commit).
2. Record the image digest and update **only** `gateway-service` in
   `docker/docker-compose.gmp-release-pins.yml` (preserve media/parking pins).
3. Keep that digest (and/or a second known-good V2-capable digest) as the
   **recovery pin**. Rollback means redeploy a V2-aware gateway digest, not the
   pre-V2 pin.
4. Isolated verification still required before first production use:
   - Flyway V1→V2 on a disposable copy of gateway schema (incl. synthetic V1 rows)
   - HTTP waitlist lifecycle against that DB + Redis + email mock/provider staging
   - Confirm logging provider cannot be silently enabled
     (`PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false`)

## Subscriber preservation
- No TRUNCATE/DELETE of `waitlist_interest` as routine recovery
- No Flyway down-migration
- Withdrawal remains the user-facing deletion path; operator export remains ADMIN-only

## Unverified prerequisites (explicit)
- **No V2-compatible recovery artifact has been built, scanned, published, or
  acceptance-tested in W01A–W01D.** This remains a **deployment prerequisite**.
- Production backup/restore of `parkio_gateway` including waitlist tables after V2
  is **NOT_EXECUTED** here and should be proven in an authorized isolated restore
  drill before cutover.
