# Public Explore access boundary (anonymous contract)

## Accepted anonymous surface

Anonymous visitors may use `/explore` when `VITE_PUBLIC_EXPLORE_ENABLED=true`:

- Product map, locate, place search, municipal markers (public DTO, limit ≤6)
- Facility **preview** (name, occupancy/freshness presentation, attribution line)
- Contribution CTA → AuthGate (`contribute`) while registration stays CLOSED
- Filters / empty / unavailable states on the public list path

## Intended auth gate (not a regression)

Opening full facility **detail** from anonymous Explore invokes AuthGate with
intent `facilityDetail` and does **not** navigate to `/facilities/:id`.

Evidence in source:

- `PublicExplorePage` → `requireAuth(\`/facilities/${id}\`, 'facilityDetail')`
- `PublicExplorePage.test.tsx` — “gates full detail behind AuthGate”
- Route manifest: `/facilities/:facilityId` is `access: 'protected'`

This matches the certified anonymous Explore contract: public aggregate preview
only; authenticated detail and community precise pins stay behind auth.
Do **not** weaken `/facilities/:id` to public to “fix” preview → login.

Authenticated users use `/map` (and protected `/facilities/:id` when municipal
discovery is baked on). That path is independent of anonymous Explore.

## PA-06

Anonymous Explore must keep `communitySpotCountInScope` null (or suppressed)
per `docs/architecture/g06-public-aggregate-privacy.md`. Municipal `/map`
enablement must not change the public Explore privacy contract.
