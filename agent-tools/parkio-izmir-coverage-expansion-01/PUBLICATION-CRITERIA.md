# Stale / historical inventory — publication criteria

Applies to İZELMAN Bizizmir CSVs (portal **last update 2022-11**) and any other
inventory whose **source date** is known but not re-verified by Parkio field survey.

## Do

- Persist imports with **source content date** from registry (`IzelmanSourceKeys.CONTENT_DATES`),
  checksum of the ingested file, and age class `HISTORICAL` / `AGING`.
- Label UX / provenance: **“last verified unknown / source dated YYYY-MM-DD”**
  (portal or CONTENT_DATES — never the Parkio import timestamp alone).
- Keep **access** explicit: barrier/abone → `RESTRICTED`; unknown → `UNKNOWN`;
  do not invent public roadside permission from an ordinary road geometry.
- Allow authenticated map visibility only when
  `facility-publication-enabled` / `roadside-publication-enabled` is intentionally true
  **and** ops accept historical labeling.
- Prefer **link review** (IZUM↔İZELMAN when enabled) over silent merge of nearby points.

## Do not

- Present Nov-2022 rows as “recently verified.”
- Treat tariff CSV (resource meta Sep 2024 / package Feb 2026) as current prices without review.
- Use successful import `completed_at` as verification date.
- Auto-merge adjacent distinct facilities solely by distance.
- Publish OSM with municipal ownership implication (ODbL attribution must remain OSM).
- Flip production publication flags in this prep track.

## Explore (anonymous)

- Unchanged: only reviewed families (`IZUM`, `ISPARK`). İZELMAN/OSM remain out of Explore
  until product/legal explicitly extends `PublicExplorePublicationPolicy`.

## Release gate checklist (per source)

- [ ] Dry-run counts recorded (raw / accepted / rejected)
- [ ] Isolated non-dry import idempotent on second run
- [ ] Bounding-box / clip rejects documented
- [ ] Duplicate-linked / review queue captured separately from unique facilities
- [ ] Publication still false in prod until final flip step
