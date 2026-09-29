# Waitlist V2 recovery and containment (W01F)

## Hard limits
- After Flyway `V2__waitlist_double_opt_in`, the **pre-V2** gateway digest is not a
  safe waitlist rollback target.
- Do not run destructive down-migrations.
- Logging-only email delivery cannot satisfy live waitlist acceptance.

## Containment (stop new writes / confirmation email)
Set `PARKIO_WAITLIST_ADMISSIONS_ENABLED=false` and **restart** the gateway.
Submit and resend return 503 `WAITLIST_ADMISSIONS_DISABLED` before DB mutation or
provider dispatch. Confirm (existing tokens) and withdraw remain available.

Hiding the Hostinger form or trimming CORS does **not** stop direct API writes.

## Restart recovery
Restart the same V2-compatible gateway artifact (same image digest) after config
or process failure. Covers process crash / bad config reload that requires reboot.
Does **not** cover a bad application build that corrupts waitlist behavior.

## Application recovery (V2-compatible fallback)
Redeploy a previously built, scanned, and acceptance-tested **V2-aware** gateway
image digest. Prefer keeping two known digests of the same schema generation:
the current release pin and a recovery pin. Update only `gateway-service` in
`docker/docker-compose.gmp-release-pins.yml`.

Same binary with different configuration is **containment / config recovery**,
not general application rollback. It covers operator mistakes in admissions or
email env vars; it cannot recover from a defective jar/image.

## Disposable validation expectation
Before production: upgrade V1→V2 on a disposable DB with synthetic PENDING /
CONFIRMED / WITHDRAWN rows; exercise containment enable/disable with restart;
confirm row preservation, Flyway history, health/auth/Explore unaffected, and
withdrawal still works.
