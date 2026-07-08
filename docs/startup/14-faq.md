# FAQ

## What is Parkio?

Parkio is a community-powered parking intelligence platform that helps drivers find, share, verify, and manage real-world parking availability.

## Is Parkio live?

Not yet. The repository documents `v1.0.0-rc1` as a hosted-beta release candidate, but the RC2 deployment report says the live hosted-beta deployment has not been executed.

## Is Parkio public-production ready?

No. Public production is documented as NO GO until managed HA data services, secrets manager, CD rollback/approval, on-call/alerting process, production-scale load/security testing, and related hardening are complete.

## Does Parkio guarantee a parking spot?

No. Parkio can improve parking intelligence with recent community signals, but it cannot guarantee that a public or shared spot will remain available.

## How does spot verification work?

Users can verify a spot as available or flag it as illegal/risky. A community illegal/risky signal makes the spot suspicious and can open moderation review. It does not automatically reject the spot or penalize the contributor. Authoritative rejection requires moderation.

## Does Parkio track my location?

Parkio stores spot location when a user intentionally submits a spot. Smart Return may require saved home/return settings when enabled. Precise user location history should be treated as sensitive, and the repo's privacy guidance says not to expose one user's movement patterns to others.

## What is Smart Return?

Smart Return is opt-in return-trip parking assistance. It supports saved settings, prompts, expected return time, notification hooks, and scheduler logic. It is feature-flagged and requires operator/runtime validation before cohort enablement.

## Is Parkio free?

During beta, monetization is TBD. No current revenue is documented. The business model document lists possible future options but does not claim pricing is active.

## What cities are supported?

Supported cities: TBD. The recommended beta approach is to start with one constrained geography before expanding.

## Is Parkio safe?

Parkio has strong hosted-beta application security foundations: gateway-only ingress, RS256/JWKS auth, rotated refresh tokens, HttpOnly web refresh cookies, mobile SecureStore, media scanning, signed media URLs, CORS allow-list, rate limiting, and CI security scanning. Public production security still requires additional operational validation and hardening.

## How does moderation work?

Users can report problematic spots. Moderators handle content outcomes, while admins handle account-level actions such as sanctions, appeals, trust/point overrides, and platform analytics. AI validation is advisory, not an automatic final decision.

## How can I join beta?

Beta access is TBD. A waitlist or invite flow should be added only when there is a real intake path.

## What data is stored?

Parkio stores account/session data, user profile/preferences, spot submissions, spot photos, parking metadata, verification/claim/report events, notifications, moderation records, and operational metrics. PII is intended to stay primarily in auth/user services; other services should use identifiers and minimize sensitive data.

## Can I delete my data?

Self-service account erasure is not evidenced as implemented in the current repo. The beta Play Store draft says users should contact `privacy@parkio.dev` for account and data deletion during beta. A verified account deletion and data-erasure workflow should be completed before public launch.
