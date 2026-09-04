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

## Human inputs required before 01E

```text
FOUNDER_LINKEDIN_URL = HUMAN INPUT REQUIRED
PARKIO_COMPANY_LINKEDIN_URL = OPTIONAL / NOT PROVIDED
```

After the operator supplies the founder's authentic personal URL, validate only that it
uses HTTPS and a `linkedin.com/in/` path. Operator confirmation, not URL syntax or a web
search, establishes ownership. Add the confirmed URL to:

- the visible founder section in `web/marketing/index.html`;
- `founder.sameAs` in the Organization JSON-LD;
- the footer verification link only if it improves navigation.

Until then, render no LinkedIn anchor and omit `sameAs` entirely. Never commit an empty,
fragment, example, or guessed URL.

## CTA state transition owned by 01E

The committed 01D page is deliberately the pre-enable state:

```text
CTA: Open Parkio
Target: https://app.parkio.dev/
Context: Account registration remains controlled.
```

Only after 01E enables and externally verifies the production public explore API and page,
change the marketing CTA state to:

```text
CTA: Explore live Parkio
Target: https://app.parkio.dev/explore
Context: Read-only public explore · no account required.
```

Apply that change consistently to the header, hero, Parkio-today panel, footer, and 404
page. Capture a real production screenshot only after the live public route is verified;
01D intentionally contains no product screenshot.

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

## Local limitation

The deterministic validator checks the required `.htaccess` directives statically. Its
local Node server validates routes, content types, links, assets, crawler equivalence, and
responsive rendering, but it does not emulate Apache module behavior. Apache/Hostinger
header verification remains an 01E post-deploy check.
