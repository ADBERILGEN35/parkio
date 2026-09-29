# Parkio Marketing Source and Hostinger Deployment

## Status

`web/marketing/` is the canonical, directly deployable source for `https://parkio.dev/`.
It is plain static HTML, CSS, and same-origin assets; Hostinger does not need Node or a
build step.

GOOGLE-STARTUP-REAPPLY-01D is source-only. It does not authorize an upload, Hostinger
mutation, DNS change, or public-explore enablement. Those actions belong to an explicitly
authorized 01E run.

## Version-control baseline

The exact pre-01D live package was imported without copy edits in commit:

```text
1edb16b2fcd5a44a3a5a9cf0dadd6466f67ea15c
```

The 01D candidate is the final exact SHA named by the 01D certification report. Do not
deploy an intermediate commit or infer the candidate from a local branch name.

## Operator-confirmed identity inputs (01E-B2-A)

```text
FOUNDER_LINKEDIN_URL = https://www.linkedin.com/in/oguzhan-tasyaran/
PARKIO_COMPANY_LINKEDIN_URL = https://www.linkedin.com/company/parkio-app
```

These URLs are committed in the visible founder/contact/footer surface and in JSON-LD
(`founder.sameAs` for the personal URL; Organization `sameAs` for the company URL). Do not
guess additional social profiles.

## CTA state after public Explore enablement (01E-B2-A)

Public Explore is live. Marketing CTAs must use:

```text
CTA: Explore parking / Park alanı keşfet
Target: https://app.parkio.dev/explore
Context: Account registration remains closed. · no account required for Explore.
Secondary: Sign in → https://app.parkio.dev/
Secondary (registration notify): waitlist section → POST https://api.parkio.dev/api/v1/waitlist
```

Turkish is the default document language for a fresh visit. Persist explicit language choice in
`localStorage` key `parkio.marketing.locale`. Keep Explore as the primary acquisition CTA.
The waitlist collects email only for “notify when registration opens” with double opt-in via
gateway APIs (`/waitlist`, `/waitlist/confirm`, `/waitlist/withdraw`). Do not use create-account
as the primary CTA.

## Manual deployment boundary

When 01E is authorized:

1. Confirm the exact candidate SHA and a clean tracked worktree.
2. Re-run `node scripts/validate-marketing-site.mjs` and the marketing Playwright suite.
3. Compare the current live critical-file hashes with the recorded baseline before upload.
4. Back up the existing `public_html` contents using the operator-controlled Hostinger
   facility and record the restore point outside the public web root.
5. Upload the contents of `web/marketing/` into `public_html`, not the parent directory.
6. Preserve `.htaccess`; do not weaken its security headers.
7. Verify `/`, `/privacy/`, `/terms/`, `/robots.txt`, `/sitemap.xml`, `/404.html`, and all
   referenced assets from an independent browser and crawler-like client.

No credential, FTP secret, resolved environment file, or Hostinger token belongs in Git.

## Rollback

If the 01E verification fails, redeploy the exact `web/marketing/` tree from baseline
commit `1edb16b2fcd5a44a3a5a9cf0dadd6466f67ea15c`. The repository baseline, not the
Downloads ZIP, is the canonical rollback source after 01D.

After rollback, verify the same critical URLs and hashes before declaring recovery.

## Waitlist / gateway publication sequencing (W01B / W01F)

Do not publish a live-looking waitlist form on Hostinger before the gateway waitlist
API and email path are operational.

**Staged Hostinger packaging** (`scripts/package-marketing-waitlist-bundles.sh staged`):
landing meta `parkio-waitlist-mode=unavailable` hides the signup form and never
simulates registration success. Confirmation and withdrawal pages stay `api`.
`?waitlistMock=1` cannot enable fake success when meta is `api` or `unavailable`.

**Launch packaging** (`… launch`): landing meta `api` posts to the real gateway.
Mock mode requires an explicit local `meta content="mock"` and must not ship in
production bundles.

**Logging-only delivery cannot satisfy live waitlist acceptance.** Production must use
`PARKIO_WAITLIST_EMAIL_PROVIDER=resend` with a verified sender domain and
`PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false`. A logging provider (even when
temporarily allowed in local/dev) never proves real email delivery.

(Note: PR #58 release narrative still carried earlier “if not logging-only”
phrasing in agent evidence; that wording is outdated — logging-only is not an
accepted live path.)

### Server-side admissions containment

`parkio.waitlist.admissions-enabled` / `PARKIO_WAITLIST_ADMISSIONS_ENABLED`
defaults to **false**. While disabled, `POST /api/v1/waitlist` and
`POST /api/v1/waitlist/resend` return HTTP 503 `WAITLIST_ADMISSIONS_DISABLED`
before any waitlist DB write or confirmation-email provider call. Direct HTTP
clients cannot bypass this. Confirmation of already-issued tokens and
withdrawal remain available. The property is bound at process start — changing
it requires a **gateway restart** (not hot-switched).

In-flight requests that already passed the admissions check may still complete
DB writes or provider calls after an operator decides to disable admissions;
restart after setting `false` to ensure the process loads the new value.
Withdrawal notice emails may still be attempted for successful withdrawals.

Required order:

1. Source merge of waitlist containment + recovery work into the authorized deploy branch.
2. Build/scan/publish a new **gateway** image; update only the gateway digest in
   `docker/docker-compose.gmp-release-pins.yml` (preserve media and parking pins).
   That digest is a V2-compatible candidate and same-version restart/redeploy
   artifact (and a containment vehicle when admissions are disabled). It is **not**
   independent application rollback. A defective binary requires a newly built
   accepted V2 digest — the pre-V2 pin remains unsafe after migration.
3. Deploy gateway with Flyway V1→V2 on `parkio_gateway`, CORS including
   `https://parkio.dev`, `PARKIO_WAITLIST_EMAIL_PROVIDER=resend`,
   `PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false`, and admissions initially
   **disabled** until cutover.
4. Authorized live email + persistence acceptance with a test address after
   setting `PARKIO_WAITLIST_ADMISSIONS_ENABLED=true` and restarting.
5. Hostinger upload of `web/marketing/` from the exact approved SHA.

Rollback / containment:

- **Containment:** set `PARKIO_WAITLIST_ADMISSIONS_ENABLED=false` and restart gateway
  (and/or hide the Hostinger form). Form hide alone does not stop direct API writes.
- **Same-digest restart/redeploy:** restart or redeploy the accepted V2-compatible
  gateway digest (not the pre-V2 pin). Do not run destructive down-migrations;
  preserve `waitlist_interest` rows.
- **Defective binary:** build/scan/accept a new V2-compatible digest from fixed
  source — do not treat same-digest redeploy as general application rollback.




The deterministic validator checks the required `.htaccess` directives statically. Its
local Node server validates routes, content types, links, assets, crawler equivalence, and
responsive rendering, but it does not emulate Apache module behavior. Apache/Hostinger
header verification remains an 01E post-deploy check.
