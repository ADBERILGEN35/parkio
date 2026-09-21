# Waitlist V2 recovery and containment (W01F / W01G)

## Hard limits
- After Flyway `V2__waitlist_double_opt_in`, the **pre-V2** gateway digest is not a
  safe waitlist rollback target.
- Do not run destructive down-migrations.
- Logging-only email delivery cannot satisfy live waitlist acceptance.

## Classification of the retained W01F gateway image
The locally built/scanned image from the containment candidate is:

1. A **V2-compatible candidate** (schema generation V2; admissions control included).
2. A **same-version restart / redeployment artifact** (restart or redeploy the same
   digest after process/config failure).
3. A **containment option** when `PARKIO_WAITLIST_ADMISSIONS_ENABLED=false`
   (after restart).

It is **not** an independent application rollback to a different binary lineage.
**Defective-binary limitation:** if this jar/image itself is defective, redeploying
the same digest cannot recover waitlist behavior — a newly built, scanned, and
accepted V2-compatible digest from a fixed source SHA is required. No separate
gateway service is invented for that case.

Keeping a second known-good V2 digest (same schema generation, different accepted
build) is an optional operator preference for faster redeploy of a prior good
build — still same-generation redeploy, not pre-V2 rollback.

## Containment (stop new writes / confirmation email)
Set `PARKIO_WAITLIST_ADMISSIONS_ENABLED=false` and **restart** the gateway.
Submit and resend return 503 `WAITLIST_ADMISSIONS_DISABLED` before DB mutation or
provider dispatch. Confirm (existing tokens) and withdraw remain available.
Health, authentication, and public Explore routes are unaffected by this flag.

Hiding the Hostinger form or trimming CORS does **not** stop direct API writes.

## Restart / same-digest redeploy
Restart or redeploy the **same** V2-compatible gateway digest after config or
process failure. Covers process crash and admissions/email env mistakes once
restarted with corrected config. Does **not** cover a bad application build.

## Disposable validation expectation
Before production: upgrade V1→V2 on a disposable DB with synthetic PENDING /
CONFIRMED / WITHDRAWN rows; exercise containment enable/disable with restart;
confirm row preservation, Flyway history, health/auth/Explore unaffected, and
withdrawal still works.
