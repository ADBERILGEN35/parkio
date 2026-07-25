# Parking Session Release Candidate Manifest

**Prepared:** 2026-07-25  
**Purpose:** Deployable Release Candidate for Parking Session stale-lifecycle hardening  
**Decision gate:** tag only after `git status` is clean on this SHA  

---

## Release identity

| Field | Value |
|---|---|
| Recommended Git tag | `v1.0.0-rc2` |
| Release version identifier | `1.0.0-rc2` (Parking Session stale lifecycle RC) |
| Base HEAD before RC | `b664928ffb55f99c632dafeb0285106e5d409c0a` (`find car`) |
| RC commit SHA | `2b94b20b2f731cf774d6bd469ee3e5a61b595e79` (code/docs RC; encoding fix may follow) |
| Breaking changes | **None** (additive schema + APIs; existing clients remain valid) |

## Recommended Docker image tags

Build from the RC SHA (do not push from this prep step):

| Service | Image tag |
|---|---|
| parking-service | `parkio/parking-service:v1.0.0-rc2` |
| parking-service | `parkio/parking-service:1.0.0-rc2` |
| notification-service | `parkio/notification-service:v1.0.0-rc2` |
| analytics-service | `parkio/analytics-service:v1.0.0-rc2` |
| web (if rebuilt) | `parkio/web:v1.0.0-rc2` |

Also stamp each image with the immutable digest/label `org.opencontainers.image.revision=<RC_SHA>`.

## Affected services

- `parking-service` — V17/V18, stale scheduler, lifecycle-config API, enriched completion + reminder outbox events
- `notification-service` — reminder event consumer, EN/TR copy, deeplink `/map?parkingSession=active`
- `analytics-service` — ignore/ack reminder events; accept enriched completion payload
- `frontend` web + shared packages (`types`, `validation`, `api-client`) — lifecycle-config client, stale confirm UX
- `frontend/apps/mobile-v2` — parity confirm UX + history badges + push routing

## Flyway versions

| Service | Versions in this RC |
|---|---|
| parking-service | **V17** `parking_session_stale_handling` |
| parking-service | **V18** `parking_session_lifecycle_evolution` |
| Prior baseline expected in env | **V16** (spot moderation lifecycle) |

Deterministic, sequential, no out-of-order migrations.

## Configuration checksum (defaults)

Default session policy (must keep `confirm < reminder2 < autoComplete`):

| Key | Default | Env override |
|---|---|---|
| `parkio.parking.session.confirm-after` | `PT24H` | `PARKIO_PARKING_SESSION_CONFIRM_AFTER` |
| `parkio.parking.session.reminder-2-after` | `PT48H` | `PARKIO_PARKING_SESSION_REMINDER_2_AFTER` |
| `parkio.parking.session.auto-complete-after` | `PT72H` | `PARKIO_PARKING_SESSION_AUTO_COMPLETE_AFTER` |
| `parkio.parking.session.reminders-enabled` | `true` | `PARKIO_PARKING_SESSION_REMINDERS_ENABLED` |
| `parkio.parking.session.auto-complete-enabled` | `true` | `PARKIO_PARKING_SESSION_AUTO_COMPLETE_ENABLED` |

Configuration content checksum (sha256 of the `parkio.parking.session` block in `application.yml` as committed with this RC) should be recomputed at deploy freeze:

```bash
# example (PowerShell / Git Bash)
git show HEAD:services/parking-service/src/main/resources/application.yml \
  | awk '/session:/,/^[[:space:]]*[a-z].*:/ {print}' \
  | sha256sum
```

Record the deploy-time checksum in the freeze evidence file.

## Required environment variables

No new **required** secrets. Optional overrides listed above. Ensure Kafka topic for parking session events already used by analytics/notification remains reachable.

## Rollback strategy

1. **App rollback:** redeploy previous image tags built from `b664928` (or last known-good parking/notification/analytics trio).
2. **Do not roll back Flyway V17/V18** in production once applied — columns/indexes are additive and backward compatible with older app binaries that ignore new fields.
3. **Feature kill switches:** set `PARKIO_PARKING_SESSION_REMINDERS_ENABLED=false` and/or `PARKIO_PARKING_SESSION_AUTO_COMPLETE_ENABLED=false` without redeploy if supported by running config refresh; otherwise redeploy with those env vars.
4. **Client rollback:** web/mobile clients that do not call `lifecycle-config` continue to work for start/complete/cancel; only stale UX / Find-my-car gating requires the new endpoint.

