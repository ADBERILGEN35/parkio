# Solution

## Core Workflow

Parkio turns a parking observation into a structured, time-sensitive community signal:

1. A driver shares a spot with a photo, location, description, vehicle fit, parking context, and legal-status signals.
2. Other drivers discover nearby spots on web or mobile.
3. The community verifies availability, claims a spot after parking, or reports a problem.
4. Moderation, trust, and advisory AI validation help keep unreliable or risky data from being treated as final truth.

## Spot Sharing

Users submit:

- Photo evidence.
- Latitude and longitude.
- Description.
- Vehicle fit.
- Parking context.
- Legal status and optional risk reasons.

The media pipeline scans uploads with ClamAV before storage, normalizes accepted images, strips original EXIF/GPS/device metadata, and serves images through short-lived signed URLs.

## Verification, Claiming, and Reports

- Verification confirms availability or flags a spot as illegal/risky.
- A community illegal/risky signal makes a spot suspicious and can open moderation review; it does not automatically reject the spot or penalize the contributor.
- Claiming represents a driver successfully taking a spot and creates a strong usefulness signal.
- Reports handle wrong location, illegal, fake, occupied, or offensive spots.
- High-risk writes such as spot creation, claim, and verify use idempotency keys to avoid duplicate actions on retries.

## Trust and Incentives

Parkio tracks points, level, Trust Score, and Contribution Score. The intent is to reward useful behavior, make reliability visible, and support better ranking and moderation decisions as real beta data becomes available.

## Smart Return

Smart Return is opt-in return-trip parking assistance. It supports saved settings, prompts, expected return time, scheduler logic, and notification hooks. It is feature-flagged and should be enabled for beta cohorts only after operator/runtime smoke.

## Notifications

Parkio includes inbox APIs, push delivery paths, Expo push integration, and deep-link metadata. Live push delivery remains operator-dependent because provider credentials and device validation must be configured in the deployed environment.

## Moderation and AI Validation

Moderators handle content outcomes. Admins handle account-level actions such as sanctions, appeals, trust/point overrides, and platform analytics. AI validation is advisory moderation support, not an automatic legal/safety decision-maker.

## Product Surfaces

- Web: React/Vite SPA with map, upload, spot detail, reports, notifications, gamification/impact, moderation, analytics, auth, and route guards.
- Mobile: Expo React Native app sharing API client, DTOs, validation, session logic, map/upload/Smart Return modules, notifications, profile, and secure token storage.

## Hosted Beta Scope

Hosted beta should be a closed/operator-run beta after VPS, DNS, hosted-beta environment, deployment, HTTPS smoke, observability, backup/restore, and mobile device smoke are complete. It is not public production.
