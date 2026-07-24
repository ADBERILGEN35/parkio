# Hostinger Static Landing Deployment

Date: 2026-07-08

## Status

Prepared for Hostinger static deployment. Not deployed from this workstation because Hostinger hPanel and DNS registrar access are operator-owned.

This package is only for the public static landing page:

- `/`
- `/privacy`
- `/terms`
- `/robots.txt`
- `/sitemap.xml`
- `/manifest.webmanifest`
- Open Graph and app icons

It does not deploy backend services, does not claim hosted beta is live, and does not collect real waitlist emails.

## Hostinger Capability Check

Hostinger web hosting is suitable for this static package. Hostinger's public web-hosting page describes web hosting as storage for website files and lists managed hosting, SSL, CDN, backups, file/domain management, and sufficient storage on current web-hosting tiers.

Operator must confirm in hPanel:

- The active plan is the intended Business/Unlimited web-hosting plan.
- `parkio.dev` is attached to the hosting account.
- File Manager or FTP/SFTP upload is available for `public_html`.
- SSL is issued for `parkio.dev` and `www.parkio.dev`.
- DNS is controlled either by Hostinger nameservers or by the current registrar's DNS zone.

References:

- https://www.hostinger.com/web-hosting

## Build

From the repository root:

```bash
cd frontend
VITE_WAITLIST_INTAKE_MODE=disabled corepack pnpm --filter @parkio/web build
```

Output directory:

```text
frontend/apps/web/dist
```

The `VITE_WAITLIST_INTAKE_MODE=disabled` build makes the public landing waitlist panel informational only. It does not render an email input, does not render a submit button, and does not submit waitlist data.

## Static Route Strategy

The web app already code-splits the public landing page from the protected app bundle. For Hostinger static hosting, the public legal routes are also handled without booting the full authenticated application.

Upload strategy:

- Upload the build output to `public_html`.
- Add `privacy/index.html` as a copy of root `index.html`.
- Add `terms/index.html` as a copy of root `index.html`.
- Do not add a broad SPA fallback rewrite.

This keeps `/privacy` and `/terms` available while API-dependent app routes such as `/login`, `/map`, `/spots`, and dashboard routes remain unavailable on static hosting unless an operator intentionally adds a rewrite later.

Note: Vite still emits protected app chunks into `assets/` because the repository is one web app. They are not loaded by `/`, `/privacy`, or `/terms` during static landing verification.

## Prepared Upload Package

Prepared locally:

```text
/tmp/parkio-hostinger-landing-20260708-1600
/tmp/parkio-hostinger-landing-20260708-1600.zip
```

Archive size:

```text
1.6M
```

Upload the archive contents into Hostinger `public_html`, not the archive directory itself.

Top-level package contents:
     
```text
assets/
icons/
index.html
logo.svg
manifest.webmanifest
offline.html
og-parkio.png
og-parkio.svg
privacy/index.html
robots.txt
sitemap.xml
social-preview.png
sw.js
terms/index.html
```

## DNS

Use one of these paths.

### If DNS is managed by Hostinger

Point `parkio.dev` to the Hostinger web-hosting site using hPanel's domain setup flow. Confirm these records after hPanel creates them:

```text
@      A      <Hostinger website IP from hPanel>
www    CNAME  parkio.dev
```

### If DNS stays at the current registrar

Create or update:

```text
@      A      <Hostinger website IP from hPanel>
www    CNAME  parkio.dev
```

Remove conflicting `A`, `AAAA`, or `CNAME` records for `@` and `www`.

Do not point `api.parkio.dev` to Hostinger. Backend/API DNS remains blocked until the VPS is available.

## SSL

After DNS resolves to Hostinger:

- Issue or enable Hostinger SSL for `parkio.dev`.
- Include `www.parkio.dev`.
- Enable HTTP to HTTPS redirect if hPanel offers it.

## Verification Checklist

After upload and DNS propagation:

```bash
curl -I https://parkio.dev/
curl -I https://parkio.dev/privacy/
curl -I https://parkio.dev/terms/
curl -I https://parkio.dev/robots.txt
curl -I https://parkio.dev/sitemap.xml
curl -I https://parkio.dev/manifest.webmanifest
curl -I https://parkio.dev/og-parkio.png
curl -I https://parkio.dev/social-preview.png
```

Browser checks:

- `/` renders "Parking intelligence powered by real drivers."
- Waitlist panel renders "Hosted-beta intake is temporarily paused."
- `/privacy/` renders "Privacy Policy."
- `/terms/` renders "Terms of Service."
- Browser network panel shows no `/api/`, `/waitlist`, `localhost:8080`, or `api.parkio.dev` requests on landing page load.

## Local Verification Results

Commands run:

```bash
corepack pnpm --filter @parkio/web test -- src/pages/LandingPage.test.tsx src/pages/landing/waitlistService.test.ts
corepack pnpm --filter @parkio/web typecheck
VITE_WAITLIST_INTAKE_MODE=disabled corepack pnpm --filter @parkio/web build
corepack pnpm --filter @parkio/web lint
```

Results:

- Targeted tests: 10 passed.
- Typecheck: passed.
- Production build: passed.
- Lint: passed with 5 existing fast-refresh warnings, 0 errors.
- Local package HTTP checks: `200` for `/privacy/`, `/terms/`, `/robots.txt`, `/sitemap.xml`, `/manifest.webmanifest`, `/og-parkio.png`, `/social-preview.png`, and `/icons/parkio-icon.svg`.
- Headless browser check: `/`, `/privacy/`, and `/terms/` rendered; landing page load made 0 backend/API requests and produced 0 page errors.

## Temporary Waitlist Behavior

Waitlist collection is disabled for the Hostinger static package.

The static page:

- Does not collect email addresses.
- Does not store waitlist data.
- Does not submit to the backend waitlist API.
- Explains that waitlist collection will open after hosted-beta backend intake is available.

Real waitlist collection remains blocked until the backend VPS/API path is live and privacy/legal pages are hosted at the same public domain.

## Remaining Blockers

- Hostinger hPanel access.
- DNS registrar or Hostinger DNS access.
- Hostinger website IP or nameserver values from hPanel.
- SSL issuance after DNS points to Hostinger.
- Oracle/VPS capacity for backend and API deployment.
- Real waitlist API deployment before collecting beta emails.
- Final legal review before treating legal pages as production legal advice.

## GO/NO-GO

GO for using `parkio.dev` as an honest static landing page in AWS Activate after:

- Upload package is extracted into `public_html`.
- DNS resolves to Hostinger.
- HTTPS works for `parkio.dev` and `www.parkio.dev`.
- Verification checklist passes.

NO-GO for claiming hosted beta is live.

NO-GO for collecting real waitlist emails from Hostinger static hosting until a safe backend intake is reachable and verified.