## Quality gate evidence (prep)

| Gate | Result |
|---|---|
| Gradle `*ParkingSession*` / notification push / analytics consumer | PASS |
| Flyway PostGIS V17/V18 integration assertion | PASS |
| `@parkio/validation` + `@parkio/api-client` parking tests | PASS |
| Web typecheck | PASS |
| Web MapPage + parking vitest suite | PASS (after lifecycle-config MSW fixture) |
| mobile-v2 typecheck | PASS |
| mobile-v2 parking Jest | PASS |
| OpenAPI | Springdoc annotations + examples updated in-repo (runtime generation) |
| Encoding | V17 restored as UTF-8; working tree scanned for UTF-16 nulls |

## Docs / runbooks included

- `docs/architecture/PARKING-SESSION-LIFECYCLE.md`
- `docs/architecture/PARKING-SESSION-SECURITY-AUDIT.md`
- `docs/operations/parking-session-stale-runbook.md`
- `docs/operations/parking-session-performance.md`
- `docs/operations/sql/parking-session-indexes-*.sql|md`
- `docs/operations/parking-session-rollout-evidence-2026-07-25.md` (freeze abort evidence)
- Grafana dashboard + Prometheus alerts + k6 skeleton

---

## Superseding RC: `v1.0.0-rc3`

`v1.0.0-rc2` (`17fa98ac1b4fb3056e0028da0bbf1a9f506f4910`) stays immutable but is **not**
the publication target. Publishing it was blocked by four failing gates, and clearing
them required source changes, so the fixes ship as a normal follow-up commit tagged
`v1.0.0-rc3`.

**Product scope is unchanged from rc2.** No Parking Session behaviour, API, schema, or
configuration was touched. rc3 adds only:

| Area | Change | Why |
|---|---|---|
| Security (backend) | pin `bcprov-jdk18on` to 1.81.1 via a `media-service` dependency constraint | CVE-2025-14813 CRITICAL blocked the image gate |
| Security (frontend) | `pnpm.overrides` for `axios`, `postcss`, `js-yaml`, `brace-expansion` | 7 fixable HIGH advisories blocked the dependency gate |
| Test correctness | legacy mobile `home.session` suite rewritten for the current redirect-to-map behaviour | the suite asserted a dashboard that no longer exists |
| Test stability | mobile-v2 RNTL `asyncUtilTimeout` raised to 15s | the 1s default timed out under CI contention |
| CI policy | legacy mobile job made advisory; Frontend CI no longer runs the RN apps | see `docs/operations/mobile-release-policy.md` |
| Release workflow | `web` added to the image matrix, tag/clean-tree integrity gates, registry digest verification, `publish_images` input, build-record uploads disabled | image publication was impossible and the draft-release step failed on artifact download |

Flyway targets (V17/V18), affected services, rollback strategy, environment variables,
and the configuration checksum procedure above all apply unchanged to rc3.

Disposition evidence:

- `docs/operations/security-findings-2026-07-25.md`
- `docs/operations/mobile-release-policy.md`
- `docs/operations/parking-session-tag-rollout-2026-07-25.md` (run IDs, image digests)

---


## Superseding RC: `v1.0.0-rc5`

`v1.0.0-rc4` (`9ce171017e2b3e31d8c36ecf06ed831c138f257f`) published all four hosted-beta
images to GHCR and passed the in-workflow registry digest / OCI-label checks, but the
Release job still failed on `actions/attest-build-provenance` because GitHub artifact
attestations are unavailable on user-owned private repositories. Cosign keyless signing
succeeded. The only change in rc5 is skipping that attestation step when
`github.event.repository.private` is true so a private-repo publish can finish green.
Product scope is unchanged from rc2.

**Do not deploy from this document alone.** Tag `v1.0.0-rc2` only when the RC SHA is clean, then proceed with a separate controlled rollout.
