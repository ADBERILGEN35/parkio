# Landing Page Copy for parkio.dev

Use this as source copy for the future `parkio.dev` landing page. Do not imply the site or beta is live until deployment evidence exists.

## Hero

Headline:
Parkio

Subheadline:
Community-powered parking intelligence for real-world availability.

Body:
Find, share, verify, and manage parking spots with photo-backed community signals, trust-aware workflows, and mobile-first tools.

Primary CTA:
Join beta waitlist

Secondary CTAs:

- Follow development
- View technical documentation
- Contact founder

Availability note:
Parkio is in release-candidate / hosted-beta preparation. The public beta is not live yet.

## Problem

Finding a route is easy. Knowing whether a nearby parking spot is actually available, legal, recent, and trustworthy is still hard.

## How It Works

1. Share a spot with a photo, location, vehicle fit, and parking context.
2. Discover nearby spots on web or mobile.
3. Verify availability, claim a spot after parking, or report a problem.
4. Build community trust through useful contributions.

## Features

- Photo-backed spot sharing.
- Nearby search and map discovery.
- Vehicle fit and parking context.
- Verification, claiming, and reports.
- Points, levels, and trust signals.
- Moderation and advisory AI validation.
- Notifications and Smart Return assistance.
- Web and mobile clients.

## Trust and Privacy

- Web refresh tokens use HttpOnly cookies.
- Mobile sessions use secure native storage.
- Spot photos use short-lived signed URLs.
- Original image EXIF/GPS/device metadata is stripped before storage.
- Precise user location history is treated as sensitive.
- AI validation supports moderation; it does not make final legal or safety decisions.

## Technology Credibility

Parkio is not a landing-page-only prototype. The repository includes Java 21/Spring Boot microservices, PostgreSQL/PostGIS, Kafka, Redis, S3-compatible media storage, ClamAV scanning, React web, Expo mobile, observability, CI, security scanning, release notes, runbooks, and certification reports.

Current stage: `v1.0.0-rc1` application release candidate for hosted beta.

## Hosted Beta

The application layer is certified for hosted beta, but the live hosted beta is not yet deployed. Remaining work: VPS, DNS, hosted-beta secrets, preflight, deploy, HTTPS smoke, backup/restore, observability, and mobile device smoke.

## FAQ

### Is Parkio live?

Not yet. Parkio is in release-candidate / hosted-beta preparation.

### Is Parkio public-production ready?

No. Public production requires managed data services, secrets management, CD with rollback, on-call, and production-scale validation.

### Can I join the beta?

Beta access is TBD. Use "Join beta waitlist" only after a real intake path exists.

### Does Parkio guarantee a parking spot?

No. Parkio improves parking intelligence through community signals, but it does not guarantee public spot availability.

## Footer

Parkio - community-powered parking intelligence.

Domain: `parkio.dev`.

Links:

- Technical documentation
- GitHub repo
- Contact
- Privacy
- Terms

Footer note:
Parkio is not yet publicly launched.
